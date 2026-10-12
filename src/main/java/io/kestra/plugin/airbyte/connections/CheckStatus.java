package io.kestra.plugin.airbyte.connections;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.plugin.airbyte.models.Attempt;
import io.kestra.plugin.airbyte.models.AttemptInfo;
import io.kestra.plugin.airbyte.models.AttemptStatus;
import io.kestra.plugin.airbyte.models.JobInfo;
import io.kestra.plugin.airbyte.models.JobStatus;
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
    description = "Polls Airbyte until a job reaches a terminal state, streams attempt logs, and emits sync metrics. Polling runs every second for up to 60 minutes unless you change `pollFrequency` or `maxDuration`"
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
        )
    }
)
public class CheckStatus extends AbstractAirbyteConnection implements RunnableTask<CheckStatus.Output> {
    private static final List<JobStatus> ENDED_JOB_STATUS = List.of(
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
        description = "Maximum total time to wait for the job to finish. Defaults to 60 minutes"
    )
    @Builder.Default
    Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(60));

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
        Logger logger = runContext.logger();

        // Init with 1 as when triggering sync, an attempt is automatically generated
        AtomicInteger attemptCounter = new AtomicInteger(1);

        // Check rendered jobId provided is a long
        Long jobIdRendered = Long.parseLong(runContext.render(this.jobId).as(String.class).orElse(null));

        // wait for end
        JobInfo finalJobStatus = Await.until(
            throwSupplier(() ->
            {
                HttpRequest.HttpRequestBuilder fetchJobRequest = HttpRequest.builder()
                    .uri(URI.create(getUrl() + "/api/v1/jobs/get/"))
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

        // failure message
        attempts(finalJobStatus)
            .stream()
            .map(AttemptInfo::getAttempt)
            .filter(Objects::nonNull)
            .map(Attempt::getFailureSummary)
            .filter(Objects::nonNull)
            .forEach(attemptFailureSummary -> logger.warn("Failure with reason {}", attemptFailureSummary));

        // handle failed attempt
        if (!finalJobStatus.getJob().getStatus().equals(JobStatus.SUCCEEDED)) {
            int attemptCount = attempts(finalJobStatus).size();
            throw new Exception(
                "Failed run with status '" + finalJobStatus.getJob().getStatus() +
                    "' after " + attemptCount + " attempt(s) : " + finalJobStatus
            );
        }

        // metrics
        runContext.metric(Counter.of("attempts.count", attempts(finalJobStatus).size()));

        attempts(finalJobStatus)
            .stream()
            .map(AttemptInfo::getAttempt)
            .filter(Objects::nonNull)
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
            });

        return Output.builder()
            .finalJobStatus(finalJobStatus.getJob().getStatus().toString())
            .metadata(syncMetadata(finalJobStatus))
            .build();
    }

    private static SyncMetadata syncMetadata(JobInfo jobInfo) {
        var source = new LinkedHashMap<StreamKey, Long>();
        var destination = new LinkedHashMap<StreamKey, Long>();
        var successfulAttempts = attempts(jobInfo).stream()
            .map(AttemptInfo::getAttempt)
            .filter(Objects::nonNull)
            .filter(attempt -> attempt.getStatus() == AttemptStatus.SUCCEEDED)
            .toList();

        successfulAttempts.stream()
            .flatMap(attempt -> Optional.ofNullable(attempt.getStreamStats()).orElseGet(List::of).stream())
            .filter(Objects::nonNull)
            .forEach(streamStats ->
            {
                var streamKey = new StreamKey(streamStats.getStreamName(), streamStats.getStreamNamespace());
                if (streamStats.getStats() == null) {
                    source.putIfAbsent(streamKey, null);
                    destination.putIfAbsent(streamKey, null);
                    return;
                }

                merge(source, streamKey, streamStats.getStats().getRecordsEmitted());
                merge(destination, streamKey, streamStats.getStats().getRecordsCommitted());
            });

        var committedRows = destination.values().stream()
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
            .source(toTables(source))
            .destination(toTables(destination))
            .build();
    }

    private static void merge(Map<StreamKey, Long> tables, StreamKey streamKey, Long rows) {
        if (rows == null) {
            tables.putIfAbsent(streamKey, null);
        } else {
            tables.merge(streamKey, rows, Long::sum);
        }
    }

    private static List<SyncMetadata.Table> toTables(Map<StreamKey, Long> tables) {
        return tables.entrySet().stream()
            .map(
                entry -> SyncMetadata.Table.builder()
                    .name(entry.getKey().name())
                    .namespace(entry.getKey().namespace())
                    .rows(entry.getValue())
                    .build()
            )
            .toList();
    }

    private static List<AttemptInfo> attempts(JobInfo jobInfo) {
        return Optional.ofNullable(jobInfo.getAttempts()).orElseGet(List::of);
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
        @Schema(
            title = "Final job status",
            description = "Terminal Airbyte job status returned by the task"
        )
        private final String finalJobStatus;

        @Schema(
            title = "Sync metadata",
            description = "Rows synced and source and destination tables affected by the completed Airbyte job"
        )
        private final SyncMetadata metadata;
    }
}
