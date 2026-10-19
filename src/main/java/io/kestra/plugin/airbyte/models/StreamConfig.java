package io.kestra.plugin.airbyte.models;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(title = "Airbyte stream configuration")
public class StreamConfig {
    @Schema(title = "Selected", description = "Whether the stream is replicated by the connection")
    Boolean selected;

    @Schema(title = "Sync mode")
    String syncMode;

    @Schema(title = "Destination sync mode")
    String destinationSyncMode;

    @Schema(title = "Cursor field")
    List<String> cursorField;

    @Schema(title = "Primary key")
    List<List<String>> primaryKey;

    @Schema(title = "Destination object name", description = "Table name set explicitly for the stream in the destination")
    String destinationObjectName;
}
