package io.kestra.plugin.airbyte.endpoints;

import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.event.Level;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.plugins.endpoint.PluginEndpoint;
import io.kestra.core.plugins.endpoint.PluginEndpointContext;
import io.kestra.core.plugins.endpoint.PluginEndpointResponse;

/**
 * Pages through the logs of the task run, oldest first. Query parameters: {@code level} (minimum level, default {@code INFO}),
 * {@code page} (default 1) and {@code size} (default 50, max 500).
 * <p>
 * The context is scoped to the task run in the URL. Per-attempt failure lines live on the {@code attempt-N} sub task run,
 * so call this endpoint on that task run id to get the logs of a single Airbyte attempt.
 */
@Plugin
public class Logs implements PluginEndpoint {
    @Override
    public String name() {
        return "logs";
    }

    @Override
    public PluginEndpointResponse handle(PluginEndpointContext context) {
        var lines = context.logs().find(level(context.param("level"))).stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(LogEntry::getTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())))
            .map(log -> new Line(log.getTimestamp(), log.getLevel(), log.getMessage()))
            .toList();

        return PluginEndpointResponse.of(EndpointPage.of(context, lines));
    }

    private static Level level(String value) {
        if (value == null || value.isBlank()) {
            return Level.INFO;
        }
        try {
            return Level.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Level.INFO;
        }
    }

    record Line(Instant timestamp, Level level, String message) {
    }
}
