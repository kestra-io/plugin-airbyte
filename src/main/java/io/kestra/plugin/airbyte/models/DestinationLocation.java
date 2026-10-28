package io.kestra.plugin.airbyte.models;

/**
 * Where a destination writes its tables (the project and dataset for BigQuery, no schema for MySQL), only used to build asset ids and never serialized.
 */
public record DestinationLocation(String database, String schema, boolean schemaless) {
}
