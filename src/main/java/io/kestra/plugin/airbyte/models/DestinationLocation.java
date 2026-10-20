package io.kestra.plugin.airbyte.models;

/**
 * Where a destination writes its tables, read from the connector configuration. Never serialized: it only feeds asset ids.
 *
 * @param database database, or the project for BigQuery
 * @param schema default schema, or the dataset for BigQuery
 * @param schemaless whether the destination has no schema level, such as MySQL
 */
public record DestinationLocation(String database, String schema, boolean schemaless) {
}
