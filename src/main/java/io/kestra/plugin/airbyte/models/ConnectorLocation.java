package io.kestra.plugin.airbyte.models;

/** Database and schema a connector writes or reads, only used to build asset ids and never serialized. */
public record ConnectorLocation(String database, String schema, boolean schemaless) {
}
