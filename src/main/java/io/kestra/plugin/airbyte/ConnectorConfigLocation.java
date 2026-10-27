package io.kestra.plugin.airbyte;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Read model of a destination or source connector configuration that only maps the location keys. It is never persisted nor logged, so the
 * rest of the configuration, secrets included, is dropped on parsing.
 */
@Value
@Jacksonized
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectorConfigLocation {
    String database;
    String schema;
    @JsonProperty("project_id")
    String projectId;
    @JsonProperty("dataset_id")
    String datasetId;
}
