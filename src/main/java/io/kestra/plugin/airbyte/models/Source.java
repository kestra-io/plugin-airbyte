package io.kestra.plugin.airbyte.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Allow-list of the non-secret source fields. The connector configuration is deliberately not mapped.
 */
@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(title = "Airbyte source")
public class Source {
    @Schema(title = "Source ID")
    String sourceId;

    @Schema(title = "Name")
    String name;

    @Schema(title = "Connector type", description = "Name of the source connector, for example `Postgres`")
    String sourceName;

    @Schema(title = "Definition ID", description = "ID of the connector definition")
    String sourceDefinitionId;

    @Schema(title = "Workspace ID")
    String workspaceId;
}
