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
@Schema(title = "Airbyte connection snapshot", description = "Connection, source and destination as configured when the job ran. Connector configurations are never included")
public class ConnectionSnapshot {
    Connection connection;
    Source source;
    Destination destination;

    /**
     * Airbyte OSS does not always return the workspace on the connection, but does on its source and destination.
     */
    public String resolvedWorkspaceId() {
        if (connection != null && connection.getWorkspaceId() != null) {
            return connection.getWorkspaceId();
        }
        if (source != null && source.getWorkspaceId() != null) {
            return source.getWorkspaceId();
        }
        return destination == null ? null : destination.getWorkspaceId();
    }
}
