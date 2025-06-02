package io.kestra.plugin.airbyte.cloud.jobs;

import com.airbyte.api.Airbyte;
import com.airbyte.api.models.operations.CreateJobResponse;
import com.airbyte.api.models.operations.GetJobRequest;
import com.airbyte.api.models.operations.GetJobResponse;
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
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.airbyte.cloud.AbstractAirbyteCloud;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import static io.kestra.core.utils.Rethrow.throwSupplier;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a sync on a connection."
)
@Plugin(
    examples = {
        @Example(
            code = {
                "token: <token>",
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
    private static final List<JobStatusEnum> ENDED_STATUS = List.of(
        JobStatusEnum.INCOMPLETE,
        JobStatusEnum.FAILED,
        JobStatusEnum.CANCELLED,
        JobStatusEnum.SUCCEEDED
    );

    @Schema(
        title = "The connection ID to sync."
    )
    @NotNull
    private Property<String> connectionId;

    @Schema(
        title = "Wait for the job to end.",
        description = "Allowing capture of job status & logs."
    )
    @Builder.Default
    Property<Boolean> wait = Property.ofValue(true);

    @Schema(
        title = "The maximum total wait duration."
    )
    @Builder.Default
    Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(60));

    @Schema(
        title = "Specify frequency for state check API call."
    )
    @Builder.Default
    Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(1));

    abstract protected JobTypeEnum syncType();

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Airbyte client = this.client(runContext);

        JobCreateRequest createJobRequest = new JobCreateRequest(
            runContext.render(this.connectionId).as(String.class).orElse(null),
            this.syncType()
        );

        CreateJobResponse createJobResponse = client.jobs().createJob(createJobRequest);
        this.validate(createJobResponse.rawResponse());

        Job createJob = Job.of(createJobResponse.jobResponse().orElseThrow());

        logger.info("Job id {} with response: {}", createJob.jobId, createJob);

        if (!runContext.render(this.wait).as(Boolean.class).orElseThrow()) {
            return AbstractTrigger.Output.builder()
                .job(createJob)
                .build();
        }

        GetJobRequest getJobRequest = new GetJobRequest(createJob.jobId);

        // wait for end
        JobResponse finalJobResponse = Await.until(
            throwSupplier(() -> {
                GetJobResponse job = client.jobs().getJob(getJobRequest);
                this.validate(job.rawResponse());

                // ended
                if (ENDED_STATUS.contains(job.jobResponse().orElseThrow().status())) {
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

        return Output.builder()
            .job(Job.of(finalJobResponse))
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The job created."
        )
        private final Job job;
    }

    @Value
    @Builder
    public static class Job {
        public Long jobId;
        public ZonedDateTime startTime;
        public ZonedDateTime lastUpdatedAt;
        public JobTypeEnum jobType;
        public JobStatusEnum status;
        public Duration duration;
        public Long bytesSynced;
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
