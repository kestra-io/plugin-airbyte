package io.kestra.plugin.airbyte.cloud;

import java.util.List;

import com.airbyte.api.models.shared.ConnectionResponse;
import com.airbyte.api.models.shared.DestinationResponse;
import com.airbyte.api.models.shared.NamespaceDefinitionEnum;
import com.airbyte.api.models.shared.SourceResponse;
import com.airbyte.api.models.shared.StreamConfiguration;

import io.kestra.plugin.airbyte.DestinationLocations;
import io.kestra.plugin.airbyte.models.Connection;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionStream;
import io.kestra.plugin.airbyte.models.Destination;
import io.kestra.plugin.airbyte.models.DestinationLocation;
import io.kestra.plugin.airbyte.models.Source;
import io.kestra.plugin.airbyte.models.StreamConfig;
import io.kestra.plugin.airbyte.models.StreamDescriptor;

/**
 * Maps the Airbyte Cloud API responses onto the shared snapshot models. {@code configuration()} of sources is never read and the one of
 * destinations only for its location keys, which are not part of the serialized snapshot. A null source or destination is allowed when
 * the SDK could not read it.
 */
public final class CloudSnapshots {
    public static final String WEBAPP_URL = "https://cloud.airbyte.com";

    private CloudSnapshots() {
    }

    public static ConnectionSnapshot toSnapshot(ConnectionResponse connection, SourceResponse source, DestinationResponse destination) {
        var streams = connection.configurations().streams().orElseGet(List::of).stream().map(CloudSnapshots::toStream).toList();

        return ConnectionSnapshot.builder()
            .connection(
                Connection.builder()
                    .connectionId(connection.connectionId())
                    .name(connection.name())
                    .workspaceId(connection.workspaceId())
                    .sourceId(connection.sourceId())
                    .destinationId(connection.destinationId())
                    .status(connection.status().value())
                    .scheduleType(connection.schedule().scheduleType().value())
                    .namespaceDefinition(connection.namespaceDefinition().map(definition -> definition.value()).orElse(null))
                    .namespaceFormat(connection.namespaceFormat().orElse(null))
                    .prefix(connection.prefix().orElse(null))
                    .syncCatalog(Connection.SyncCatalog.builder().streams(streams).build())
                    .build()
            )
            .source(source == null ? null : toSource(source))
            .destination(destination == null ? null : toDestination(destination))
            .destinationLocation(location(connection, destination))
            .build();
    }

    /**
     * The Cloud API does not report the namespace of a stream, so the destination default schema is only right when the connection writes
     * there. For a {@code source} namespace or a custom format using {@code ${SOURCE_NAMESPACE}} the location stays null and asset ids fall
     * back to the destination ID.
     */
    private static DestinationLocation location(ConnectionResponse connection, DestinationResponse destination) {
        if (destination == null) {
            return null;
        }
        var definition = connection.namespaceDefinition().orElse(null);
        var usesDefaultSchema = definition == NamespaceDefinitionEnum.DESTINATION
            || (definition == NamespaceDefinitionEnum.CUSTOM_FORMAT && !connection.namespaceFormat().orElse("").contains("${SOURCE_NAMESPACE}"));
        return usesDefaultSchema ? DestinationLocations.parse(destination.destinationType(), destination.configuration()).orElse(null) : null;
    }

    private static Source toSource(SourceResponse source) {
        return Source.builder()
            .sourceId(source.sourceId())
            .name(source.name())
            .sourceName(source.sourceType())
            .sourceDefinitionId(source.definitionId())
            .workspaceId(source.workspaceId())
            .build();
    }

    private static Destination toDestination(DestinationResponse destination) {
        return Destination.builder()
            .destinationId(destination.destinationId())
            .name(destination.name())
            .destinationName(destination.destinationType())
            .destinationDefinitionId(destination.definitionId())
            .workspaceId(destination.workspaceId())
            .build();
    }

    private static ConnectionStream toStream(StreamConfiguration stream) {
        return ConnectionStream.builder()
            .stream(StreamDescriptor.builder().name(stream.name()).build())
            .config(
                StreamConfig.builder()
                    .selected(true)
                    .syncMode(stream.syncMode().map(mode -> mode.value()).orElse(null))
                    .cursorField(stream.cursorField().orElse(null))
                    .primaryKey(stream.primaryKey().orElse(null))
                    .build()
            )
            .build();
    }
}
