package io.kestra.plugin.airbyte;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.airbyte.models.Connection;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.Destination;
import io.kestra.plugin.airbyte.models.DestinationLocation;
import io.kestra.plugin.airbyte.models.Source;

/**
 * Reads the connection, source and destination through the documented config API. The snapshot models only declare non-secret
 * fields, so connector configurations cannot reach the snapshot. Only the destination location keys are read from the destination
 * configuration, into a field that is never serialized.
 */
public final class ConnectionSnapshots {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    private ConnectionSnapshots() {
    }

    public static Optional<ConnectionSnapshot> fetch(AbstractAirbyteConnection task, RunContext runContext, String connectionId) {
        try {
            var baseUrl = task.baseUrl(runContext);
            var connection = post(task, runContext, baseUrl + "/api/v1/connections/get", Map.of("connectionId", connectionId), Connection.class);
            var source = post(task, runContext, baseUrl + "/api/v1/sources/get", Map.of("sourceId", connection.getSourceId()), Source.class);
            var destinationResponse = post(task, runContext, baseUrl + "/api/v1/destinations/get", Map.of("destinationId", connection.getDestinationId()), Map.class);
            var destination = MAPPER.convertValue(destinationResponse, Destination.class);

            return Optional.of(
                ConnectionSnapshot.builder().connection(connection).source(source).destination(destination)
                    .destinationLocation(location(runContext, destination, destinationResponse))
                    .build()
            );
        } catch (Exception e) {
            runContext.logger().warn(
                "Unable to read the configuration of connection {}, continuing without connection snapshot, job link and assets: {}. Check that the credentials can read connections, sources and destinations",
                connectionId, e.getMessage()
            );
            return Optional.empty();
        }
    }

    private static DestinationLocation location(RunContext runContext, Destination destination, Map<?, ?> destinationResponse) {
        var location = DestinationLocations.parse(destination.getDestinationName(), destinationResponse.get("connectionConfiguration"));
        if (location.isEmpty()) {
            runContext.logger().debug("No destination location resolved for destination {}, asset ids fall back to the Airbyte destination ID", destination.getDestinationId());
        }
        return location.orElse(null);
    }

    public static URI store(RunContext runContext, ConnectionSnapshot snapshot) throws IOException {
        var file = runContext.workingDir().createTempFile(".json").toFile();
        JacksonMapper.ofJson().writeValue(file, snapshot);
        return runContext.storage().putFile(file);
    }

    public static String jobUrl(String webappBaseUrl, String workspaceId, String connectionId, Long jobId, Integer attemptNumber) {
        if (webappBaseUrl == null || workspaceId == null || connectionId == null || jobId == null) {
            return null;
        }

        var url = webappBaseUrl + "/workspaces/" + encode(workspaceId) + "/connections/" + encode(connectionId)
            + "/timeline?openLogs=true&jobId=" + jobId;
        return attemptNumber == null ? url : url + "&attemptNumber=" + attemptNumber;
    }

    private static <T> T post(AbstractAirbyteConnection task, RunContext runContext, String url, Map<String, Object> body, Class<T> type) throws Exception {
        var request = HttpRequest.builder()
            .uri(URI.create(url))
            .method("POST")
            .addHeader("Accept-Encoding", "identity")
            .body(HttpRequest.JsonRequestBody.builder().content(body).build());

        return Optional.ofNullable(task.requestOnce(runContext, request, type).getBody())
            .orElseThrow(() -> new IllegalStateException("Empty response from " + url));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
