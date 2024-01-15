package io.kestra.plugin.airbyte.connections;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.plugin.airbyte.models.JobInfo;
import io.kestra.plugin.airbyte.models.JobStatus;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.uri.UriTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
                "url: http://localhost:8080",
                "connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd",
            }
        )
    }
)
public class Sync extends AbstractAirbyteConnection implements RunnableTask<Sync.Output> {
    private static final List<JobStatus> ENDED_JOB_STATUS = List.of(
        JobStatus.FAILED,
        JobStatus.CANCELLED,
        JobStatus.SUCCEEDED
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
    private Boolean wait = true;

    @Schema(
        title = "The maximum total wait duration."
    )
    @PluginProperty
    @Builder.Default
    Duration maxDuration = Duration.ofMinutes(60);

    @Schema(
        title = "Specify frequency for sync attempt state check API call."
    )
    @PluginProperty
    @Builder.Default
    Duration pollFrequency = Duration.ofSeconds(1);

    @Schema(
        title = "Specify whether task should fail if a sync is already running."
    )
    @PluginProperty
    @Builder.Default
    Boolean failOnActiveSync = true;

    @Override
    public Sync.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        HttpResponse<JobInfo> syncResponse;

        // create sync
        try {
            syncResponse = this.request(
                    runContext,
                    HttpRequest
                            .create(
                                    HttpMethod.POST,
                                    UriTemplate
                                            .of("/api/v1/connections/sync/")
                                            .toString()
                            )
                            .body(Map.of("connectionId", runContext.render(this.connectionId))),
                    Argument.of(JobInfo.class)
            );
        } catch(SyncAlreadyRunningException e) {
            logger.info("This Airbyte sync is already running, Kestra cannot trigger a new execution.");
            if (this.failOnActiveSync) {
                throw e;
            } else {
                return Output.builder()
                        .alreadyRunning(true)
                        .jobId(null)
                        .build();
            }
        }

        JobInfo jobInfoRead = syncResponse.getBody().orElseThrow(() -> new IllegalStateException("Missing body on trigger"));

        logger.info("Job status {} with response: {}", syncResponse.getStatus(), jobInfoRead);
        Long jobId = jobInfoRead.getJob().getId();

        if (!this.wait) {
            return Output.builder()
                .alreadyRunning(false)
                .jobId(jobId)
                .build();
        }

        CheckStatus checkStatus = CheckStatus.builder()
                .url(getUrl())
                .username(getUsername())
                .password(getPassword())
                .pollFrequency(pollFrequency)
                .maxDuration(maxDuration)
                .jobId(jobId.toString())
                .build();

        CheckStatus.Output runOutput = checkStatus.run(runContext);

        return Output.builder()
            .jobId(jobId)
            .alreadyRunning(false)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The job ID created."
        )
        private final Long jobId;

        @Schema(
            title = "Whether a sync was already running."
        )
        private final Boolean alreadyRunning;
    }
}
