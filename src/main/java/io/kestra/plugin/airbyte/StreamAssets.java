package io.kestra.plugin.airbyte;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.kestra.core.models.assets.Asset;
import io.kestra.core.models.assets.AssetIdentifier;
import io.kestra.core.models.assets.Custom;
import io.kestra.core.models.assets.External;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.tasks.AssetFailureBehavior;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.airbyte.models.Connection;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionStream;
import io.kestra.plugin.airbyte.models.ConnectorLocation;
import io.kestra.plugin.airbyte.models.StreamDescriptor;
import io.kestra.plugin.airbyte.models.SyncMetadata;

/**
 * Lineage assets of a job: one {@code Table} per replicated stream, fed by the matching source stream: a {@code Table} for a database
 * source, else an {@code External} asset.
 */
public final class StreamAssets {
    public static final String TABLE_ASSET_TYPE = "io.kestra.plugin.ee.assets.Table";

    private static final int MAX_ID_LENGTH = 150;
    private static final Pattern INVALID_ID_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final Pattern LEADING_NON_ALPHANUMERIC = Pattern.compile("^[^a-zA-Z0-9]+");
    public static final String SOURCE_NAMESPACE_PLACEHOLDER = "${SOURCE_NAMESPACE}";

    private StreamAssets() {
    }

    public record Committed(Long records, Long bytes) {
    }

    /**
     * Same per-stream counts as the job's {@code metadata}, so assets and outputs can never disagree.
     */
    public static Map<StreamDescriptor, Committed> committed(SyncMetadata metadata) {
        var committed = new HashMap<StreamDescriptor, Committed>();
        if (metadata != null && metadata.getDestination() != null) {
            metadata.getDestination().forEach(
                table -> committed.put(
                    StreamDescriptor.builder().name(table.getName()).namespace(blankToNull(table.getNamespace())).build(),
                    new Committed(table.getRows(), table.getBytes())
                )
            );
        }
        return committed;
    }

    /**
     * Airbyte OSS reports the per-stream stats of a database source without {@code streamNamespace}, while the catalog stream carries one.
     * An exact (name, namespace) match always wins; otherwise the stats of the same name without a namespace are used, but only when that
     * name is unique among the selected catalog streams. With the same name in several namespaces the stats cannot be attributed, so the
     * stream is skipped rather than guessed.
     */
    private static Committed counts(Map<StreamDescriptor, Committed> committed, StreamDescriptor stream, boolean uniqueName) {
        var exact = committed.get(stream);
        if (exact != null || !uniqueName) {
            return exact;
        }
        return committed.get(StreamDescriptor.builder().name(stream.getName()).namespace(null).build());
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
        boolean jobSucceeded) {
        var connection = snapshot.getConnection();
        if (connection.getDestinationId() == null || connection.getSyncCatalog() == null || connection.getSyncCatalog().getStreams() == null) {
            return List.of();
        }

        var selected = new ArrayList<ConnectionStream>();
        for (var connectionStream : connection.getSyncCatalog().getStreams()) {
            if (
                connectionStream.getStream() != null && connectionStream.getStream().getName() != null
                    && connectionStream.getConfig() != null && Boolean.TRUE.equals(connectionStream.getConfig().getSelected())
            ) {
                selected.add(connectionStream);
            }
        }
        var selectedPerName = selected.stream().collect(Collectors.groupingBy(s -> s.getStream().getName(), Collectors.counting()));

        var planned = new ArrayList<Planned>();
        for (var connectionStream : connection.getSyncCatalog().getStreams()) {
            if (
                connectionStream.getStream() == null || connectionStream.getStream().getName() == null
                    || connectionStream.getConfig() == null || !Boolean.TRUE.equals(connectionStream.getConfig().getSelected())
            ) {
                continue;
            }

            var stream = connectionStream.getStream();
            var counts = counts(committed, stream, selectedPerName.getOrDefault(stream.getName(), 0L) == 1);
            // A succeeded job refreshes a stream even when nothing new arrived
            if (committed.isEmpty() ? !jobSucceeded : counts == null || counts.records() == null || (counts.records() <= 0 && !jobSucceeded)) {
                continue;
            }

            planned.add(new Planned(sourceIdentifier(snapshot, stream), table(snapshot, connectionStream, counts, jobId, jobStatus)));
        }
        return planned;
    }

    /**
     * Builds and emits the assets, applying {@code assets.assetFailureBehavior} since core never routes an exception thrown by
     * {@code run()} through it. An unsupported emitter (OSS) is skipped silently.
     *
     * @return {@code WARNING} when an asset failed under {@code WARN}, so the task can end in that state
     * @throws IllegalStateException when an asset failed under {@code FAIL}
     */
    public static Optional<State.Type> emit(RunContext runContext, Task task, String connectionId, Supplier<List<Planned>> planner) {
        // Core skips asset processing entirely without a declaration, so do the same
        if (task.getAssets() == null) {
            return Optional.empty();
        }
        var failure = new Failure(runContext, task, connectionId);
        List<Planned> planned;
        try {
            planned = planner.get();
        } catch (Exception e) {
            failure.record(null, e);
            return failure.warned ? Optional.of(State.Type.WARNING) : Optional.empty();
        }

        for (var asset : planned) {
            try {
                runContext.assets().emit(new AssetEmit(List.of(asset.input()), List.<Asset> of(asset.output())));
                runContext.logger().debug("Emitted asset '{}'", asset.output().getId());
            } catch (UnsupportedOperationException e) {
                runContext.logger().debug("Asset emission is not supported in this edition, skipping");
                break;
            } catch (Exception e) {
                failure.record(asset.output().getId(), e);
            }
        }
        return failure.warned ? Optional.of(State.Type.WARNING) : Optional.empty();
    }

