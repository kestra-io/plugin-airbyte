package io.kestra.plugin.airbyte;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import io.kestra.core.models.assets.Asset;
import io.kestra.core.models.assets.AssetIdentifier;
import io.kestra.core.models.assets.Custom;
import io.kestra.core.models.assets.External;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.airbyte.models.Connection;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionStream;
import io.kestra.plugin.airbyte.models.StreamDescriptor;

/**
 * Lineage assets of a job: one {@code Table} per replicated stream, fed by the matching source stream.
 */
public final class StreamAssets {
    public static final String TABLE_ASSET_TYPE = "io.kestra.plugin.ee.assets.Table";

    private static final String SOURCE_NAMESPACE_PLACEHOLDER = "${SOURCE_NAMESPACE}";

    private StreamAssets() {
    }

    public record Committed(Long records, Long bytes) {
    }

    public record Planned(AssetIdentifier input, Custom output) {
    }

    /**
     * @param committed per-stream committed counts of the job, empty when Airbyte reports none
     * @param jobSucceeded whether the job ended {@code SUCCEEDED}; without per-stream counts this is the only signal that data landed
     */
    public static List<Planned> plan(
        ConnectionSnapshot snapshot,
        Map<StreamDescriptor, Committed> committed,
        Long jobId,
        String jobStatus,
        boolean jobSucceeded
    ) {
        var connection = snapshot.getConnection();
        if (connection.getDestinationId() == null || connection.getSyncCatalog() == null || connection.getSyncCatalog().getStreams() == null) {
            return List.of();
        }

        var planned = new ArrayList<Planned>();
        for (var connectionStream : connection.getSyncCatalog().getStreams()) {
            if (connectionStream.getStream() == null || connectionStream.getStream().getName() == null
                || connectionStream.getConfig() == null || !Boolean.TRUE.equals(connectionStream.getConfig().getSelected())) {
                continue;
            }

            var stream = connectionStream.getStream();
            var counts = committed.get(stream);
            if (committed.isEmpty() ? !jobSucceeded : counts == null || counts.records() == null || counts.records() <= 0) {
                continue;
            }

            planned.add(new Planned(sourceIdentifier(connection, stream), table(snapshot, connectionStream, counts, jobId, jobStatus)));
        }
        return planned;
    }

    public static void emit(RunContext runContext, List<Planned> planned) {
        for (var asset : planned) {
            try {
                runContext.assets().emit(new AssetEmit(List.of(asset.input()), List.<Asset> of(asset.output())));
                runContext.logger().debug("Emitted asset '{}'", asset.output().getId());
            } catch (UnsupportedOperationException e) {
                runContext.logger().debug("Asset emission is not supported in this edition, skipping");
                return;
            } catch (Exception e) {
                runContext.logger().warn("Failed to emit asset '{}': {}", asset.output().getId(), e.getMessage());
            }
        }
    }

    static String namespace(Connection connection, StreamDescriptor stream) {
        var definition = connection.getNamespaceDefinition() == null ? "" : connection.getNamespaceDefinition().replace("_", "").toLowerCase();
        return switch (definition) {
            case "source" -> blankToNull(stream.getNamespace());
            case "customformat" -> customNamespace(connection.getNamespaceFormat(), stream.getNamespace());
            default -> null;
        };
    }

    static String tableName(Connection connection, ConnectionStream stream) {
        var explicit = stream.getConfig().getDestinationObjectName();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return (connection.getPrefix() == null ? "" : connection.getPrefix()) + stream.getStream().getName();
    }

    static String assetId(ConnectionSnapshot snapshot, ConnectionStream stream) {
        var connection = snapshot.getConnection();
        return join(connection.getDestinationId(), namespace(connection, stream.getStream()), tableName(connection, stream));
    }

    private static Custom table(ConnectionSnapshot snapshot, ConnectionStream stream, Committed counts, Long jobId, String jobStatus) {
        var connection = snapshot.getConnection();
        var config = stream.getConfig();
        var table = tableName(connection, stream);

        var metadata = new LinkedHashMap<String, Object>();
        put(metadata, "system", snapshot.getDestination() == null ? null : snapshot.getDestination().getDestinationName());
        put(metadata, "destinationName", snapshot.getDestination() == null ? null : snapshot.getDestination().getName());
        put(metadata, "connectionId", connection.getConnectionId());
        put(metadata, "stream", stream.getStream().getName());
        put(metadata, "syncMode", config.getSyncMode());
        put(metadata, "destinationSyncMode", config.getDestinationSyncMode());
        put(metadata, "cursorField", config.getCursorField());
        put(metadata, "primaryKey", config.getPrimaryKey());
        put(metadata, "lastJobId", jobId);
        put(metadata, "lastJobStatus", jobStatus);
        if (counts != null) {
            put(metadata, "recordsCommitted", counts.records());
            put(metadata, "bytesCommitted", counts.bytes());
        }

        return Custom.builder()
            .id(assetId(snapshot, stream))
            .type(TABLE_ASSET_TYPE)
            .displayName(table)
            .metadata(metadata)
            .build();
    }

    private static AssetIdentifier sourceIdentifier(Connection connection, StreamDescriptor stream) {
        return new AssetIdentifier(null, null, join(connection.getSourceId(), blankToNull(stream.getNamespace()), stream.getName()), External.ASSET_TYPE);
    }

    private static String customNamespace(String format, String sourceNamespace) {
        if (format == null || format.isBlank()) {
            return null;
        }
        if (!format.contains(SOURCE_NAMESPACE_PLACEHOLDER)) {
            return format;
        }
        var resolved = blankToNull(sourceNamespace);
        return resolved == null ? null : format.replace(SOURCE_NAMESPACE_PLACEHOLDER, resolved);
    }

    private static String join(String... segments) {
        return String.join(".", Stream.of(segments).filter(Objects::nonNull).toList());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
