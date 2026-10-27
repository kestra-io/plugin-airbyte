package io.kestra.plugin.airbyte.models;

/**
 * Where a connector reads or writes its tables (a destination writes them, a database source reads them), read from the connector configuration. Never serialized: it
 * only feeds asset ids.
 *
 * @param database database, or the project for BigQuery
 * @param schema default schema, or the dataset for BigQuery
 * @param schemaless whether the connector has no schema level, such as MySQL
 */
public record ConnectorLocation(String database, String schema, boolean schemaless) {
}
