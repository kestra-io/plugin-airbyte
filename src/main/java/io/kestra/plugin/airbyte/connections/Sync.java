package io.kestra.plugin.airbyte.connections;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.plugin.airbyte.models.JobInfo;
import io.kestra.plugin.airbyte.models.JobStatus;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientRequestException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run an Airbyte sync."
)
@Plugin(
    examples = {
        @Example(
            full = true,
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
    private Property<String> connectionId;

    @Schema(
        title = "Wait for the job to end.",
        description = "Allowing capture of job status & logs."
    )
    @Builder.Default
    private Property<Boolean> wait = Property.of(true);

    @Schema(
        title = "The maximum total wait duration."
    )
    @Builder.Default
    Property<Duration> maxDuration = Property.of(Duration.ofMinutes(60));

    @Schema(
        title = "Specify frequency for sync attempt state check API call."
    )
    @Builder.Default
    Property<Duration> pollFrequency = Property.of(Duration.ofSeconds(1));

    @Schema(
        title = "Specify whether task should fail if a sync is already running."
    )
    @Builder.Default
    Property<Boolean> failOnActiveSync = Property.of(true);

    @Override
    public Sync.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        HttpResponse<JobInfo> syncResponse;

        try {
            HttpRequest.HttpRequestBuilder syncRequest = HttpRequest.builder()
                .uri(URI.create(getUrl()+ "/api/v1/connections/sync/"))
                .method("POST")
                .body(HttpRequest.JsonRequestBody.builder()
                    .content(Map.of("connectionId", runContext.render(this.connectionId).as(String.class).orElseThrow()))
                    .build());

            syncResponse = this.request(runContext, syncRequest, JobInfo.class);
        } catch (HttpClientRequestException | HttpClientResponseException e) {
            if (e.getMessage().contains("A sync is already running")) {
                logger.info("This Airbyte sync is already running, Kestra cannot trigger a new execution.");
                if (runContext.render(this.failOnActiveSync).as(Boolean.class).orElseThrow()) {
                    throw e;
                } else {
                    return Output.builder()
                        .alreadyRunning(true)
                        .jobId(null)
                        .build();
                }
            }
            throw e;
        } catch (HttpClientException e) {
            throw new RuntimeException("Request failed with error: " + e.getMessage(), e);
        }

        JobInfo jobInfoRead = Optional.ofNullable(syncResponse.getBody())
            .orElseThrow(() -> new IllegalStateException("Missing body on trigger"));

        logger.info("Job status {} with response: {}", syncResponse.getStatus(), jobInfoRead);
        Long jobId = jobInfoRead.getJob().getId();

        if (!runContext.render(this.wait).as(Boolean.class).orElseThrow()) {
            return Output.builder()
                .alreadyRunning(false)
                .jobId(jobId)
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
            .jobId(Property.of(jobId.toString()))
            .build();

        checkStatus.run(runContext);

        return Output.builder()
            .jobId(jobId)
            .alreadyRunning(false)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The job ID created.")
        private final Long jobId;

        @Schema(title = "Whether a sync was already running.")
        private final Boolean alreadyRunning;
    }
}
