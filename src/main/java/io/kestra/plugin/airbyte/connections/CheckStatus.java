package io.kestra.plugin.airbyte.connections;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.RunnableTaskException;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.plugin.airbyte.ConnectionSnapshots;
import io.kestra.plugin.airbyte.StreamAssets;
import io.kestra.plugin.airbyte.models.Attempt;
import io.kestra.plugin.airbyte.models.AttemptInfo;
import io.kestra.plugin.airbyte.models.AttemptStats;
import io.kestra.plugin.airbyte.models.AttemptStatus;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionSummary;
import io.kestra.plugin.airbyte.models.JobConfigType;
import io.kestra.plugin.airbyte.models.JobInfo;
import io.kestra.plugin.airbyte.models.JobStatus;
import io.kestra.plugin.airbyte.models.JobSummary;
import io.kestra.plugin.airbyte.models.OnPartialSuccess;
import io.kestra.plugin.airbyte.models.SyncMetadata;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.utils.Rethrow.throwSupplier;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Wait for an Airbyte job",
    description = """
        Polls Airbyte until a job reaches a terminal state, streams attempt logs, and emits sync metrics. Polling runs every second for up to 60 minutes unless you change `pollFrequency` or `maxDuration`.

        When the job ends in any state other than `SUCCEEDED`, the task fails but keeps its outputs (`job`, `attempts`, `metadata`, ...), so `errors` and `finally` branches can read them. Set `onPartialSuccess: WARNING` to end in `WARNING` instead when the job failed after committing records.

        Each Airbyte attempt is also recorded as a sub task run named `attempt-<n>`, and the `job.failures` output lists sanitized failure reasons: internal messages and stack traces are never kept.

        When Kestra runs with assets enabled (Enterprise Edition), every replicated stream that committed records is emitted as a table asset, with the source stream as its input, but only when the task ends in SUCCESS or WARNING (Kestra drops the assets of a failed task run). Counts are the same per-stream figures as `metadata`, so a job that ended in WARNING emits no asset. The asset id is `<destinationId>.<namespace>.<table>` and uses Airbyte's logical names, which can differ from the physical table name when the destination normalizes them
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: airbyte_check_status
                namespace: company.team

                tasks:
                  - id: check_status
                    type: io.kestra.plugin.airbyte.connections.CheckStatus
                    url: http://localhost:8080
                    jobId: "970"
                """
        ),
        @Example(
            full = true,
            title = "Read the job outputs when the Airbyte job fails",
            code = """
                id: airbyte_check_status_on_failure
                namespace: company.team

                tasks:
                  - id: check_status
                    type: io.kestra.plugin.airbyte.connections.CheckStatus
                    url: http://localhost:8080
                    jobId: "970"
                    onPartialSuccess: WARNING

                errors:
                  - id: alert
                    type: io.kestra.plugin.core.log.Log
                    level: ERROR
                    message: "Airbyte job failed: {{ outputs.check_status.job.failures[0].externalMessage ?? 'no failure message reported' }}. See {{ outputs.check_status.jobUrl }}"
                """
        )
    },
    metrics = {
        @Metric(
            name = "attempts.count",
            type = Counter.TYPE,
            unit = "attempt",
            description = "Number of attempts made during the Airbyte sync"
        ),
        @Metric(
            name = "records.committed",
            type = Counter.TYPE,
            unit = "record",
            description = "Number of records successfully committed"
        ),
        @Metric(
            name = "records.emitted",
            type = Counter.TYPE,
            unit = "record",
            description = "Number of records emitted during processing"
        ),
        @Metric(
            name = "bytes.emitted",
            type = Counter.TYPE,
            unit = "byte",
            description = "Number of bytes emitted during processing"
        ),
        @Metric(
            name = "state.emitted",
            type = Counter.TYPE,
            unit = "message",
            description = "Number of state messages emitted"
        ),
        @Metric(
            name = "records.rejected",
            type = Counter.TYPE,
            unit = "record",
            description = "Number of records rejected by the destination, tagged by stream"
        ),
        @Metric(
            name = "attempts.failed",
            type = Counter.TYPE,
            unit = "attempt",
            description = "Number of failed attempts during the Airbyte job"
        ),
        @Metric(
            name = "duration",
            type = Timer.TYPE,
            description = "Time between the start of the job and the end of its last attempt"
        )
    }
)
public class CheckStatus extends AbstractAirbyteConnection implements RunnableTask<CheckStatus.Output> {
    private static final Set<JobConfigType> SNAPSHOT_JOB_TYPES = EnumSet.of(
        JobConfigType.SYNC,
        JobConfigType.RESET_CONNECTION,
        JobConfigType.REFRESH,
        JobConfigType.CLEAR
    );