    private static final class Failure {
        private final RunContext runContext;
        private final Task task;
        private final String connectionId;
        private boolean warned;

        private Failure(RunContext runContext, Task task, String connectionId) {
            this.runContext = runContext;
            this.task = task;
            this.connectionId = connectionId;
        }

        void record(String assetId, Exception cause) {
            var what = assetId == null ? "build the assets" : "emit asset '" + assetId + "'";
            var message = "Failed to " + what + " for connection " + connectionId + ": " + cause.getMessage();
            switch (behavior()) {
                case FAIL -> throw new IllegalStateException(
                    message + ". Set assets.assetFailureBehavior to WARN or IGNORE to not fail the task.", cause
                );
                case WARN -> {
                    runContext.logger().warn(message);
                    warned = true;
                }
                case IGNORE -> runContext.logger().debug(message);
            }
        }

        // Core falls back to WARN when the property cannot be rendered
        private AssetFailureBehavior behavior() {
            try {
                var declaration = task.getAssets();
                return declaration == null || declaration.getAssetFailureBehavior() == null
                    ? AssetFailureBehavior.WARN
                    : runContext.render(declaration.getAssetFailureBehavior()).as(AssetFailureBehavior.class).orElse(AssetFailureBehavior.WARN);
            } catch (Exception e) {
                return AssetFailureBehavior.WARN;
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
        var table = tableName(connection, stream);
        var location = snapshot.getDestinationLocation();
        var schema = schema(snapshot, stream);
        if (location != null && (location.schemaless() || schema != null)) {
            return sanitize(database(snapshot, stream), schema, table);
        }
        return sanitize(connection.getDestinationId(), namespace(connection, stream.getStream()), table);
    }

    // MySQL and ClickHouse have no schema level: the Airbyte namespace is the database
    private static String database(ConnectionSnapshot snapshot, ConnectionStream stream) {
        var location = snapshot.getDestinationLocation();
        if (location == null) {
            return null;
        }
        if (location.schemaless()) {
            var namespace = namespace(snapshot.getConnection(), stream.getStream());
            return namespace != null ? namespace : location.database();
        }
        return location.database();
    }

    private static String schema(ConnectionSnapshot snapshot, ConnectionStream stream) {
        var location = snapshot.getDestinationLocation();
        if (location == null || location.schemaless()) {
            return null;
        }
        var namespace = namespace(snapshot.getConnection(), stream.getStream());
        return namespace != null ? namespace : location.schema();
    }

    // Same rules as the Fivetran plugin so the same table gets the same id: allowed characters, alphanumeric start, 150 characters at most
    static String sanitize(String... segments) {
        var id = Stream.of(segments)
            .filter(Objects::nonNull)
            .map(segment -> INVALID_ID_CHARS.matcher(segment).replaceAll("_"))
            .collect(Collectors.joining("."));
        id = LEADING_NON_ALPHANUMERIC.matcher(id).replaceFirst("");
        if (id.length() <= MAX_ID_LENGTH) {
            return id;
        }
        return id.substring(0, MAX_ID_LENGTH - 9) + "_" + String.format("%08x", id.hashCode());
    }

    private static Custom table(ConnectionSnapshot snapshot, ConnectionStream stream, Committed counts, Long jobId, String jobStatus) {
        var connection = snapshot.getConnection();
        var config = stream.getConfig();
        var table = tableName(connection, stream);
        var source = snapshot.getSource();

        var metadata = new LinkedHashMap<String, Object>();
        put(metadata, "system", snapshot.getDestination() == null ? null : snapshot.getDestination().getDestinationName());
        put(metadata, "destinationName", snapshot.getDestination() == null ? null : snapshot.getDestination().getName());
        put(metadata, "destinationId", connection.getDestinationId());
        put(metadata, "database", database(snapshot, stream));
        put(metadata, "schema", schema(snapshot, stream));
        put(metadata, "name", table);
        put(metadata, "sourceId", connection.getSourceId());
        put(metadata, "sourceName", source == null ? null : source.getName());
        put(metadata, "sourceType", source == null ? null : source.getSourceName());
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
            put(metadata, "bytes", counts.bytes());
        }

        return Custom.builder()
            .id(assetId(snapshot, stream))
            .type(TABLE_ASSET_TYPE)
            .displayName(table)
            .metadata(metadata)
            .build();
    }

    // A database source is the same table the JDBC, dbt and Fivetran tasks emit; any other source stays an External asset
    private static AssetIdentifier sourceIdentifier(ConnectionSnapshot snapshot, StreamDescriptor stream) {
        var namespace = blankToNull(stream.getNamespace());
        var location = snapshot.getSourceLocation();
        if (location != null) {
            // MySQL and ClickHouse have no schema level: the stream namespace is the database
            var id = location.schemaless()
                ? sanitize(namespace != null ? namespace : location.database(), stream.getName())
                : sourceTableId(location, namespace, stream.getName());
            if (id != null) {
                return new AssetIdentifier(null, null, id, TABLE_ASSET_TYPE);
            }
        }
        return new AssetIdentifier(null, null, sanitize(snapshot.getConnection().getSourceId(), namespace, stream.getName()), External.ASSET_TYPE);
    }

    private static String sourceTableId(ConnectorLocation location, String namespace, String stream) {
        var schema = namespace != null ? namespace : location.schema();
        return schema == null ? null : sanitize(location.database(), schema, stream);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
