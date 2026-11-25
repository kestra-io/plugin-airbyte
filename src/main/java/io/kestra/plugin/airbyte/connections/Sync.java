package io.kestra.plugin.airbyte.connections;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
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
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.plugin.airbyte.ConnectionSnapshots;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionSummary;
import io.kestra.plugin.airbyte.models.JobConfigType;
import io.kestra.plugin.airbyte.models.Job;
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
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run an Airbyte connection sync",
    description = """
        Starts a sync for an Airbyte connection and, by default, waits for the job to finish. Polling runs every second for up to 60 minutes unless you change `pollFrequency` or `maxDuration`. If a sync is already running for the connection, the task adopts it and polls it to completion instead of failing or blindly re-triggering — see `onActiveSync`.

        A retry or resubmission of the task run resumes the job it already triggered instead of starting a new sync, unless that job failed or was cancelled. Killing the task cancels a job it triggered, never an adopted one.

        When the job ends in any state other than `SUCCEEDED`, the task fails but keeps its outputs (`job`, `attempts`, `metadata`, ...), so `errors` and `finally` branches can read them. Set `onPartialSuccess: WARNING` to end in `WARNING` instead when the job failed after committing records.

        The task also exposes the `connection` that ran the job, a `connectionSnapshot` file (connector configurations are never included) and a `jobUrl` to the job logs in the Airbyte web app. When Kestra runs with assets enabled (Enterprise Edition), each replicated stream that committed records is emitted as a table asset with the source stream as its input. Assets are emitted only when the task ends in SUCCESS or WARNING, because Kestra drops the assets of a failed task run, and their counts are the same per-stream figures as `metadata`, so a job that ended in WARNING emits no asset
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
        ),
        @Metric(
            name = "resumed",
            type = Counter.TYPE,
            description = "Whether the task resumed the job this task run triggered before it was interrupted (1) instead of starting a new sync (0)"
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

    static final String CHECKPOINT_KEY = "airbyte.jobId";

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Builder.Default
    private final AtomicReference<Runnable> killable = new AtomicReference<>();

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Builder.Default
    private final AtomicBoolean isKilled = new AtomicBoolean(false);

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

        var resumed = this.findCheckpointedJob(runContext, rConnectionId);
        runContext.metric(Counter.of("resumed", resumed.isPresent() ? 1 : 0));

        boolean adopted = false;
        Long jobId;

        if (resumed.isPresent()) {
            jobId = resumed.get().getId();
            logger.info("Resuming job {} (status {}) this task run triggered before it was interrupted instead of triggering a new sync", jobId, resumed.get().getStatus());
        } else {
            var activeJobId = findActiveSyncJob(runContext, rConnectionId);
            adopted = activeJobId.isPresent();

            if (adopted) {
                jobId = activeJobId.get();
            } else {
                if (isKilled.get()) {
                    throw new IllegalStateException("The task was killed before the Airbyte sync was triggered.");
                }
                try {
                    jobId = triggerSync(runContext, rConnectionId);
                } catch (SyncAlreadyRunningException e) {
                    activeJobId = findActiveSyncJob(runContext, rConnectionId);
                    if (activeJobId.isEmpty()) {
                        if (policy == OnActiveSync.SKIP) {
                            return skippedOutput();
                        }
                        throw new IllegalStateException(
                            "Airbyte reported a job already running for connection %s, but no active sync job was found. Retry the task.".formatted(rConnectionId),
                            e
                        );
                    }
                    adopted = true;
                    jobId = activeJobId.get();
                }
            }

            if (adopted) {
                var policyOutput = applyOnActiveSyncPolicy(policy, rConnectionId, jobId);
                if (policyOutput != null) {
                    return policyOutput;
                }
                logger.info("A sync is already running for connection {}, adopting job {}", rConnectionId, jobId);
            }
        }

        // An adopted job was started elsewhere: no checkpoint and no cancel on kill, a retry adopts it again.
        if (!adopted) {
            this.track(runContext, jobId);
        }

        var snapshot = ConnectionSnapshots.fetch(this, runContext, rConnectionId).orElse(null);

        if (!runContext.render(this.wait).as(Boolean.class).orElseThrow()) {
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
            .maxDuration(maxDuration)
            .onPartialSuccess(onPartialSuccess)
            .jobId(Property.ofValue(jobId.toString()))
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

    // A checkpoint store or Airbyte error other than a 404 is thrown: failing beats risking a second sync.
    private Optional<Job> findCheckpointedJob(RunContext runContext, String rConnectionId) throws Exception {
        // A RunContext not initialized by a worker (runContextFactory.of) has no checkpoint store.
        var checkpoints = runContext.checkpoints();
        var saved = checkpoints == null ? Optional.<String>empty() : checkpoints.get(CHECKPOINT_KEY);
        if (saved.isEmpty()) {
            return Optional.empty();
        }

        long jobId;
        try {
            jobId = Long.parseLong(saved.get());
        } catch (NumberFormatException e) {
            runContext.logger().warn("Ignoring the job checkpoint '{}', it is not a job id", saved.get());
            return Optional.empty();
        }

        JobInfo jobInfo;
        try {
            jobInfo = this.request(runContext, this.jobRequest(runContext, "get", jobId), JobInfo.class).getBody();
        } catch (RuntimeException e) {
            if (isNotFound(e)) {
                runContext.logger().warn("The checkpointed job {} no longer exists in Airbyte, a new sync is triggered", jobId);
                return Optional.empty();
            }
            throw e;
        }

        var job = jobInfo == null ? null : jobInfo.getJob();
        if (job == null || job.getConfigType() != JobConfigType.SYNC || !rConnectionId.equals(job.getConfigId())) {
            return Optional.empty();
        }
        if (job.getStatus() == null || job.getStatus() == JobStatus.FAILED || job.getStatus() == JobStatus.CANCELLED) {
            return Optional.empty();
        }
        return Optional.of(job);
    }

    private static boolean isNotFound(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpClientResponseException responseException
                && responseException.getResponse() != null
                && responseException.getResponse().getStatus().getCode() == 404) {
                return true;
            }
        }
        return false;
    }

    private void track(RunContext runContext, Long jobId) {
        try {
            // A RunContext not initialized by a worker (runContextFactory.of) has no checkpoint store.
            var checkpoints = runContext.checkpoints();
            if (checkpoints != null) {
                checkpoints.set(CHECKPOINT_KEY, jobId.toString());
            }
        } catch (Exception e) {
            runContext.logger().warn("Could not save the job checkpoint, an interrupted task will trigger a new sync: {}", e.getMessage());
        }

        killable.set(() -> this.cancelJob(runContext, jobId));
        // A kill that landed before the job id was known: cancel now, getAndSet keeps it to one cancel.
        if (isKilled.get()) {
            Optional.ofNullable(killable.getAndSet(null)).ifPresent(Runnable::run);
        }
    }

    private void cancelJob(RunContext runContext, Long jobId) {
        try {
            this.requestOnce(runContext, this.jobRequest(runContext, "cancel", jobId), JobInfo.class);
            runContext.logger().info("Cancelled Airbyte job {}", jobId);
        } catch (Exception e) {
            runContext.logger().warn("Could not cancel Airbyte job {}: {}", jobId, e.getMessage());
        }
    }

    private HttpRequest.HttpRequestBuilder jobRequest(RunContext runContext, String action, long jobId) throws IllegalVariableEvaluationException {
        return HttpRequest.builder()
            .uri(URI.create("%s/api/v1/jobs/%s".formatted(this.baseUrl(runContext), action)))
            .method("POST")
            .addHeader("Accept-Encoding", "identity")
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(Map.of("id", jobId))
                    .build()
            );
    }

    @Override
    public void kill() {
        if (isKilled.compareAndSet(false, true)) {
            Optional.ofNullable(killable.getAndSet(null)).ifPresent(Runnable::run);
        }
    }

    private Optional<Long> findActiveSyncJob(RunContext runContext, String connectionId) throws Exception {
        HttpRequest.HttpRequestBuilder listRequest = HttpRequest.builder()
            .uri(URI.create(runContext.render(getUrl()).as(String.class).orElseThrow() + "/api/v1/jobs/list"))
            .method("POST")
            .addHeader("Accept-Encoding", "identity")
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(Map.of(
                        "configTypes", List.of("sync"),
                        "configId", connectionId,
                        "pagination", Map.of("pageSize", 5, "rowOffset", 0)
                    ))
                    .build()
            );

        HttpResponse<JobList> listResponse = this.request(runContext, listRequest, JobList.class);

        return Optional.ofNullable(listResponse.getBody())
            .map(JobList::getJobs)
            .orElseGet(List::of)
            .stream()
            .filter(jobInfo -> jobInfo.getJob() != null
                // Defense-in-depth: the request above already restricts to "sync" via `configTypes`, but a server
                // that ignores/mis-applies that filter must not be able to get a reset/clear job silently adopted.
                && jobInfo.getJob().getConfigType() == JobConfigType.SYNC
                && ACTIVE_JOB_STATUS.contains(jobInfo.getJob().getStatus()))
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
