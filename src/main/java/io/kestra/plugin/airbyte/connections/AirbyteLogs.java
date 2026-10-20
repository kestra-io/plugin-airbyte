package io.kestra.plugin.airbyte.connections;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.event.Level;

import io.kestra.plugin.airbyte.models.Attempt;
import io.kestra.plugin.airbyte.models.AttemptInfo;
import io.kestra.plugin.airbyte.models.Event;
import io.kestra.plugin.airbyte.models.LogRow;

/**
 * Forwards the Airbyte job logs to the Kestra task logs and keeps them for the {@code airbyteLogs} file, each event exactly once.
 * <p>
 * Airbyte returns the whole log of an attempt on every call and only ever appends to it, so the number of events already
 * handled is tracked per attempt number.
 */
final class AirbyteLogs {
    private static final String ANSI_ESCAPE = "\u001b[";

    static final String STACK_TRACE_OMITTED = "\t... stack trace omitted";

    private static final Pattern STACK_FRAME = Pattern.compile("^\\s+(at \\S.*|\\.\\.\\. \\d+ (more|common frames omitted).*)$");

    private final Logger logger;
    private final Level platformLevel;
    private final int maxEvents;
    private final Map<Integer, Integer> handled = new HashMap<>();
    private final List<LogRow> rows = new ArrayList<>();
    private boolean truncated;

    AirbyteLogs(Logger logger, Level platformLevel, int maxEvents) {
        this.logger = logger;
        this.platformLevel = platformLevel;
        this.maxEvents = maxEvents;
    }

    boolean isEnabled() {
        return maxEvents > 0;
    }

    List<LogRow> rows() {
        return List.copyOf(rows);
    }

    void consume(List<AttemptInfo> attempts) {
        for (var index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            consume(attemptNumber(attempt, index), attempt);
        }
    }

    void consume(int attemptNumber, AttemptInfo attempt) {
        if (!isEnabled() || attempt == null || attempt.getLogs() == null) {
            return;
        }

        var logs = attempt.getLogs();
        var events = Optional.ofNullable(logs.getEvents()).orElseGet(List::of);
        if (!events.isEmpty()) {
            var from = handled.getOrDefault(attemptNumber, 0);
            for (var index = from; index < events.size(); index++) {
                add(toRow(attemptNumber, events.get(index)));
            }
            handled.put(attemptNumber, Math.max(from, events.size()));
            return;
        }

        var lines = Optional.ofNullable(logs.getLogLines()).orElseGet(List::of);
        var from = handled.getOrDefault(attemptNumber, 0);
        for (var index = from; index < lines.size(); index++) {
            add(toLegacyRow(attemptNumber, lines.get(index)));
        }
        handled.put(attemptNumber, Math.max(from, lines.size()));
    }

    static int attemptNumber(AttemptInfo info, int fallback) {
        return Optional.ofNullable(info.getAttempt())
            .map(Attempt::getId)
            .map(Long::intValue)
            .orElse(fallback);
    }

    private void add(LogRow row) {
        if (row == null) {
            return;
        }
        if (rows.size() >= maxEvents) {
            if (!truncated) {
                truncated = true;
                logger.warn("Airbyte logs exceed `maxLogEvents` ({}): the following events are neither logged nor stored", maxEvents);
            }
            return;
        }

        rows.add(row);
        log(row);
    }

    private void log(LogRow row) {
        if (row.getLogSource() == null) {
            logAt(row.getLevel(), row.getMessage());
            return;
        }

        var level = "platform".equals(row.getLogSource()) && row.getLevel() == Level.INFO ? platformLevel : row.getLevel();
        logAt(level, "[" + row.label() + "] " + row.getMessage());
    }

    private void logAt(Level level, String message) {
        switch (level) {
            case ERROR -> logger.error("{}", message);
            case WARN -> logger.warn("{}", message);
            case DEBUG -> logger.debug("{}", message);
            case TRACE -> logger.trace("{}", message);
            default -> logger.info("{}", message);
        }
    }

    private static LogRow toRow(int attemptNumber, Event event) {
        var message = event == null ? null : sanitize(event.getMessage());
        if (message == null) {
            return null;
        }

        return LogRow.builder()
            .attemptNumber(attemptNumber)
            .timestamp(event.getTimestamp() == null ? null : Instant.ofEpochMilli(event.getTimestamp()))
            .level(level(event.getLevel()))
            .logSource(event.getLogSource())
            .message(message)
            .build();
    }

    private static LogRow toLegacyRow(int attemptNumber, String line) {
        var message = sanitize(line);
        if (message == null) {
            return null;
        }

        return LogRow.builder()
            .attemptNumber(attemptNumber)
            .level(guessLevel(message))
            .message(message)
            .build();
    }

    static Level level(String value) {
        if (value == null) {
            return Level.INFO;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "error" -> Level.ERROR;
            case "warn", "warning" -> Level.WARN;
            case "debug" -> Level.DEBUG;
            case "trace" -> Level.TRACE;
            default -> Level.INFO;
        };
    }

    // Legacy lines colour the level name: `ERROR` + ESC + `[`
    private static Level guessLevel(String line) {
        if (line.contains("ERROR" + ANSI_ESCAPE)) {
            return Level.ERROR;
        }
        if (line.contains("WARN" + ANSI_ESCAPE)) {
            return Level.WARN;
        }
        if (line.contains("DEBUG" + ANSI_ESCAPE)) {
            return Level.DEBUG;
        }
        if (line.contains("TRACE" + ANSI_ESCAPE)) {
            return Level.TRACE;
        }
        return Level.INFO;
    }

    /**
     * Drops stack trace frames, which expose connector internals, like {@code failures} drop the stack trace. The exception
     * class and message lines are kept. {@code null} when nothing but frames or blanks remain.
     */
    static String sanitize(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        var lines = message.split("\n", -1);
        var kept = new ArrayList<String>();
        var omitted = false;
        for (var line : lines) {
            if (STACK_FRAME.matcher(line).matches()) {
                omitted = true;
            } else {
                kept.add(line);
            }
        }

        if (!omitted) {
            return message;
        }
        if (kept.stream().allMatch(String::isBlank)) {
            return null;
        }
        return String.join("\n", kept).stripTrailing() + "\n" + STACK_TRACE_OMITTED;
    }
}
