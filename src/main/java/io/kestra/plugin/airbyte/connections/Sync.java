package io.kestra.plugin.airbyte.connections;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.plugin.airbyte.models.JobConfigType;
import io.kestra.plugin.airbyte.models.JobInfo;
import io.kestra.plugin.airbyte.models.JobList;
import io.kestra.plugin.airbyte.models.JobStatus;

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
    description = "Starts a sync for an Airbyte connection and, by default, waits for the job to finish. Polling runs every second for up to 60 minutes unless you change `pollFrequency` or `maxDuration`. If a sync is already running for the connection, the task adopts it and polls it to completion instead of failing or blindly re-triggering — see `onActiveSync`"
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
        )
    }
)
public class Sync extends AbstractAirbyteConnection implements RunnableTask<Sync.Output> {
    private static final List<JobStatus> ACTIVE_JOB_STATUS = List.of(
        JobStatus.PENDING,
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
        title = "Behavior when a sync is already running",
        description = """
            Controls what happens when a sync is already running for this connection:
            - `ADOPT` (default): attach to the in-flight job and poll it to completion instead of triggering a new sync.
            - `FAIL`: fail the task immediately.
            - `SKIP`: succeed immediately without waiting, with `alreadyRunning` set to `true` and no `jobId`.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<OnActiveSync> onActiveSync = Property.ofValue(OnActiveSync.ADOPT);

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
                    throw new IllegalStateException(
                        "A non-sync job (reset/clear) is running for connection " + rConnectionId + "; retry once it completes",
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

        if (!runContext.render(this.wait).as(Boolean.class).orElseThrow()) {
            return Output.builder()
                .jobId(jobId)
                .alreadyRunning(adopted)
                .adopted(adopted)
                .build();
        }

        CheckStatus checkStatus = CheckStatus.builder()
            .url(getUrl())
            .username(getUsername())
            .password(getPassword())
            .token(getToken())
            .applicationCredentials(getApplicationCredentials())
            .pollFrequency(pollFrequency)
            .maxDuration(maxDuration)
            .jobId(Property.ofValue(jobId.toString()))
            .build();

        checkStatus.run(runContext);

        return Output.builder()
            .jobId(jobId)
            .alreadyRunning(adopted)
            .adopted(adopted)
            .build();
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
            case SKIP -> Output.builder()
                .alreadyRunning(true)
                .adopted(false)
                .jobId(null)
                .build();
            case ADOPT -> null;
        };
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
    }
}
