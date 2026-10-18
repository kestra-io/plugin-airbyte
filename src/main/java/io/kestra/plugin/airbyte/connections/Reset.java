package io.kestra.plugin.airbyte.connections;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kestra.core.http.HttpRequest;
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
import lombok.extern.jackson.Jacksonized;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Reset selected Airbyte connection streams",
    description = "Resets explicitly selected streams on a self-hosted Airbyte instance using `/api/v1/connections/reset/stream`. This deletes the selected streams' destination data and resets their cursors. Requires an Airbyte version exposing this config API endpoint. By default, waits for the reset job to finish"
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: airbyte_reset_stream
                namespace: company.team

                tasks:
                  - id: reset
                    type: io.kestra.plugin.airbyte.connections.Reset
                    url: http://localhost:8000
                    connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd
                    streams:
                      - streamName: customers
                        streamNamespace: public
                """
        )
    }
)
public class Reset extends AbstractAirbyteConnection implements RunnableTask<Reset.Output> {
    @Schema(title = "Connection ID", description = "Airbyte connection containing the streams to reset")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> connectionId;

    @Schema(
        title = "Streams to reset",
        description = "Explicit stream names and optional namespaces. At least one stream is required. Streams outside this list are not reset"
    )
    @Valid
    @NotEmpty
    @PluginProperty(group = "main")
    private List<Stream> streams;

    @Schema(title = "Wait for completion", description = "Wait for the reset job to finish. Defaults to true")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> wait = Property.ofValue(true);

    @Schema(title = "Maximum wait duration", description = "Maximum time to wait for the reset job. Defaults to 60 minutes")
    @Builder.Default
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(60));

    @Schema(title = "Poll frequency", description = "Interval between job status checks. Defaults to 1 second")
    @Builder.Default
    private Property<Duration> pollFrequency = Property.ofValue(Duration.ofSeconds(1));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rStreams = new ArrayList<Map<String, String>>();
        if (streams == null || streams.isEmpty()) {
            throw new IllegalArgumentException("No streams selected for reset; provide at least one stream with streamName");
        }
        for (var stream : streams) {
            var name = stream == null ? null : runContext.render(stream.streamName).as(String.class).orElse(null);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Missing streamName for reset; provide a nonempty name for every selected stream");
            }
            var descriptor = new LinkedHashMap<String, String>();
            descriptor.put("streamName", name);
            var namespace = runContext.render(stream.streamNamespace).as(String.class).orElse(null);
            if (namespace != null) {
                descriptor.put("streamNamespace", namespace);
            }
            rStreams.add(descriptor);
        }

        var rUrl = runContext.render(getUrl()).as(String.class).orElseThrow();
        var resetRequest = HttpRequest.builder()
            .uri(URI.create(rUrl + "/api/v1/connections/reset/stream"))
            .method("POST")
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(
                        Map.of(
                            "connectionId", runContext.render(connectionId).as(String.class).orElseThrow(),
                            "streams", rStreams
                        )
                    )
                    .build()
            );
        var response = this.request(runContext, resetRequest, JobInfo.class);
        var jobInfo = response.getBody();
        if (jobInfo == null || jobInfo.getJob() == null || jobInfo.getJob().getId() == null) {
            throw new IllegalStateException("Airbyte returned no reset job ID; check that the instance supports stream resets");
        }
        var jobId = jobInfo.getJob().getId();
        runContext.logger().info("Started stream reset job {}", jobId);

        if (runContext.render(wait).as(Boolean.class).orElseThrow()) {
            CheckStatus.builder()
                .url(Property.ofValue(rUrl))
                .username(getUsername())
                .password(getPassword())
                .token(getToken())
                .applicationCredentials(getApplicationCredentials())
                .options(options)
                .pollFrequency(pollFrequency)
                .maxDuration(maxDuration)
                .jobId(Property.ofValue(jobId.toString()))
                .build()
                .run(runContext);
        }

        return Output.builder().jobId(jobId).build();
    }

    @Builder
    @Getter
    @Jacksonized
    public static class Stream {
        @Schema(title = "Stream name", description = "Exact name of the stream to reset")
        @NotNull
        private Property<String> streamName;

        @Schema(title = "Stream namespace", description = "Namespace of the stream, if it has one. Omit for a stream without a namespace")
        private Property<String> streamNamespace;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Job ID", description = "Airbyte job ID for the stream reset")
        private final Long jobId;
    }
}
