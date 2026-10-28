package io.kestra.plugin.airbyte;

import java.util.Locale;
import java.util.Optional;

import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.airbyte.models.DestinationConfigLocation;
import io.kestra.plugin.airbyte.models.DestinationLocation;

/**
 * Resolves the database and schema a destination writes to, so asset ids can line up with the ones of other plugins that use the same names.
 */
public final class DestinationLocations {
    private DestinationLocations() {
    }

    /**
     * Never throws: an unreadable or unknown configuration only means asset ids fall back to the Airbyte destination ID.
     *
     * @param connectorType the connector name, such as {@code Snowflake} on self-hosted or {@code snowflake} on Airbyte Cloud
     * @param configuration the raw connector configuration, a map or an SDK model
     */
    public static Optional<DestinationLocation> parse(String connectorType, Object configuration) {
        if (configuration == null) {
            return Optional.empty();
        }
        try {
            return resolve(connectorType, JacksonMapper.ofJson().convertValue(configuration, DestinationConfigLocation.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<DestinationLocation> resolve(String connectorType, DestinationConfigLocation config) {
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
                || type.contains("snowflake") || type.contains("databricks")
        ) {
            return of(config.getDatabase(), config.getSchema(), false);
        }
        return Optional.empty();
    }

    private static Optional<DestinationLocation> of(String database, String schema, boolean schemaless) {
        var usableDatabase = usable(database);
        return usableDatabase == null ? Optional.empty() : Optional.of(new DestinationLocation(usableDatabase, usable(schema), schemaless));
    }

    private static String usable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
