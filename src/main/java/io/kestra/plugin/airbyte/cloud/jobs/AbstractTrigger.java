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
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.airbyte.cloud.AbstractAirbyteCloud;
import io.swagger.v3.oas.annotations.media.Schema;
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
    @PluginProperty(dynamic = true)
    private String connectionId;

    @Schema(
        title = "Wait for the end of the job.",
        description = "Allowing to capture job status & logs."
    )
    @PluginProperty
    @Builder.Default
    Boolean wait = true;

    @Schema(
        title = "The maximum total wait duration."
    )
    @PluginProperty
    @Builder.Default
    Duration maxDuration = Duration.ofMinutes(60);

    @Schema(
            title = "Specify frequency for state check API call."
    )
    @PluginProperty
    @Builder.Default
    Duration pollFrequency = Duration.ofSeconds(1);

    abstract protected JobTypeEnum syncType();

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Airbyte client = this.client(runContext);

        JobCreateRequest createJobRequest = new JobCreateRequest(
            runContext.render(this.connectionId),
            this.syncType()
        );

        CreateJobResponse createJobResponse = client.jobs.createJob(createJobRequest);
        this.validate(createJobResponse.rawResponse);

        Job createJob = Job.of(createJobResponse.jobResponse);

        logger.info("Job id {} with response: {}", createJobResponse.jobResponse.jobId, createJob);

        if (!this.wait) {
            return AbstractTrigger.Output.builder()
                .job(createJob)
                .build();
        }

        GetJobRequest getJobRequest = new GetJobRequest(createJobResponse.jobResponse.jobId);

        // wait for end
        JobResponse finalJobResponse = Await.until(
            throwSupplier(() -> {
                GetJobResponse job = client.jobs.getJob(getJobRequest);
                this.validate(job.rawResponse);

                // ended
                if (ENDED_STATUS.contains(job.jobResponse.status)) {
                    return job.jobResponse;
                }

                return null;
            }),
            this.pollFrequency,
            this.maxDuration
        );

        if (finalJobResponse.bytesSynced != null) {
            runContext.metric(Counter.of("bytes_synced", finalJobResponse.bytesSynced));
        }

        if (finalJobResponse.rowsSynced != null) {
            runContext.metric(Counter.of("rows_synced", finalJobResponse.rowsSynced));
        }


        if (finalJobResponse.duration != null) {
            runContext.metric(Timer.of("duration", Duration.parse(finalJobResponse.duration)));
        }

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
                .jobId(jobResponse.jobId)
                .startTime(ZonedDateTime.parse(jobResponse.startTime))
                .lastUpdatedAt(jobResponse.lastUpdatedAt == null ? null : ZonedDateTime.parse(jobResponse.lastUpdatedAt))
                .jobType(jobResponse.jobType)
                .status(jobResponse.status)
                .duration(jobResponse.duration == null ? null : Duration.parse(jobResponse.duration))
                .bytesSynced(jobResponse.bytesSynced)
                .rowsSynced(jobResponse.rowsSynced)
                .build();
        }
    }
}
