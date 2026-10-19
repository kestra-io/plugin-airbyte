package io.kestra.plugin.airbyte.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Allow-list of the non-secret connection fields. Fields not declared here are dropped on deserialization.
 */
@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(title = "Airbyte connection")
public class Connection {
    @Schema(title = "Connection ID")
    String connectionId;

    @Schema(title = "Name")
    String name;

    @Schema(title = "Workspace ID")
    String workspaceId;

    @Schema(title = "Source ID")
    String sourceId;

    @Schema(title = "Destination ID")
    String destinationId;

    @Schema(title = "Status", description = "Connection status, for example `active`")
    String status;

    @Schema(title = "Schedule type")
    String scheduleType;

    @Schema(title = "Namespace definition", description = "How destination namespaces are chosen: `source`, `destination` or `customformat`")
    String namespaceDefinition;

    @Schema(title = "Namespace format", description = "Custom namespace format, used when the namespace definition is `customformat`")
    String namespaceFormat;

    @Schema(title = "Prefix", description = "Prefix added to destination table names")
    String prefix;

    @Schema(title = "Sync catalog", description = "Streams known to the connection")
    SyncCatalog syncCatalog;

    @Value
    @Jacksonized
    @SuperBuilder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(title = "Airbyte sync catalog")
    public static class SyncCatalog {
        @Schema(title = "Streams")
        List<ConnectionStream> streams;
    }
}
