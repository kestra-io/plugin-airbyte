package io.kestra.plugin.airbyte.cloud;

import java.util.List;

import com.airbyte.api.models.shared.ConnectionResponse;
import com.airbyte.api.models.shared.DestinationResponse;
import com.airbyte.api.models.shared.SourceResponse;
import com.airbyte.api.models.shared.StreamConfiguration;

import io.kestra.plugin.airbyte.models.Connection;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionStream;
import io.kestra.plugin.airbyte.models.Destination;
import io.kestra.plugin.airbyte.models.Source;
import io.kestra.plugin.airbyte.models.StreamConfig;
import io.kestra.plugin.airbyte.models.StreamDescriptor;

/**
 * Maps the Airbyte Cloud API responses onto the shared snapshot models. {@code configuration()} of sources and destinations is never read.
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
            .source(
                Source.builder()
                    .sourceId(source.sourceId())
                    .name(source.name())
                    .sourceName(source.sourceType())
                    .sourceDefinitionId(source.definitionId())
                    .workspaceId(source.workspaceId())
                    .build()
            )
            .destination(
                Destination.builder()
                    .destinationId(destination.destinationId())
                    .name(destination.name())
                    .destinationName(destination.destinationType())
                    .destinationDefinitionId(destination.definitionId())
                    .workspaceId(destination.workspaceId())
                    .build()
            )
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
