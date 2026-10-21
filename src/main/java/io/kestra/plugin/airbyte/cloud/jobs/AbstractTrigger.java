package io.kestra.plugin.airbyte.cloud.jobs;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import org.slf4j.Logger;

import com.airbyte.api.Airbyte;
import com.airbyte.api.models.operations.CreateJobResponse;
import com.airbyte.api.models.operations.GetConnectionRequest;
import com.airbyte.api.models.operations.GetDestinationRequest;
import com.airbyte.api.models.operations.GetJobRequest;
import com.airbyte.api.models.operations.GetJobResponse;
import com.airbyte.api.models.operations.GetSourceRequest;
import com.airbyte.api.models.shared.ConnectionResponse;
import com.airbyte.api.models.shared.JobCreateRequest;
import com.airbyte.api.models.shared.JobResponse;
import com.airbyte.api.models.shared.JobStatusEnum;
import com.airbyte.api.models.shared.JobTypeEnum;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.RunnableTaskException;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.airbyte.ConnectionSnapshots;
import io.kestra.plugin.airbyte.StreamAssets;
import io.kestra.plugin.airbyte.cloud.AbstractAirbyteCloud;
import io.kestra.plugin.airbyte.cloud.CloudSnapshots;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionSummary;

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
    title = "Run an Airbyte Cloud job",
    description = "Creates an Airbyte Cloud job for a connection and, by default, waits for it to finish. Polling runs every second for up to 60 minutes unless you change `pollFrequency` or `maxDuration`"
)
@Plugin(
    examples = {
        @Example(
            code = {
                "token: \"{{ secret('AIRBYTE_TOKEN') }}\"",
                "connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd",
            }
        )
    },
    metrics = {
        @Metric(name = "bytes_synced", type = Counter.TYPE),
        @Metric(name = "rows_synced", type = Counter.TYPE),
        @Metric(name = "duration", type = Timer.TYPE)
    }
)
public abstract class AbstractTrigger extends AbstractAirbyteCloud implements RunnableTask<AbstractTrigger.Output> {
    // INCOMPLETE is not final: Airbyte reports it between attempts, then the job runs again
    private static final Set<JobStatusEnum> ENDED_STATUS = EnumSet.of(
        JobStatusEnum.FAILED,
        JobStatusEnum.CANCELLED,
        JobStatusEnum.SUCCEEDED
    );

    @Schema(
        title = "Connection ID",
        description = "Airbyte Cloud connection ID for the job"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> connectionId;

    @Schema(
        title = "Wait for completion",
        description = "If `true`, wait for the Airbyte Cloud job to reach a terminal state before the task completes. Defaults to `true`"
    )
    @Builder.Default
    Property<Boolean> wait = Property.ofValue(true);

    @Schema(
        title = "Maximum wait duration",
        description = "Maximum total time to wait for the job to finish, including Airbyte's automatic retry attempts. Defaults to 60 minutes"
    )
    @Builder.Default
    Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(60));

