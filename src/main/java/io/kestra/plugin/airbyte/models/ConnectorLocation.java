package io.kestra.plugin.airbyte.models;

/**
 * Where a connector writes (destination) or reads (database source) its tables (the project and dataset for BigQuery, no schema for MySQL),
 * only used to build asset ids and never serialized.
 */
public record ConnectorLocation(String database, String schema, boolean schemaless) {
}
