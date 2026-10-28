package io.kestra.plugin.airbyte;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.kestra.core.models.assets.Asset;
import io.kestra.core.models.assets.AssetIdentifier;
import io.kestra.core.models.assets.AssetsDeclaration;
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
import io.kestra.plugin.airbyte.models.StreamDescriptor;
import io.kestra.plugin.airbyte.models.SyncMetadata;

/**
 * Lineage assets of a job: one {@code Table} per replicated stream, fed by the matching source stream.
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
                    StreamDescriptor.builder().name(table.getName()).namespace(table.getNamespace()).build(),
                    new Committed(table.getRows(), table.getBytes())
                )
            );
        }
        return committed;
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

        var planned = new ArrayList<Planned>();
        for (var connectionStream : connection.getSyncCatalog().getStreams()) {
            if (
                connectionStream.getStream() == null || connectionStream.getStream().getName() == null
                    || connectionStream.getConfig() == null || !Boolean.TRUE.equals(connectionStream.getConfig().getSelected())
            ) {
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

    /**
     * Result of {@link #emit}: the task is expected to end in {@code state} when not null, and to fail with {@code failure} when
     * not null (only under the {@code FAIL} behavior).
     */
    public record Outcome(State.Type state, Exception failure) {
        public static final Outcome NONE = new Outcome(null, null);
    }

    /**
     * Builds and emits the assets, applying {@code assets.assetFailureBehavior} since core never routes an exception thrown by
     * {@code run()} through it. An unsupported emitter (OSS) is skipped silently. Nothing is built unless {@code assets.enableAuto}
     * is on, as the emitter drops the assets otherwise.
     */
    public static Outcome emit(RunContext runContext, Task task, String connectionId, Supplier<List<Planned>> planner) {
        // Core skips asset processing entirely without a declaration or without enableAuto, so do the same
        if (task.getAssets() == null || !autoEnabled(runContext, task.getAssets())) {
            return Outcome.NONE;
        }
        var failure = new Failure(runContext, task, connectionId);
        List<Planned> planned;
        try {
            planned = planner.get();
        } catch (Exception e) {
            failure.record(null, e);
            return failure.outcome();
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
                if (failure.failure != null) {
                    break;
                }
            }
        }
        return failure.outcome();
    }

    private static boolean autoEnabled(RunContext runContext, AssetsDeclaration declaration) {
        try {
            return runContext.render(declaration.getEnableAuto()).as(Boolean.class).orElse(false);
        } catch (Exception e) {
            runContext.logger().warn("Unable to render assets.enableAuto, skipping assets: {}", e.getMessage());
            return false;
        }
    }

    private static final class Failure {
        private final RunContext runContext;
        private final Task task;
        private final String connectionId;
        private boolean warned;
        private IllegalStateException failure;

        private Failure(RunContext runContext, Task task, String connectionId) {
            this.runContext = runContext;
            this.task = task;
            this.connectionId = connectionId;
        }

        Outcome outcome() {
            return new Outcome(warned ? State.Type.WARNING : null, failure);
        }

        void record(String assetId, Exception cause) {
            var what = assetId == null ? "build the assets" : "emit asset '%s'".formatted(assetId);
            var message = "Failed to %s for connection %s: %s".formatted(what, connectionId, cause.getMessage());
            switch (behavior()) {
                case FAIL -> failure = new IllegalStateException(
                    message + ". Set assets.assetFailureBehavior to WARN or IGNORE to not fail the task.", cause
                );
                case WARN -> {
                    runContext.logger().warn(message);
                    warned = true;
                }
                case IGNORE -> runContext.logger().debug(message);
            }
        }

        private AssetFailureBehavior behavior() {
            try {
                return runContext.render(task.getAssets().getAssetFailureBehavior()).as(AssetFailureBehavior.class).orElse(AssetFailureBehavior.WARN);
            } catch (Exception e) {
                // Same fallback as core
                runContext.logger().warn("Unable to render assetFailureBehavior, defaulting to WARN: {}", e.getMessage());
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
        return id.substring(0, MAX_ID_LENGTH - 9) + "_" + "%08x".formatted(id.hashCode());
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

    private static AssetIdentifier sourceIdentifier(Connection connection, StreamDescriptor stream) {
        return new AssetIdentifier(null, null, sanitize(connection.getSourceId(), blankToNull(stream.getNamespace()), stream.getName()), External.ASSET_TYPE);
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
