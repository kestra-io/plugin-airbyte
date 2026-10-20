package io.kestra.plugin.airbyte;

import java.util.Optional;
import java.util.regex.Pattern;

import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.airbyte.models.DestinationLocation;

/**
 * Resolves the database and schema a destination writes to, so asset ids can match the ones of dbt, JDBC and Fivetran.
 */
public final class DestinationLocations {
    private static final Pattern MASKED = Pattern.compile("\\*+");

    private DestinationLocations() {
    }

    /**
     * Never throws: an unreadable or unknown configuration only means asset ids fall back to the Airbyte destination ID.
     *
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

    public static Optional<DestinationLocation> resolve(String connectorType, DestinationConfigLocation config) {
        if (config == null) {
            return Optional.empty();
        }

        var type = connectorType == null ? "" : connectorType.toLowerCase().replaceAll("[^a-z0-9]", "");
        DestinationLocation location = null;
        if (type.contains("bigquery")) {
            location = of(config.getProjectId(), config.getDatasetId(), false);
        } else if (type.contains("mysql") || type.contains("clickhouse")) {
            location = of(config.getDatabase(), null, true);
        } else if (
            type.contains("postgres") || type.contains("redshift") || type.contains("mssql") || type.contains("sqlserver")
                || type.contains("snowflake") || type.contains("databricks")
        ) {
            location = of(config.getDatabase(), config.getSchema(), false);
        }
        return Optional.ofNullable(location);
    }

    private static DestinationLocation of(String database, String schema, boolean schemaless) {
        var rDatabase = usable(database);
        return rDatabase == null ? null : new DestinationLocation(rDatabase, usable(schema), schemaless);
    }

    // Airbyte masks secret fields with asterisks, which must never end up in an id
    private static String usable(String value) {
        return value == null || value.isBlank() || MASKED.matcher(value.trim()).matches() ? null : value.trim();
    }
}
