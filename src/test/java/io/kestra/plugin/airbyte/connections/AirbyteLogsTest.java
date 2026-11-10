package io.kestra.plugin.airbyte.connections;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.helpers.MessageFormatter;

import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.airbyte.models.Attempt;
import io.kestra.plugin.airbyte.models.AttemptInfo;
import io.kestra.plugin.airbyte.models.Event;
import io.kestra.plugin.airbyte.models.Log;
import io.kestra.plugin.airbyte.models.LogRow;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class AirbyteLogsTest {
    private final List<String> captured = new ArrayList<>();

    private final Logger logger = (Logger) Proxy.newProxyInstance(
        Logger.class.getClassLoader(),
        new Class<?>[] { Logger.class },
        (proxy, method, args) ->
        {
            if (args != null && args.length == 2 && args[0] instanceof String format) {
                captured.add(method.getName().toUpperCase() + " " + MessageFormatter.format(format, args[1]).getMessage());
            }
            return null;
        }
    );

    @TempDir
    private Path dir;

    private AirbyteLogs logs(Level platformLevel, int max) {
        return new AirbyteLogs(logger, platformLevel, max, dir.resolve("airbyte-logs.ion"));
    }

    private static List<LogRow> rows(AirbyteLogs logs) throws IOException {
        logs.close();
        if (logs.count() == 0) {
            return List.of();
        }
        try (var input = Files.newInputStream(logs.file())) {
            return FileSerde.readAll(input, LogRow.class).collectList().block();
        }
    }

    private static Event event(long timestamp, String level, String source, String message) {
        return Event.builder().timestamp(timestamp).level(level).logSource(source).message(message).caller(Map.of("className", "io.airbyte.Internal")).build();
    }

    private static AttemptInfo attempt(long number, Event... events) {
        return AttemptInfo.builder()
            .attempt(Attempt.builder().id(number).build())
            .logType("structured")
            .logs(Log.builder().version("1").logLines(List.of()).events(List.of(events)).build())
            .build();
    }

    private static AttemptInfo legacyAttempt(long number, String... lines) {
        return AttemptInfo.builder()
            .attempt(Attempt.builder().id(number).build())
            .logs(Log.builder().logLines(List.of(lines)).build())
            .build();
    }

    private List<String> formatted() {
        return captured;
    }

    @Test
    void prefixes_the_source_and_maps_the_level_case_insensitively() throws Exception {
        var logs = logs(Level.INFO, 100);

        logs.consume(
            List.of(
                attempt(
                    0,
                    event(1, "info", "platform", "p"),
                    event(2, "WARN", "source", "s"),
                    event(3, "Error", "destination", "d"),
                    event(4, "debug", "replication-orchestrator", "o"),
                    event(5, "trace", "source", "t"),
                    event(6, "loud", "source", "unknown")
                )
            )
        );

        assertThat(
            formatted(), contains(
                "INFO [Airbyte][platform] p",
                "WARN [Airbyte][source] s",
                "ERROR [Airbyte][destination] d",
                "DEBUG [Airbyte][orchestrator] o",
                "TRACE [Airbyte][source] t",
                "INFO [Airbyte][source] unknown"
            )
        );
        assertThat(rows(logs).get(3).getLogSource(), is("replication-orchestrator"));
        assertThat(rows(logs).get(5).getLevel(), is(Level.INFO));
        assertThat(rows(logs).getFirst().getTimestamp().toEpochMilli(), is(1L));
    }

    @Test
    void platform_log_level_only_moves_platform_info_events() throws Exception {
        var logs = logs(Level.DEBUG, 100);

        logs.consume(
            List.of(
                attempt(
                    0,
                    event(1, "info", "platform", "noise"),
                    event(2, "warn", "platform", "backoff"),
                    event(3, "error", "platform", "boom"),
                    event(4, "info", "source", "records")
                )
            )
        );

        assertThat(formatted(), contains("DEBUG [Airbyte][platform] noise", "WARN [Airbyte][platform] backoff", "ERROR [Airbyte][platform] boom", "INFO [Airbyte][source] records"));
        assertThat(rows(logs).getFirst().getLevel(), is(Level.INFO));
    }

    @Test
    void logs_each_event_once_when_the_log_grows() {
        var logs = logs(Level.INFO, 100);

        logs.consume(List.of(attempt(0, event(1, "info", "source", "a"), event(2, "info", "source", "b"))));
        logs.consume(List.of(attempt(0, event(1, "info", "source", "a"), event(2, "info", "source", "b"), event(3, "info", "source", "c"), event(4, "info", "source", "d"))));

        assertThat(formatted(), contains("INFO [Airbyte][source] a", "INFO [Airbyte][source] b", "INFO [Airbyte][source] c", "INFO [Airbyte][source] d"));
        assertThat(logs.count(), is(4));
    }

    @Test
    void legacy_log_lines_do_not_drop_the_first_new_line() throws Exception {
        var logs = logs(Level.INFO, 100);

        logs.consume(List.of(legacyAttempt(0, "a", "b")));
        logs.consume(List.of(legacyAttempt(0, "a", "b", "c", "d")));

        assertThat(formatted(), contains("INFO [Airbyte] a", "INFO [Airbyte] b", "INFO [Airbyte] c", "INFO [Airbyte] d"));
    }

    @Test
    void legacy_log_lines_keep_guessing_the_level() throws Exception {
        var logs = logs(Level.INFO, 100);
        var esc = "\u001b[0m";

        logs.consume(List.of(legacyAttempt(0, "ERROR" + esc + " failed", "WARN" + esc + " careful", "DEBUG" + esc + " detail", "TRACE" + esc + " finer", "plain")));

        assertThat(
            formatted(), contains(
                "ERROR [Airbyte] ERROR" + esc + " failed", "WARN [Airbyte] WARN" + esc + " careful", "DEBUG [Airbyte] DEBUG" + esc + " detail", "TRACE [Airbyte] TRACE" + esc + " finer",
                "INFO [Airbyte] plain"
            )
        );
        assertThat(rows(logs).getFirst().getTimestamp(), is(nullValue()));
        assertThat(rows(logs).getFirst().getLogSource(), is(nullValue()));
    }

    @Test
    void tracks_the_sent_count_per_attempt_number_not_per_position() throws Exception {
        var logs = logs(Level.INFO, 100);

        logs.consume(List.of(attempt(1, event(1, "info", "source", "second-1")), attempt(0, event(1, "info", "source", "first-1"))));
        logs.consume(List.of(attempt(0, event(1, "info", "source", "first-1"), event(2, "info", "source", "first-2")), attempt(1, event(1, "info", "source", "second-1"))));

        assertThat(formatted(), contains("INFO [Airbyte][source] second-1", "INFO [Airbyte][source] first-1", "INFO [Airbyte][source] first-2"));
        assertThat(rows(logs).stream().map(row -> row.getAttemptNumber()).toList(), contains(1, 0, 0));
    }

    @Test
    void stops_at_max_events_with_a_single_warning() {
        var logs = logs(Level.INFO, 2);

        logs.consume(List.of(attempt(0, event(1, "info", "source", "a"), event(2, "info", "source", "b"), event(3, "info", "source", "c"), event(4, "info", "source", "d"))));

        assertThat(logs.count(), is(2));
        assertThat(formatted().size(), is(3));
        assertThat(formatted().getLast(), containsString("maxLogEvents"));
    }

    @Test
    void zero_max_events_disables_the_logs() {
        var logs = logs(Level.INFO, 0);

        logs.consume(List.of(attempt(0, event(1, "info", "source", "a"))));

        assertThat(logs.isEnabled(), is(false));
        assertThat(logs.count(), is(0));
        assertThat(Files.exists(logs.file()), is(false));
        assertThat(formatted().size(), is(0));
    }

    @Test
    void sanitize_drops_stack_frames_and_keeps_the_exception_lines() {
        var message = """
            ERROR main i.a.Runner(run):60 Failed java.lang.IllegalStateException: Scheduled exceptional event.
            \tat io.airbyte.Internal.run(Internal.kt:11) ~[internal.jar:?]
            \tat io.airbyte.Other.run(Other.kt:22)
            Caused by: java.io.IOException: disk full
            \t... 4 more
            """;

        var sanitized = AirbyteLogs.sanitize(message);

        assertThat(
            sanitized, is(
                """
                    ERROR main i.a.Runner(run):60 Failed java.lang.IllegalStateException: Scheduled exceptional event.
                    Caused by: java.io.IOException: disk full
                    \t... stack trace omitted"""
            )
        );
    }

    @Test
    void sanitize_drops_events_made_of_a_single_frame_and_keeps_regular_messages() {
        assertThat(AirbyteLogs.sanitize("\tat io.airbyte.Internal.run(Internal.kt:11)"), is(nullValue()));
        assertThat(AirbyteLogs.sanitize(null), is(nullValue()));
        assertThat(AirbyteLogs.sanitize("\n----- END CHECK -----\n\n"), is("\n----- END CHECK -----\n\n"));
        assertThat(AirbyteLogs.sanitize("Stream completed: null.products"), is("Stream completed: null.products"));
    }
}
