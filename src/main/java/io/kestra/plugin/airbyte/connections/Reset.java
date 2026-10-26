package io.kestra.plugin.airbyte.connections;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.plugin.airbyte.models.JobInfo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Reset streams of a self-hosted Airbyte connection",
    description = """
        Starts a reset of the selected streams of a self-hosted Airbyte connection and returns the ID of the reset job. At least one stream is required.

        **Warning:** resetting a stream deletes the data this stream generated in the destination and resets its cursors back to their initial state. The next sync of the connection then replicates the stream from scratch.

        The task does not wait for the job to finish. To wait, pass the returned `jobId` to `io.kestra.plugin.airbyte.connections.CheckStatus`.

        This task calls Airbyte's `POST /api/v1/connections/reset/stream` config API. To reset a whole connection on Airbyte Cloud, use `io.kestra.plugin.airbyte.cloud.jobs.Reset`.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Reset one stream of a self-hosted Airbyte connection and wait for the job",
            code = """
                id: airbyte_reset_stream
                namespace: company.team

                tasks:
                  - id: reset
                    type: io.kestra.plugin.airbyte.connections.Reset
                    url: http://localhost:8000
                    username: "{{ secret('AIRBYTE_USERNAME') }}"
                    password: "{{ secret('AIRBYTE_PASSWORD') }}"
                    connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd
                    streams:
                      - streamName: customers
                        streamNamespace: public

                  - id: wait_for_reset
                    type: io.kestra.plugin.airbyte.connections.CheckStatus
                    url: http://localhost:8000
                    username: "{{ secret('AIRBYTE_USERNAME') }}"
                    password: "{{ secret('AIRBYTE_PASSWORD') }}"
                    jobId: "{{ outputs.reset.jobId }}"
                """
        ),
        @Example(
            full = true,
            title = "Reset several streams, with and without a namespace",
            code = """
                id: airbyte_reset_streams
                namespace: company.team

                tasks:
                  - id: reset
                    type: io.kestra.plugin.airbyte.connections.Reset
                    url: http://localhost:8000
                    token: "{{ secret('AIRBYTE_TOKEN') }}"
                    connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd
                    streams:
                      - streamName: customers
                        streamNamespace: public
                      - streamName: orders
                """
        )
    }
)
public class Reset extends AbstractAirbyteConnection implements RunnableTask<Reset.Output> {
    @Schema(
        title = "Connection ID",
        description = "Airbyte connection ID that owns the streams to reset"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> connectionId;

    @Schema(
        title = "Streams to reset",
        description = "Streams to reset, each with a `streamName` and an optional `streamNamespace`. At least one stream is required. Resetting a stream deletes its data in the destination and resets its cursors"
    )
    @NotNull
    @NotEmpty
    @Valid
    @PluginProperty(group = "main")
    private List<StreamSelection> streams;

    @Override
    public Reset.Output run(RunContext runContext) throws Exception {
        var rConnectionId = runContext.render(this.connectionId).as(String.class).orElseThrow();
        var rStreams = renderStreams(runContext);

        var resetRequest = HttpRequest.builder()
            .uri(URI.create(baseUrl(runContext) + "/api/v1/connections/reset/stream"))
            .method("POST")
            .addHeader("Accept-Encoding", "identity")
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(Map.of("connectionId", rConnectionId, "streams", rStreams))
                    .build()
            );

        HttpResponse<JobInfo> response;
        try {
            response = this.request(runContext, resetRequest, JobInfo.class);
        } catch (SyncAlreadyRunningException e) {
            throw new IllegalStateException("Airbyte rejected the reset because a job is already running for connection " + rConnectionId, e);
        } catch (HttpClientException e) {
            throw new RuntimeException("Request failed with error: " + e.getMessage(), e);
        }

        var jobInfo = Optional.ofNullable(response.getBody())
            .filter(body -> body.getJob() != null && body.getJob().getId() != null)
            .orElseThrow(() -> new IllegalStateException("Missing job in the Airbyte reset response"));

        var jobId = jobInfo.getJob().getId();
        runContext.logger().info("Reset of {} stream(s) of connection {} started as job {}", rStreams.size(), rConnectionId, jobId);

        return Output.builder().jobId(jobId).build();
    }

    private List<Map<String, String>> renderStreams(RunContext runContext) throws Exception {
        if (this.streams == null || this.streams.isEmpty()) {
            throw new IllegalArgumentException("At least one stream is required to reset a connection");
        }

        var rendered = new ArrayList<Map<String, String>>(this.streams.size());
        for (var stream : this.streams) {
            var name = stream == null || stream.getStreamName() == null
                ? null
                : runContext.render(stream.getStreamName()).as(String.class).filter(value -> !value.isBlank()).orElse(null);
            if (name == null) {
                throw new IllegalArgumentException("Each stream to reset requires a non-blank `streamName`");
            }

            var entry = new LinkedHashMap<String, String>();
            entry.put("streamName", name);
            if (stream.getStreamNamespace() != null) {
                runContext.render(stream.getStreamNamespace()).as(String.class)
                    .filter(value -> !value.isBlank())
                    .ifPresent(namespace -> entry.put("streamNamespace", namespace));
            }
            rendered.add(entry);
        }
        return rendered;
    }

    @Builder
    @Getter
    public static class StreamSelection {
        @Schema(
            title = "Stream name",
            description = "Name of the stream to reset, as it appears in the source"
        )
        @NotNull
        @PluginProperty(group = "main")
        private Property<String> streamName;

        @Schema(
            title = "Stream namespace",
            description = "Namespace of the stream to reset, such as a database schema. Leave it out for streams without a namespace"
        )
        @PluginProperty(group = "main")
        private Property<String> streamNamespace;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Job ID",
            description = "Airbyte job ID of the reset. Pass it to `io.kestra.plugin.airbyte.connections.CheckStatus` to wait for the job to finish"
        )
        private final Long jobId;
    }
}