    @Schema(
        title = "Poll frequency",
        description = "Interval between Airbyte Cloud job status checks. Defaults to 1 second"
    )
    @Builder.Default
    Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(1));

    abstract protected JobTypeEnum syncType();

    /**
     * Whether a successful job lands data worth declaring as assets. A reset clears data, so it declares none.
     */
    protected boolean emitsAssets() {
        return false;
    }

    static boolean isEnded(JobStatusEnum status) {
        return ENDED_STATUS.contains(status);
    }

    static boolean isFailure(JobStatusEnum status) {
        return status == JobStatusEnum.FAILED || status == JobStatusEnum.CANCELLED;
    }

    static String failureMessage(Long jobId, JobStatusEnum status, String jobUrl) {
        return "Airbyte Cloud job " + jobId + " ended " + status.value() + ". "
            + (jobUrl == null ? "Check the job logs in Airbyte Cloud" : "See " + jobUrl);
    }

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Airbyte client = this.client(runContext);

        var rConnectionId = runContext.render(this.connectionId).as(String.class).orElse(null);
        var createJobRequest = new JobCreateRequest(rConnectionId, this.syncType());

        CreateJobResponse createJobResponse = client.jobs().createJob(createJobRequest);
        this.validate(createJobResponse.rawResponse());

        Job createJob = Job.of(createJobResponse.jobResponse().orElseThrow());

        logger.info("Job id {} with response: {}", createJob.jobId, createJob);

        var snapshot = fetchSnapshot(runContext, rConnectionId);

        if (!runContext.render(this.wait).as(Boolean.class).orElseThrow()) {
            return outputOf(runContext, createJob, snapshot);
        }

        GetJobRequest getJobRequest = new GetJobRequest(createJob.jobId);

        // wait for end
        JobResponse finalJobResponse = Await.until(
            throwSupplier(() ->
            {
                GetJobResponse job = client.jobs().getJob(getJobRequest);
                this.validate(job.rawResponse());

                // ended
                if (isEnded(job.jobResponse().orElseThrow().status())) {
                    return job.jobResponse().orElseThrow();
                }

                return null;
            }),
            runContext.render(this.pollFrequency).as(Duration.class).orElseThrow(),
            runContext.render(this.maxDuration).as(Duration.class).orElseThrow()
        );

        finalJobResponse.bytesSynced()
            .ifPresent(bytesSynced -> runContext.metric(Counter.of("bytes_synced", bytesSynced)));

        finalJobResponse.rowsSynced()
            .ifPresent(rowsSynced -> runContext.metric(Counter.of("rows_synced", rowsSynced)));

        finalJobResponse.duration()
            .ifPresent(duration -> runContext.metric(Timer.of("duration", Duration.parse(duration))));

        var finalJob = Job.of(finalJobResponse);
        if (emitsAssets() && snapshot.isPresent() && finalJob.status == JobStatusEnum.SUCCEEDED) {
            StreamAssets.emit(
                runContext,
                StreamAssets.plan(snapshot.get(), Map.of(), finalJob.jobId, finalJob.status.value(), true)
            );
        }

        var output = outputOf(runContext, finalJob, snapshot);
        if (isFailure(finalJob.status)) {
            throw new RunnableTaskException(failureMessage(finalJob.jobId, finalJob.status, output.getJobUrl()), output);
        }
        return output;
    }

    private Optional<ConnectionSnapshot> fetchSnapshot(RunContext runContext, String rConnectionId) {
        Airbyte client;
        ConnectionResponse connectionResponse;
        try {
            client = this.snapshotClient(runContext);
            var connection = client.connections().getConnection(new GetConnectionRequest(rConnectionId));
            this.validate(connection.rawResponse());
            connectionResponse = connection.connectionResponse().orElseThrow(() -> new IllegalStateException("Empty connection response"));
        } catch (Exception e) {
            runContext.logger().warn(
                "Unable to read connection {} from Airbyte Cloud, continuing without connection snapshot, job link and assets: {}. Check that the credentials can read connections",
                rConnectionId, e.getMessage()
            );
            return Optional.empty();
        }

        // The SDK only binds the configuration of connectors it knows, so a newer connector must not cost us the connection itself.
        var source = readDetails(
            runContext, "source", connectionResponse.sourceId(), () ->
            {
                var response = client.sources().getSource(new GetSourceRequest(connectionResponse.sourceId()));
                this.validate(response.rawResponse());
                return response.sourceResponse().orElseThrow(() -> new IllegalStateException("Empty source response"));
            }
        );
        var destination = readDetails(
            runContext, "destination", connectionResponse.destinationId(), () ->
            {
                var response = client.destinations().getDestination(new GetDestinationRequest(connectionResponse.destinationId()));
                this.validate(response.rawResponse());
                return response.destinationResponse().orElseThrow(() -> new IllegalStateException("Empty destination response"));
            }
        );

        return Optional.of(CloudSnapshots.toSnapshot(connectionResponse, source, destination));
    }

    private <T> T readDetails(RunContext runContext, String kind, String id, Callable<T> read) {
        try {
            return read.call();
        } catch (Exception e) {
            runContext.logger().warn(
                "Could not read the Airbyte Cloud {} {} (the SDK may not recognise this connector's configuration); continuing without {} details: {}",
                kind, id, kind, e.getMessage()
            );
            return null;
        }
    }

    private Output outputOf(RunContext runContext, Job job, Optional<ConnectionSnapshot> snapshot) {
        var builder = Output.builder().job(job);

        snapshot.ifPresent(value ->
        {
            try {
                builder.connectionSnapshot(ConnectionSnapshots.store(runContext, value));
            } catch (Exception e) {
                runContext.logger().warn("Unable to store the connection snapshot: {}", e.getMessage());
            }
            builder
                .connection(ConnectionSummary.from(value))
                .jobUrl(
                    ConnectionSnapshots.jobUrl(
                        CloudSnapshots.WEBAPP_URL, value.resolvedWorkspaceId(), value.getConnection().getConnectionId(), job.jobId, null
                    )
                );
        });

        return builder.build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Job details",
            description = "Airbyte Cloud job returned by the task"
        )
        private final Job job;

        @Schema(
            title = "Connection",
            description = "Summary of the connection, source and destination of the job. `null` when their configuration could not be read"
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
            description = "Link to the job in the Airbyte Cloud web app. `null` when the workspace could not be resolved"
        )
        private final String jobUrl;
    }

    @Value
    @Builder
    public static class Job {
        @Schema(title = "Job ID", description = "Airbyte Cloud job ID")
        public Long jobId;

        @Schema(title = "Start time", description = "Timestamp when the job started")
        public ZonedDateTime startTime;

        @Schema(title = "Last updated at", description = "Timestamp when the job was last updated")
        public ZonedDateTime lastUpdatedAt;

        @Schema(title = "Job type", description = "Type of job (e.g. SYNC or RESET)")
        public JobTypeEnum jobType;

        @Schema(title = "Status", description = "Job status. Terminal when `wait` is enabled")
        public JobStatusEnum status;

        @Schema(title = "Duration", description = "Total duration of the job")
        public Duration duration;

        @Schema(title = "Bytes synced", description = "Number of bytes synced by the job")
        public Long bytesSynced;

        @Schema(title = "Rows synced", description = "Number of rows synced by the job")
        public Long rowsSynced;

        public static Job of(JobResponse jobResponse) {
            return Job.builder()
                .jobId(jobResponse.jobId())
                .startTime(ZonedDateTime.parse(jobResponse.startTime()))
                .lastUpdatedAt(jobResponse.lastUpdatedAt().map(ZonedDateTime::parse).orElse(null))
                .jobType(jobResponse.jobType())
                .status(jobResponse.status())
                .duration(jobResponse.duration().map(Duration::parse).orElse(null))
                .bytesSynced(jobResponse.bytesSynced().orElse(null))
                .rowsSynced(jobResponse.rowsSynced().orElse(null))
                .build();
        }
    }
}
