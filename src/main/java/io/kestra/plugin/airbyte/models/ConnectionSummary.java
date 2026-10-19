package io.kestra.plugin.airbyte.models;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
@Schema(title = "Airbyte connection summary", description = "Short, non-secret description of the connection that ran the job")
public class ConnectionSummary {
    @Schema(title = "Connection ID")
    String connectionId;

    @Schema(title = "Name")
    String name;

    @Schema(title = "Status")
    String status;

    @Schema(title = "Schedule type")
    String scheduleType;

    @Schema(title = "Workspace ID")
    String workspaceId;

    @Schema(title = "Source ID")
    String sourceId;

    @Schema(title = "Source name", description = "Name given to the source in Airbyte")
    String sourceName;

    @Schema(title = "Source type", description = "Source connector type, for example `Postgres`")
    String sourceType;

    @Schema(title = "Destination ID")
    String destinationId;

    @Schema(title = "Destination name", description = "Name given to the destination in Airbyte")
    String destinationName;

    @Schema(title = "Destination type", description = "Destination connector type, for example `Snowflake`")
    String destinationType;

    @Schema(title = "Selected stream count", description = "Number of streams replicated by the connection")
    Integer streamCount;

    public static ConnectionSummary from(ConnectionSnapshot snapshot) {
        var connection = snapshot.getConnection();
        var source = snapshot.getSource();
        var destination = snapshot.getDestination();
        var streams = connection.getSyncCatalog() == null || connection.getSyncCatalog().getStreams() == null
            ? List.<ConnectionStream> of()
            : connection.getSyncCatalog().getStreams();

        return ConnectionSummary.builder()
            .connectionId(connection.getConnectionId())
            .name(connection.getName())
            .status(connection.getStatus())
            .scheduleType(connection.getScheduleType())
            .workspaceId(snapshot.resolvedWorkspaceId())
            .sourceId(connection.getSourceId())
            .sourceName(source == null ? null : source.getName())
            .sourceType(source == null ? null : source.getSourceName())
            .destinationId(connection.getDestinationId())
            .destinationName(destination == null ? null : destination.getName())
            .destinationType(destination == null ? null : destination.getDestinationName())
            .streamCount((int) streams.stream().filter(s -> s.getConfig() != null && Boolean.TRUE.equals(s.getConfig().getSelected())).count())
            .build();
    }
}
