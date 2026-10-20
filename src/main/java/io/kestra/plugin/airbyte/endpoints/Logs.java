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
 * {@code attempt} (keeps the logs of that Kestra task run attempt number), {@code page} (default 1) and {@code size} (default 50, max 500).
 */
@Plugin
public class Logs implements PluginEndpoint {
    @Override
    public String name() {
        return "logs";
    }

    @Override
    public PluginEndpointResponse handle(PluginEndpointContext context) {
        // TODO: LogEntry.attemptNumber is Kestra's retry attempt, not the Airbyte attempt; pending a decision on how to map Airbyte attempts to logs
        var attempt = attempt(context.param("attempt"));

        var lines = context.logs().find(level(context.param("level"))).stream()
            .filter(Objects::nonNull)
            .filter(log -> attempt == null || attempt.equals(log.getAttemptNumber()))
            .sorted(Comparator.comparing(LogEntry::getTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())))
            .map(log -> new Line(log.getTimestamp(), log.getLevel(), log.getMessage(), log.getAttemptNumber()))
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

    private static Integer attempt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record Line(Instant timestamp, Level level, String message, Integer attemptNumber) {
    }
}
