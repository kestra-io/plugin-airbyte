package io.kestra.plugin.airbyte.endpoints;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.plugins.endpoint.PluginEndpoint;
import io.kestra.core.plugins.endpoint.PluginEndpointContext;
import io.kestra.core.plugins.endpoint.PluginEndpointResponse;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionStream;

/**
 * Returns the streams of the {@code connectionSnapshot} output file with their configuration. The optional {@code name}
 * query parameter keeps the streams whose name contains it, ignoring case. The snapshot models only declare non-secret fields.
 */
@Plugin
public class Snapshot implements PluginEndpoint {
    @Override
    public String name() {
        return "snapshot";
    }

    @Override
    public PluginEndpointResponse handle(PluginEndpointContext context) {
        var filter = context.param("name");
        var needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);

        var streams = StoredOutput.open(context, "connectionSnapshot")
            .map(Snapshot::read)
            .map(snapshot -> streams(snapshot, needle))
            .orElseGet(List::of);

        return PluginEndpointResponse.of(new Streams(streams.size(), streams));
    }

    private static List<ConnectionStream> streams(ConnectionSnapshot snapshot, String needle) {
        var catalog = snapshot == null || snapshot.getConnection() == null ? null : snapshot.getConnection().getSyncCatalog();
        if (catalog == null || catalog.getStreams() == null) {
            return List.of();
        }

        return catalog.getStreams().stream()
            .filter(Objects::nonNull)
            .filter(stream -> needle.isEmpty() || matches(stream, needle))
            .toList();
    }

    private static boolean matches(ConnectionStream stream, String needle) {
        return stream.getStream() != null
            && stream.getStream().getName() != null
            && stream.getStream().getName().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static ConnectionSnapshot read(InputStream input) {
        try (input) {
            return JacksonMapper.ofJson().readValue(input, ConnectionSnapshot.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record Streams(int total, List<ConnectionStream> streams) {
    }
}
