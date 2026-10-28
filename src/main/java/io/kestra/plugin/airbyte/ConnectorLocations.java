package io.kestra.plugin.airbyte;

import java.util.Locale;
import java.util.Optional;

import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.airbyte.models.ConnectorConfigLocation;
import io.kestra.plugin.airbyte.models.ConnectorLocation;

/**
 * Resolves the database and schema a connector (destination or source) points at, so asset ids can line up with the ones of other plugins that use the same names.
 */
public final class ConnectorLocations {
    private ConnectorLocations() {
    }

    /**
     * Location of a destination. Never throws: an unreadable or unknown configuration only means asset ids fall back to the Airbyte
     * destination ID.
     *
     * @param connectorType the connector name, such as {@code Snowflake} on self-hosted or {@code snowflake} on Airbyte Cloud
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

    private static Optional<ConnectorLocation> resolve(String connectorType, ConnectorConfigLocation config, boolean source) {
        if (config == null || connectorType == null) {
            return Optional.empty();
        }

        var type = connectorType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        // Snowflake Cortex shares the name but is a vector store, not a warehouse with tables
        if (type.contains("cortex")) {
            return Optional.empty();
        }
        if (type.contains("bigquery")) {
            return of(config.getProjectId(), config.getDatasetId(), false);
        }
        if (type.contains("mysql") || type.contains("clickhouse")) {
            return of(config.getDatabase(), null, true);
        }
        if (
            type.contains("postgres") || type.contains("redshift") || type.contains("mssql") || type.contains("sqlserver")
                || type.contains("snowflake") || (!source && type.contains("databricks"))
        ) {
            return of(config.getDatabase(), config.getSchema(), false);
        }
        return Optional.empty();
    }

    private static Optional<ConnectorLocation> of(String database, String schema, boolean schemaless) {
        var usableDatabase = usable(database);
        return usableDatabase == null ? Optional.empty() : Optional.of(new ConnectorLocation(usableDatabase, usable(schema), schemaless));
    }

    private static String usable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
