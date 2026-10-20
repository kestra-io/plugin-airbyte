package io.kestra.plugin.airbyte.endpoints;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.event.Level;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.plugins.endpoint.PluginEndpoint;
import io.kestra.core.plugins.endpoint.PluginEndpointContext;
import io.kestra.core.plugins.endpoint.PluginEndpointResponse;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.airbyte.models.LogRow;

/**
 * Pages through the logs of the task run, oldest first.
 * <p>
 * When the task run has an {@code airbyteLogs} output, the Airbyte log events are served ({@code origin} {@code airbyte}).
 * Otherwise the Kestra task run logs are served ({@code origin} {@code kestra}), which is the case for task runs that ended before
 * the output existed, ran without logs, or are not an Airbyte job.
 * <p>
 * Query parameters:
 * <ul>
 * <li>{@code level}: minimum level, default {@code INFO}.</li>
 * <li>{@code q}: case-insensitive substring of the message.</li>
 * <li>{@code attempt}: Airbyte attempt number. Airbyte logs only.</li>
 * <li>{@code source}: {@code platform}, {@code source}, {@code destination} or {@code orchestrator}, repeated or comma-separated. Airbyte logs only.</li>
 * <li>{@code page} (default 1) and {@code size} (default 50, max 500).</li>
 * </ul>
 * The endpoint SPI cannot answer with an HTTP 400, so an invalid {@code level}, {@code attempt} or {@code source} is ignored rather than rejected.
 * <p>
 * The context is scoped to the task run in the URL. Per-attempt failure lines live on the {@code attempt-N} sub task run,
 * which has no {@code airbyteLogs} output: call this endpoint on that task run id to get its Kestra logs.
 */
@Plugin
public class Logs implements PluginEndpoint {
    private static final String ORIGIN_AIRBYTE = "airbyte";
    private static final String ORIGIN_KESTRA = "kestra";
    private static final String ORCHESTRATOR = "replication-orchestrator";
    private static final Set<String> SOURCES = Set.of("platform", ORCHESTRATOR, "source", "destination");

    @Override
    public String name() {
        return "logs";
    }

    @Override
    public PluginEndpointResponse handle(PluginEndpointContext context) {
        var level = level(context.param("level"));
        var query = query(context.param("q"));

        var stored = StoredOutput.open(context, "airbyteLogs");
        if (stored.isPresent()) {
            var attempt = attempt(context.param("attempt"));
            var sources = sources(context);
            var lines = read(stored.get()).stream()
                .filter(row -> attempt == null || attempt.equals(row.getAttemptNumber()))
                .filter(row -> sources.isEmpty() || sources.contains(row.getLogSource()))
                .filter(row -> row.getLevel() != null && row.getLevel().toInt() >= level.toInt())
                .filter(row -> matches(query, row.getMessage()))
                .map(row -> new Line(row.getTimestamp(), row.getLevel(), row.getMessage(), row.getAttemptNumber(), row.label()))
                .toList();
            return PluginEndpointResponse.of(Response.of(ORIGIN_AIRBYTE, EndpointPage.of(context, lines)));
        }

        var lines = context.logs().find(level).stream()
            .filter(Objects::nonNull)
            .filter(log -> matches(query, log.getMessage()))
            .sorted(Comparator.comparing(LogEntry::getTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())))
            .map(log -> new Line(log.getTimestamp(), log.getLevel(), log.getMessage(), null, null))
            .toList();
        return PluginEndpointResponse.of(Response.of(ORIGIN_KESTRA, EndpointPage.of(context, lines)));
    }

    private static List<LogRow> read(InputStream input) {
        try (input) {
            return FileSerde.readAll(input, LogRow.class).collectList().blockOptional().orElseGet(List::of);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    private static String query(String value) {
        return value == null || value.isBlank() ? null : value.toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String query, String message) {
        return query == null || (message != null && message.toLowerCase(Locale.ROOT).contains(query));
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

    private static Set<String> sources(PluginEndpointContext context) {
        return context.parameters().getOrDefault("source", List.of()).stream()
            .filter(Objects::nonNull)
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(value -> value.strip().toLowerCase(Locale.ROOT))
            .map(value -> "orchestrator".equals(value) ? ORCHESTRATOR : value)
            .filter(SOURCES::contains)
            .collect(Collectors.toUnmodifiableSet());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Line(Instant timestamp, Level level, String message, Integer attempt, String source) {
    }

    record Response(String origin, int page, int size, long total, List<Line> results) {
        static Response of(String origin, EndpointPage<Line> page) {
            return new Response(origin, page.page(), page.size(), page.total(), page.results());
        }
    }
}
