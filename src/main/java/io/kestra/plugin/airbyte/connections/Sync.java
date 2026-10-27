package io.kestra.plugin.airbyte.connections;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.event.Level;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.RunnableTaskException;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.plugin.airbyte.ConnectionSnapshots;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionSummary;
import io.kestra.plugin.airbyte.models.JobConfigType;
import io.kestra.plugin.airbyte.models.JobInfo;
import io.kestra.plugin.airbyte.models.JobList;
import io.kestra.plugin.airbyte.models.JobStatus;
import io.kestra.plugin.airbyte.models.JobSummary;
import io.kestra.plugin.airbyte.models.OnPartialSuccess;
import io.kestra.plugin.airbyte.models.SyncMetadata;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run an Airbyte connection sync",
    description = """
        Starts a sync for an Airbyte connection and, by default, waits for the job to finish. Polling runs every second for up to 60 minutes unless you change `pollFrequency` or `maxDuration`. If a sync is already running for the connection, the task adopts it and polls it to completion instead of failing or blindly re-triggering — see `onActiveSync`.

        When the job ends in any state other than `SUCCEEDED`, the task fails but keeps its outputs (`job`, `attempts`, `metadata`, ...), so `errors` and `finally` branches can read them. Set `onPartialSuccess: WARNING` to end in `WARNING` instead when the job failed after committing records.

        The task also exposes the `connection` that ran the job, a `connectionSnapshot` file (connector configurations are never included) and a `jobUrl` to the job logs in the Airbyte web app. When Kestra runs with assets enabled (Enterprise Edition), each replicated stream that committed records is emitted as a table asset with the source stream as its input (also with 0 records when the job SUCCEEDED, with `recordsCommitted: 0`). Assets are emitted only when the task ends in SUCCESS or WARNING, because Kestra drops the assets of a failed task run, and their counts are the same per-stream figures as `metadata`, so a job that ended in WARNING emits no asset. The asset id is `<database>.<schema>.<table>` read from the destination configuration, falling back to `<destinationId>.<namespace>.<table>`. The input of a database source is the source table, a `Table` asset with id `<database>.<schema>.<table>` read from the source configuration, so it matches the JDBC, dbt and Fivetran ids; API sources and unresolved locations keep an `External` input `<sourceId>.<namespace>.<stream>`. If an asset fails, `assets.assetFailureBehavior` applies: `FAIL` fails the task, `WARN` (the default) ends it in WARNING, `IGNORE` only logs at debug level
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Trigger an Airbyte sync and wait for completion",
            code = """
                id: airbyte_sync
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.airbyte.connections.Sync
                    url: http://localhost:8080
                    connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd

                  - id: log_rows_synced
                    type: io.kestra.plugin.core.log.Log
                    message: "Rows synced: {{ outputs.sync.metadata.rowsSynced }}"
                """
        ),
        @Example(
            full = true,
            title = "Trigger a single Airbyte sync on schedule",
            code = """
                id: airbyte_sync
                namespace: company.team

                tasks:
                  - id: data_ingestion
                    type: io.kestra.plugin.airbyte.connections.Sync
                    connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12ab
                    url: http://host.docker.internal:8000/
                    username: "{{ secret('AIRBYTE_USERNAME') }}"
                    password: "{{ secret('AIRBYTE_PASSWORD') }}"

                triggers:
                  - id: every_minute
                    type: io.kestra.plugin.core.trigger.Schedule
                    cron: "*/1 * * * *"
                """
        ),
        @Example(
            full = true,
            title = "Fail immediately instead of adopting an already running sync",
            code = """
                id: airbyte_sync_fail_on_active
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.airbyte.connections.Sync
                    url: http://localhost:8080
                    connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd
                    onActiveSync: FAIL
                """
        ),
        @Example(
            full = true,
            title = "Handle a failed sync and tolerate partial success",
            code = """
                id: airbyte_sync_with_error_handling
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.airbyte.connections.Sync
                    url: http://localhost:8080
                    connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd
                    onPartialSuccess: WARNING

                errors:
                  - id: alert
                    type: io.kestra.plugin.core.log.Log
                    level: ERROR
                    message: |
                      Airbyte job {{ outputs.sync.jobId }} ended {{ outputs.sync.job.status }}:
                      {{ outputs.sync.job.failures[0].externalMessage ?? 'no failure message reported' }}
                      Logs: {{ outputs.sync.jobUrl }}
                """
        )
    },
    metrics = {
        @Metric(
            name = "attempts.count",
            type = Counter.TYPE,
            unit = "attempt",
            description = "Number of attempts made during the Airbyte sync (emitted when `wait` is enabled)"
        ),
        @Metric(
            name = "records.committed",
            type = Counter.TYPE,
            unit = "record",
            description = "Number of records successfully committed (emitted when `wait` is enabled)"
        ),
        @Metric(
            name = "records.emitted",
            type = Counter.TYPE,
            unit = "record",
            description = "Number of records emitted during processing (emitted when `wait` is enabled)"
        ),
        @Metric(
            name = "bytes.emitted",
            type = Counter.TYPE,
            unit = "byte",
            description = "Number of bytes emitted during processing (emitted when `wait` is enabled)"
        ),
        @Metric(
            name = "state.emitted",
            type = Counter.TYPE,
            unit = "message",
            description = "Number of state messages emitted (emitted when `wait` is enabled)"
        ),
        @Metric(
            name = "records.rejected",
            type = Counter.TYPE,
            unit = "record",
            description = "Number of records rejected by the destination, tagged by stream (emitted when `wait` is enabled)"
        ),
        @Metric(
            name = "attempts.failed",
            type = Counter.TYPE,
            unit = "attempt",
            description = "Number of failed attempts during the Airbyte job (emitted when `wait` is enabled)"
        ),
        @Metric(
            name = "duration",
            type = Timer.TYPE,
            description = "Time between the start of the job and the end of its last attempt (emitted when `wait` is enabled)"
        )
    }
)
public class Sync extends AbstractAirbyteConnection implements RunnableTask<Sync.Output> {
    private static final Set<JobStatus> ACTIVE_JOB_STATUS = EnumSet.of(
        JobStatus.PENDING,
        JobStatus.QUEUED,
        JobStatus.RUNNING,
        JobStatus.INCOMPLETE
    );

    @Schema(
        title = "Connection ID",
        description = "Airbyte connection ID to sync"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> connectionId;

    @Schema(
        title = "Wait for completion",
        description = "If `true`, wait for the Airbyte job to reach a terminal state before the task completes. Defaults to `true`"
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> wait = Property.ofValue(true);

    @Schema(
        title = "Maximum wait duration",
        description = "Maximum total time to wait when `wait` is enabled. Defaults to 60 minutes. When a job is adopted from an already running sync, this duration is counted from the moment it is adopted, not from the job's actual start time"
    )
    @Builder.Default
    Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(60));

    @Schema(
        title = "Poll frequency",
        description = "Interval between status checks while waiting for the job to finish. Defaults to 1 second"
    )
    @Builder.Default
    Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(1));

    @Schema(
        title = "Log frequency",
        description = """
            Interval between fetches of the Airbyte logs of the running attempt, independent of `pollFrequency`. Defaults to 10 seconds. \
            Airbyte returns the full log on every call, so a low value costs bandwidth on long jobs. \
            The remaining logs are always fetched once when the job ends. Only applies when `wait` is enabled
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    Property<Duration> logFrequency = Property.ofValue(Duration.ofSeconds(10));

    @Schema(
        title = "Maximum log events",
        description = """
            Maximum number of Airbyte log events forwarded to the Kestra logs and stored in the `airbyteLogs` file, counted from the start of the job. \
            Defaults to 100000 and must be between 0 and 1000000. Later events are dropped and a warning is logged. Set to `0` to disable the Airbyte logs. \
            Only applies when `wait` is enabled
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    Property<Integer> maxLogEvents = Property.ofValue(CheckStatus.DEFAULT_MAX_LOG_EVENTS);

    @Schema(
        title = "Platform log level",
        description = """
            Kestra log level for the Airbyte `platform` events that Airbyte reports at `info` level, such as stage transitions and workload scheduling. \
            Defaults to `INFO`. Use `DEBUG` or `TRACE` to hide this noise at the default log level. \
            `platform` warnings and errors, and the events of the source, destination and orchestrator, always keep their own level. \
            The `airbyteLogs` file keeps the level reported by Airbyte. Only applies when `wait` is enabled
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<Level> platformLogLevel = Property.ofValue(Level.INFO);

    @Schema(
        title = "Behavior when a sync is already running",
        description = """
            Controls what happens when a sync is already running for this connection. A job that is pending, queued, running or incomplete counts as an active sync:
            - `ADOPT` (default): attach to the in-flight job and poll it to completion instead of triggering a new sync.
            - `FAIL`: fail the task immediately.
            - `SKIP`: succeed immediately without waiting, with `alreadyRunning` set to `true` and no `jobId`.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<OnActiveSync> onActiveSync = Property.ofValue(OnActiveSync.ADOPT);

    @Schema(
        title = "Behavior on partial success",
        description = """
            Controls what happens when the job ends `FAILED` but at least one attempt committed records (Airbyte's `partialSuccess`). Only applies when `wait` is enabled:
            - `FAIL` (default): fail the task.
            - `WARNING`: end the task in `WARNING` and return the outputs.
            Any other non-successful outcome always fails the task.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<OnPartialSuccess> onPartialSuccess = Property.ofValue(OnPartialSuccess.FAIL);

    @Deprecated
    @Schema(
        title = "Fail on active sync",
        description = "Deprecated – use `onActiveSync` instead. When explicitly set, it overrides `onActiveSync`: `true` behaves like `onActiveSync: FAIL`, `false` behaves like `onActiveSync: SKIP`"
    )
    @PluginProperty(group = "deprecated")
    private Property<Boolean> failOnActiveSync;

    @Override
    public Sync.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String rConnectionId = runContext.render(this.connectionId).as(String.class).orElseThrow();
        OnActiveSync policy = resolveOnActiveSyncPolicy(runContext);
        var rWait = runContext.render(this.wait).as(Boolean.class).orElseThrow();
        if (rWait) {
            // Fail fast: once the job is triggered, a late validation error would leave it running with no jobId output
            CheckStatus.renderMaxLogEvents(runContext, this.maxLogEvents);
        }

        Optional<Long> activeJobId = findActiveSyncJob(runContext, rConnectionId);
        boolean adopted = activeJobId.isPresent();
        Long jobId;

        if (adopted) {
            jobId = activeJobId.get();
        } else {
            try {
                jobId = triggerSync(runContext, rConnectionId);
            } catch (SyncAlreadyRunningException e) {
                activeJobId = findActiveSyncJob(runContext, rConnectionId);
                if (activeJobId.isEmpty()) {
                    if (policy == OnActiveSync.SKIP) {
                        return skippedOutput();
                    }
                    throw new IllegalStateException(
                        "Airbyte reported a job already running for connection " + rConnectionId + ", but no active sync job was found; retry the task",
                        e
                    );
                }
                adopted = true;
                jobId = activeJobId.get();
            }
        }

        if (adopted) {
            Output policyOutput = applyOnActiveSyncPolicy(policy, rConnectionId, jobId);
            if (policyOutput != null) {
                return policyOutput;
            }
            logger.info("A sync is already running for connection {}, adopting job {}", rConnectionId, jobId);
        }

        var snapshot = ConnectionSnapshots.fetch(this, runContext, rConnectionId).orElse(null);

        if (!rWait) {
            return notWaitingOutput(runContext, jobId, adopted, snapshot);
        }

        CheckStatus checkStatus = CheckStatus.builder()
            .url(getUrl())
            .webappUrl(getWebappUrl())
            .username(getUsername())
            .password(getPassword())
            .token(getToken())
            .applicationCredentials(getApplicationCredentials())
            .pollFrequency(pollFrequency)
            .logFrequency(logFrequency)
            .maxLogEvents(maxLogEvents)
            .platformLogLevel(platformLogLevel)
            .maxDuration(maxDuration)
            .onPartialSuccess(onPartialSuccess)
            .jobId(Property.ofValue(jobId.toString()))
            .assets(getAssets())
            .build();

        try {
            return fromCheckStatus(jobId, adopted, checkStatus.run(runContext, snapshot, false));
        } catch (RunnableTaskException e) {
            if (e.getOutput() instanceof CheckStatus.Output failed) {
                throw new RunnableTaskException(e.getMessage(), e, fromCheckStatus(jobId, adopted, failed));
            }
            throw e;
        }
    }

    private static Output fromCheckStatus(Long jobId, boolean adopted, CheckStatus.Output checkStatusOutput) {
        return Output.builder()
            .jobId(jobId)
            .alreadyRunning(adopted)
            .adopted(adopted)
            .job(checkStatusOutput.getJob())
            .attempts(checkStatusOutput.getAttempts())
            .airbyteLogs(checkStatusOutput.getAirbyteLogs())
            .metadata(checkStatusOutput.getMetadata())
            .connection(checkStatusOutput.getConnection())
            .connectionSnapshot(checkStatusOutput.getConnectionSnapshot())
            .jobUrl(checkStatusOutput.getJobUrl())
            .state(checkStatusOutput.finalState().orElse(null))
            .build();
    }

    private Output notWaitingOutput(RunContext runContext, Long jobId, boolean adopted, ConnectionSnapshot snapshot) throws Exception {
        var builder = Output.builder()
            .jobId(jobId)
            .alreadyRunning(adopted)
            .adopted(adopted);

        if (snapshot != null) {
            try {
                builder.connectionSnapshot(ConnectionSnapshots.store(runContext, snapshot));
            } catch (Exception e) {
                runContext.logger().warn("Unable to store the connection snapshot: {}", e.getMessage());
            }
            builder
                .connection(ConnectionSummary.from(snapshot))
                .jobUrl(
                    ConnectionSnapshots.jobUrl(
                        webappBaseUrl(runContext), snapshot.resolvedWorkspaceId(), snapshot.getConnection().getConnectionId(), jobId, null
                    )
                );
        }
        return builder.build();
    }

    private OnActiveSync resolveOnActiveSyncPolicy(RunContext runContext) throws IllegalVariableEvaluationException {
        if (this.failOnActiveSync != null) {
            Boolean legacy = runContext.render(this.failOnActiveSync).as(Boolean.class).orElse(null);
            if (legacy != null) {
                return legacy ? OnActiveSync.FAIL : OnActiveSync.SKIP;
            }
        }
        return runContext.render(this.onActiveSync).as(OnActiveSync.class).orElse(OnActiveSync.ADOPT);
    }

    private Output applyOnActiveSyncPolicy(OnActiveSync policy, String connectionId, Long jobId) throws SyncAlreadyRunningException {
        return switch (policy) {
            case FAIL -> throw new SyncAlreadyRunningException(
                "A sync is already running for connection " + connectionId + " (job " + jobId + ")"
            );
            case SKIP -> skippedOutput();
            case ADOPT -> null;
        };
    }

    private static Output skippedOutput() {
        return Output.builder()
            .alreadyRunning(true)
            .adopted(false)
            .jobId(null)
            .build();
    }

    private Long triggerSync(RunContext runContext, String connectionId) throws Exception {
        HttpRequest.HttpRequestBuilder syncRequest = HttpRequest.builder()
            .uri(URI.create(runContext.render(getUrl()).as(String.class).orElseThrow() + "/api/v1/connections/sync/"))
            .method("POST")
            .addHeader("Accept-Encoding", "identity")
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(Map.of("connectionId", connectionId))
                    .build()
            );

        HttpResponse<JobInfo> syncResponse;
        try {
            syncResponse = this.request(runContext, syncRequest, JobInfo.class);
        } catch (HttpClientException e) {
            throw new RuntimeException("Request failed with error: " + e.getMessage(), e);
        }

        JobInfo jobInfoRead = Optional.ofNullable(syncResponse.getBody())
            .orElseThrow(() -> new IllegalStateException("Missing body on trigger"));

        runContext.logger().info("Job status {} with response: {}", syncResponse.getStatus(), jobInfoRead);
        return jobInfoRead.getJob().getId();
    }

    private Optional<Long> findActiveSyncJob(RunContext runContext, String connectionId) throws Exception {
        HttpRequest.HttpRequestBuilder listRequest = HttpRequest.builder()
            .uri(URI.create(runContext.render(getUrl()).as(String.class).orElseThrow() + "/api/v1/jobs/list"))
            .method("POST")
            .addHeader("Accept-Encoding", "identity")
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(
                        Map.of(
                            "configTypes", List.of("sync"),
                            "configId", connectionId,
                            "pagination", Map.of("pageSize", 5, "rowOffset", 0)
                        )
                    )
                    .build()
            );

        HttpResponse<JobList> listResponse = this.request(runContext, listRequest, JobList.class);

        return Optional.ofNullable(listResponse.getBody())
            .map(JobList::getJobs)
            .orElseGet(List::of)
            .stream()
            .filter(
                jobInfo -> jobInfo.getJob() != null
                    // Defense-in-depth: the request above already restricts to "sync" via `configTypes`, but a server
                    // that ignores/mis-applies that filter must not be able to get a reset/clear job silently adopted.
                    && jobInfo.getJob().getConfigType() == JobConfigType.SYNC
                    && ACTIVE_JOB_STATUS.contains(jobInfo.getJob().getStatus())
            )
            .map(jobInfo -> jobInfo.getJob().getId())
            .max(Comparator.naturalOrder());
    }

    public enum OnActiveSync {
        ADOPT,
        FAIL,
        SKIP
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Job ID",
            description = "Airbyte job ID for the sync. Set for every outcome except `onActiveSync: SKIP`, whether the job was newly triggered or adopted from an already running sync"
        )
        private final Long jobId;

        @Schema(
            title = "Already running",
            description = "Whether a sync was already running for the connection, either adopted (`onActiveSync: ADOPT`) or skipped (`onActiveSync: SKIP`)"
        )
        private final Boolean alreadyRunning;

        @Schema(
            title = "Adopted",
            description = "Whether `jobId` refers to a sync that was already running and got adopted, rather than a sync newly triggered by this task"
        )
        private final Boolean adopted;

        @Schema(
            title = "Job summary",
            description = "Status, timing, attempt counts and sanitized failure reasons of the Airbyte job. Only available when `wait` is enabled"
        )
        private final JobSummary job;

        @Schema(
            title = "Attempts file",
            description = "Internal storage URI of an Ion file with one row per Airbyte attempt. Only available when `wait` is enabled"
        )
        @PluginProperty(internalStorageURI = true)
        private final URI attempts;

        @Schema(
            title = "Airbyte logs file",
            description = CheckStatus.AIRBYTE_LOGS_DESCRIPTION + ". Only available when `wait` is enabled"
        )
        @PluginProperty(internalStorageURI = true)
        private final URI airbyteLogs;

        @Schema(
            title = "Sync metadata",
            description = "Rows synced and source and destination tables affected by the completed Airbyte job. Only available when `wait` is enabled"
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
