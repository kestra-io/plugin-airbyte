package io.kestra.plugin.airbyte.endpoints;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import io.kestra.core.models.executions.LogEntry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class LogsTest {
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private static LogEntry log(int second, Level level, String message, Integer attempt) {
        return LogEntry.builder()
            .namespace("ns")
            .flowId("flow")
            .timestamp(BASE.plusSeconds(second))
            .level(level)
            .message(message)
            .attemptNumber(attempt)
            .build();
    }

    private static StubEndpointContext context() {
        return new StubEndpointContext().logs(
            List.of(
                log(3, Level.ERROR, "third", 1),
                log(1, Level.INFO, "first", 0),
                log(2, Level.DEBUG, "debug", 0),
                log(4, Level.WARN, "fourth", 1)
            )
        );
    }

    @Test
    void returns_logs_oldest_first_at_info_level_by_default() throws Exception {
        var stub = context();
        var json = StubEndpointContext.json(new Logs().handle(stub));

        assertThat(stub.requestedLevel(), is(Level.INFO));
        assertThat(json.get("total").asInt(), is(3));
        assertThat(json.get("results").get(0).get("message").asText(), is("first"));
        assertThat(json.get("results").get(0).get("level").asText(), is("INFO"));
        assertThat(json.get("results").get(2).get("message").asText(), is("fourth"));
    }

    @Test
    void filters_by_minimum_level_case_insensitively() throws Exception {
        var stub = context().param("level", "debug");
        var json = StubEndpointContext.json(new Logs().handle(stub));

        assertThat(stub.requestedLevel(), is(Level.DEBUG));
        assertThat(json.get("total").asInt(), is(4));

        var errors = StubEndpointContext.json(new Logs().handle(context().param("level", "ERROR")));
        assertThat(errors.get("total").asInt(), is(1));
        assertThat(errors.get("results").get(0).get("message").asText(), is("third"));
    }

    @Test
    void unknown_level_falls_back_to_info() throws Exception {
        var stub = context().param("level", "LOUD");
        StubEndpointContext.json(new Logs().handle(stub));

        assertThat(stub.requestedLevel(), is(Level.INFO));
    }

    @Test
    void filters_by_attempt() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(context().param("attempt", "1")));

        assertThat(json.get("total").asInt(), is(2));
        assertThat(json.get("results").get(0).get("message").asText(), is("third"));
        assertThat(json.get("results").get(0).get("attemptNumber").asInt(), is(1));
    }

    @Test
    void non_numeric_attempt_is_ignored() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(context().param("attempt", "x")));

        assertThat(json.get("total").asInt(), is(3));
    }

    @Test
    void combines_filters_and_paginates() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(context().param("level", "TRACE").param("size", "2").param("page", "2")));

        assertThat(json.get("total").asInt(), is(4));
        assertThat(json.get("results").size(), is(2));
        assertThat(json.get("results").get(0).get("message").asText(), is("third"));
    }

    @Test
    void no_logs_returns_an_empty_page() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(new StubEndpointContext()));

        assertThat(json.get("total").asInt(), is(0));
        assertThat(json.get("results").size(), is(0));
    }
}
