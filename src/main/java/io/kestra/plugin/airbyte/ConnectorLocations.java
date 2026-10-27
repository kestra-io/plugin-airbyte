package io.kestra.plugin.airbyte;

import java.util.Optional;
import java.util.regex.Pattern;

import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.airbyte.models.ConnectorLocation;

/**
 * Resolves the database and schema a connector (destination or source) points at, so asset ids can match the ones of dbt, JDBC and Fivetran.
 */
public final class ConnectorLocations {
    private static final Pattern MASKED = Pattern.compile("\\*+");

    private ConnectorLocations() {
    }

    /**
     * Location of a destination. Never throws: an unreadable or unknown configuration only means asset ids fall back to the Airbyte
     * destination ID.
     *
     * @param configuration the raw connector configuration, a map or an SDK model
     */
    public static Optional<ConnectorLocation> parse(String connectorType, Object configuration) {
        return parse(connectorType, configuration, false);
    }

    /**
     * Location of a database source, whose configuration holds the same location keys. Databricks is destination-only in Airbyte, so it
     * is not resolved here. Never throws.
     */
    public static Optional<ConnectorLocation> parseSource(String connectorType, Object configuration) {
        return parse(connectorType, configuration, true);
    }

    private static Optional<ConnectorLocation> parse(String connectorType, Object configuration, boolean source) {
        if (configuration == null) {
            return Optional.empty();
        }
        try {
            return resolve(connectorType, JacksonMapper.ofJson().convertValue(configuration, ConnectorConfigLocation.class), source);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static Optional<ConnectorLocation> resolve(String connectorType, ConnectorConfigLocation config) {
        return resolve(connectorType, config, false);
    }

    private static Optional<ConnectorLocation> resolve(String connectorType, ConnectorConfigLocation config, boolean source) {
        if (config == null) {
            return Optional.empty();
        }

        var type = connectorType == null ? "" : connectorType.toLowerCase().replaceAll("[^a-z0-9]", "");
        ConnectorLocation location = null;
        if (type.contains("bigquery")) {
            location = of(config.getProjectId(), config.getDatasetId(), false);
        } else if (type.contains("mysql") || type.contains("clickhouse")) {
            location = of(config.getDatabase(), null, true);
        } else if (
            type.contains("postgres") || type.contains("redshift") || type.contains("mssql") || type.contains("sqlserver")
                || type.contains("snowflake") || (!source && type.contains("databricks"))
        ) {
            location = of(config.getDatabase(), config.getSchema(), false);
        }
        return Optional.ofNullable(location);
    }

    private static ConnectorLocation of(String database, String schema, boolean schemaless) {
        var usableDatabase = usable(database);
        return usableDatabase == null ? null : new ConnectorLocation(usableDatabase, usable(schema), schemaless);
    }

    // Airbyte masks secret fields with asterisks, which must never end up in an id
    private static String usable(String value) {
        return value == null || value.isBlank() || MASKED.matcher(value.trim()).matches() ? null : value.trim();
    }
}
