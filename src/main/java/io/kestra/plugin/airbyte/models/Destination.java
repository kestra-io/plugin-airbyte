package io.kestra.plugin.airbyte.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Allow-list of the non-secret destination fields. The connector configuration is deliberately not mapped.
 */
@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(title = "Airbyte destination")
public class Destination {
    @Schema(title = "Destination ID")
    String destinationId;

    @Schema(title = "Name")
    String name;

    @Schema(title = "Connector type", description = "Name of the destination connector, for example `Postgres`")
    String destinationName;

    @Schema(title = "Definition ID", description = "ID of the connector definition")
    String destinationDefinitionId;

    @Schema(title = "Workspace ID")
    String workspaceId;
}
