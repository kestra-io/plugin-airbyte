package io.kestra.plugin.airbyte.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(title = "Airbyte connection stream")
public class ConnectionStream {
    @Schema(title = "Stream", description = "Name and namespace of the stream in the source")
    StreamDescriptor stream;

    @Schema(title = "Configuration", description = "How the connection replicates the stream")
    StreamConfig config;
}