    private static final Set<JobConfigType> ASSET_JOB_TYPES = EnumSet.of(JobConfigType.SYNC, JobConfigType.REFRESH);

    private static final Set<JobStatus> ENDED_JOB_STATUS = EnumSet.of(
        JobStatus.FAILED,
        JobStatus.CANCELLED,
        JobStatus.SUCCEEDED
    );

    @Schema(
        title = "Job ID",
        description = "Airbyte job ID to monitor."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> jobId;

    @Schema(
        title = "Maximum wait duration",
        description = "Maximum total time to wait for the job to finish, including Airbyte's automatic retry attempts. Defaults to 60 minutes"
    )
    @Builder.Default
    Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(60));

    @Schema(
        title = "Behavior on partial success",
        description = """
            Controls what happens when the job ends `FAILED` but at least one attempt committed records (Airbyte's `partialSuccess`):
            - `FAIL` (default): fail the task.
            - `WARNING`: end the task in `WARNING` and return the outputs.
            Any other non-successful outcome always fails the task.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<OnPartialSuccess> onPartialSuccess = Property.ofValue(OnPartialSuccess.FAIL);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private transient Map<Integer, Integer> loggedLine = new HashMap<>();

    @Schema(
        title = "Poll frequency",
        description = "Interval between Airbyte job status checks. Defaults to 1 second"
    )
    @Builder.Default
    Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(1));

    @Override
    public CheckStatus.Output run(RunContext runContext) throws Exception {
        return run(runContext, null, true);
    }

    CheckStatus.Output run(RunContext runContext, ConnectionSnapshot snapshot, boolean fetchSnapshotIfMissing) throws Exception {
        Logger logger = runContext.logger();

        var unknownStatusLogged = new AtomicBoolean(false);

        // Init with 1 as when triggering sync, an attempt is automatically generated
        AtomicInteger attemptCounter = new AtomicInteger(1);

        // Check rendered jobId provided is a long
        Long jobIdRendered = Long.parseLong(runContext.render(this.jobId).as(String.class).orElse(null));
        String rUrl = runContext.render(getUrl()).as(String.class).orElseThrow();

        // wait for end
        JobInfo finalJobStatus = Await.until(
            throwSupplier(() ->
            {
                HttpRequest.HttpRequestBuilder fetchJobRequest = HttpRequest.builder()
                    .uri(URI.create(rUrl + "/api/v1/jobs/get/"))
                    .method("POST")
                    .body(
                        HttpRequest.JsonRequestBody.builder()
                            .content(Map.of("id", jobIdRendered))
                            .build()
                    );

                HttpResponse<JobInfo> response = this.request(runContext, fetchJobRequest, JobInfo.class);

                if (response.getBody() != null) {
                    JobInfo jobStatus = response.getBody();
                    sendLog(logger, jobStatus);

                    if (jobStatus.getJob().getStatus() == null && unknownStatusLogged.compareAndSet(false, true)) {
                        logger.warn(
                            "Status of job {} is not recognised by this plugin; polling continues until the job is failed, cancelled or succeeded, or until maxDuration is reached",
                            jobIdRendered
                        );
                    }

                    // ended
                    if (ENDED_JOB_STATUS.contains(jobStatus.getJob().getStatus())) {
                        return jobStatus;
                    }

                    // Handle case of failed attempt, Airbyte started a new attempt
                    if (attempts(jobStatus).size() > attemptCounter.get()) {
                        logger.warn("Previous attempt failed, creating a new sync attempt ...");
                        attemptCounter.getAndIncrement();
                    }
                }
                return null;
            }),
            runContext.render(this.pollFrequency).as(Duration.class).orElseThrow(),
            runContext.render(this.maxDuration).as(Duration.class).orElseThrow()
        );

        var job = finalJobStatus.getJob();
        var status = job.getStatus();
        var summary = JobReports.summary(finalJobStatus);
        var attemptRows = JobReports.rows(finalJobStatus);

        attemptRows.forEach(
            row -> row.getFailures()
                .forEach(
                    failure -> logger.warn(
                        "Attempt {} failed [{}/{}]: {}", row.getAttemptNumber(), failure.getOrigin(), failure.getType(),
                        Optional.ofNullable(failure.getExternalMessage()).orElse("no message reported by Airbyte")
                    )
                )
        );

        var attemptsUri = JobReports.storeAttempts(runContext, attemptRows);
        JobReports.emitAttemptTaskRuns(runContext, finalJobStatus, attemptRows);
        emitMetrics(runContext, finalJobStatus, summary);

        if (snapshot == null && fetchSnapshotIfMissing && job.getConfigId() != null && SNAPSHOT_JOB_TYPES.contains(job.getConfigType())) {
            snapshot = ConnectionSnapshots.fetch(this, runContext, job.getConfigId()).orElse(null);
        }

        URI snapshotUri = null;
        ConnectionSummary connection = null;
        String jobUrl = null;
        if (snapshot != null) {
            try {
                snapshotUri = ConnectionSnapshots.store(runContext, snapshot);
            } catch (Exception e) {
                logger.warn("Unable to store the connection snapshot: {}", e.getMessage());
            }
            connection = ConnectionSummary.from(snapshot);
            jobUrl = ConnectionSnapshots.jobUrl(
                webappBaseUrl(runContext),
                snapshot.resolvedWorkspaceId(),
                snapshot.getConnection().getConnectionId(),
                job.getId(),
                summary.getAttemptCount() > 0 ? summary.getAttemptCount() - 1 : null
            );
        }

        var partialSuccessAsWarning = status == JobStatus.FAILED
            && Boolean.TRUE.equals(summary.getPartialSuccess())
            && runContext.render(this.onPartialSuccess).as(OnPartialSuccess.class).orElse(OnPartialSuccess.FAIL) == OnPartialSuccess.WARNING;

        var metadata = syncMetadata(finalJobStatus);

        // Core drops assets of failed runs, and a clear or reset would give false lineage.
        if (snapshot != null && ASSET_JOB_TYPES.contains(job.getConfigType()) && (status == JobStatus.SUCCEEDED || partialSuccessAsWarning)) {
            StreamAssets.emit(
                runContext,
                StreamAssets.plan(snapshot, StreamAssets.committed(metadata), job.getId(), String.valueOf(status), status == JobStatus.SUCCEEDED)
            );
        }

        var output = Output.builder()
            .finalJobStatus(String.valueOf(status))
            .job(summary)
            .attempts(attemptsUri)
            .metadata(metadata)
            .connection(connection)
            .connectionSnapshot(snapshotUri)
            .jobUrl(jobUrl)
            .state(partialSuccessAsWarning ? State.Type.WARNING : null)
            .build();

        if (status == JobStatus.SUCCEEDED) {
            return output;
        }

        var message = JobReports.failureMessage(finalJobStatus, summary, jobUrl);
        if (partialSuccessAsWarning) {
            logger.warn("{}. Ending in WARNING because `onPartialSuccess` is `WARNING`", message);
            return output;
        }

        throw new RunnableTaskException(message, output);
    }

    private static void emitMetrics(RunContext runContext, JobInfo finalJobStatus, JobSummary summary) {
        runContext.metric(Counter.of("attempts.count", JobReports.attempts(finalJobStatus).size()));
        runContext.metric(Counter.of("attempts.failed", summary.getFailedAttemptCount()));

        if (summary.getDuration() != null) {
            runContext.metric(Timer.of("duration", summary.getDuration()));
        }

        JobReports.attemptList(finalJobStatus)
            .stream()
            .filter(attempt -> attempt.getStreamStats() != null)
            .flatMap(attempt -> attempt.getStreamStats().stream())
            .filter(streamStats -> streamStats != null && streamStats.getStats() != null)
            .forEach(o ->
            {
                if (o.getStats().getRecordsCommitted() != null) {
                    runContext.metric(Counter.of("records.committed", o.getStats().getRecordsCommitted(), "stream", o.getStreamName()));
                }
                if (o.getStats().getRecordsEmitted() != null) {
                    runContext.metric(Counter.of("records.emitted", o.getStats().getRecordsEmitted(), "stream", o.getStreamName()));
                }
                if (o.getStats().getBytesEmitted() != null) {
                    runContext.metric(Counter.of("bytes.emitted", o.getStats().getBytesEmitted(), "stream", o.getStreamName()));
                }
                if (o.getStats().getStateMessagesEmitted() != null) {
                    runContext.metric(Counter.of("state.emitted", o.getStats().getStateMessagesEmitted(), "stream", o.getStreamName()));
                }
                if (o.getStats().getRecordsRejected() != null) {
                    runContext.metric(Counter.of("records.rejected", o.getStats().getRecordsRejected(), "stream", o.getStreamName()));
                }
            });
    }

    private static SyncMetadata syncMetadata(JobInfo jobInfo) {
        var source = new LinkedHashMap<StreamKey, Totals>();
        var destination = new LinkedHashMap<StreamKey, Totals>();
        var successfulAttempts = JobReports.attemptList(jobInfo).stream()
            .filter(attempt -> attempt.getStatus() == AttemptStatus.SUCCEEDED)
            .toList();

        successfulAttempts.stream()
            .flatMap(attempt -> Optional.ofNullable(attempt.getStreamStats()).orElseGet(List::of).stream())
            .filter(Objects::nonNull)
            .forEach(streamStats ->
            {
                var streamKey = new StreamKey(streamStats.getStreamName(), streamStats.getStreamNamespace());
                var sourceTotals = source.computeIfAbsent(streamKey, key -> new Totals());
                var destinationTotals = destination.computeIfAbsent(streamKey, key -> new Totals());
                var stats = streamStats.getStats();
                if (stats == null) {
                    return;
                }

                sourceTotals.rows = JobReports.sum(sourceTotals.rows, stats.getRecordsEmitted());
                sourceTotals.bytes = JobReports.sum(sourceTotals.bytes, stats.getBytesEmitted());
                destinationTotals.rows = JobReports.sum(destinationTotals.rows, stats.getRecordsCommitted());
                destinationTotals.bytes = JobReports.sum(destinationTotals.bytes, stats.getBytesCommitted());
                destinationTotals.emittedBytes = JobReports.sum(destinationTotals.emittedBytes, stats.getBytesEmitted());
                destinationTotals.rejected = JobReports.sum(destinationTotals.rejected, stats.getRecordsRejected());
            });

        var committedRows = destination.values().stream()
            .map(totals -> totals.rows)
            .filter(Objects::nonNull)
            .reduce(Long::sum);
        var rowsSynced = committedRows.orElseGet(() ->
            successfulAttempts.stream()
                .map(Attempt::getRecordsSynced)
                .filter(Objects::nonNull)
                .reduce(0L, Long::sum)
        );

        return SyncMetadata.builder()
            .rowsSynced(rowsSynced)
            .bytesSynced(
                destination.values().stream().map(totals -> totals.bytes).filter(Objects::nonNull).reduce(Long::sum)
                    .or(() -> successfulAttempts.stream().map(Attempt::getBytesSynced).filter(Objects::nonNull).reduce(Long::sum))
                    .or(
                        () -> successfulAttempts.stream().map(Attempt::getTotalStats).filter(Objects::nonNull).map(AttemptStats::getBytesEmitted)
                            .filter(Objects::nonNull).reduce(Long::sum)
                    )
                    .orElse(null)
            )
            .recordsRejected(destination.values().stream().map(totals -> totals.rejected).filter(Objects::nonNull).reduce(Long::sum).orElse(null))
            .source(toTables(source))
            .destination(toTables(destination))
            .build();
    }

    private static List<SyncMetadata.Table> toTables(Map<StreamKey, Totals> tables) {
        return tables.entrySet().stream()
            .map(
                entry -> SyncMetadata.Table.builder()
                    .name(entry.getKey().name())
                    .namespace(entry.getKey().namespace())
                    .rows(entry.getValue().rows)
                    .bytes(entry.getValue().bytes != null ? entry.getValue().bytes : entry.getValue().emittedBytes)
                    .recordsRejected(entry.getValue().rejected)
                    .build()
            )
            .toList();
    }

    private static List<AttemptInfo> attempts(JobInfo jobInfo) {
        return JobReports.attempts(jobInfo);
    }

    private static final class Totals {
        Long rows;
        Long bytes;
        Long emittedBytes;
        Long rejected;
    }

    private record StreamKey(String name, String namespace) {
    }

    private void sendLog(Logger logger, JobInfo job) {
        int index = 0;

        for (AttemptInfo attempt : attempts(job)) {
            if (!loggedLine.containsKey(index) || attempt.getLogs().getLogLines().size() > loggedLine.get(index)) {
                attempt.getLogs()
                    .getLogLines()
                    .subList(!loggedLine.containsKey(index) ? 0 : loggedLine.get(index) + 1, attempt.getLogs().getLogLines().size())
                    .forEach(msg ->
                    {
                        if (msg.contains("ERROR[")) {
                            logger.error(msg);
                        } else if (msg.contains("WARN[")) {
                            logger.warn(msg);
                        } else if (msg.contains("DEBUG[")) {
                            logger.debug(msg);
                        } else if (msg.contains("TRACE[")) {
                            logger.trace(msg);
                        } else {
                            logger.info(msg);
                        }
                    });

                loggedLine.put(index, attempt.getLogs().getLogLines().size());
            }
            index++;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Deprecated
        @Schema(
            title = "Final job status",
            description = "Deprecated – use `job.status` instead. Terminal Airbyte job status returned by the task"
        )
        private final String finalJobStatus;

        @Schema(
            title = "Job summary",
            description = "Status, timing, attempt counts and sanitized failure reasons of the Airbyte job"
        )
        private final JobSummary job;

        @Schema(
            title = "Attempts file",
            description = "Internal storage URI of an Ion file with one row per Airbyte attempt: status, timing, failure reasons and per-stream statistics"
        )
        @PluginProperty(internalStorageURI = true)
        private final URI attempts;

        @Schema(
            title = "Sync metadata",
            description = "Rows synced and source and destination tables affected by the completed Airbyte job"
        )
        private final SyncMetadata metadata;

        @Schema(
            title = "Connection",
            description = "Summary of the connection, source and destination that ran the job. `null` when their configuration could not be read"
        )
        private final ConnectionSummary connection;

        @Schema(
            title = "Connection snapshot file",
            description = "Internal storage URI of a JSON file with the connection, source and destination configuration at the time of the job. Connector configurations are never included. `null` when it could not be read"
        )
        @PluginProperty(internalStorageURI = true)
        private final URI connectionSnapshot;

        @Schema(
            title = "Job URL",
            description = "Link to the job logs in the Airbyte web app, built from `webappUrl` or `url`. `null` when the workspace could not be resolved"
        )
        private final String jobUrl;

        @JsonIgnore
        @Getter(AccessLevel.NONE)
        private final State.Type state;

        @Override
        public Optional<State.Type> finalState() {
            return Optional.ofNullable(state);
        }
    }
}
