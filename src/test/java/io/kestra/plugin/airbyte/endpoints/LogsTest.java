package io.kestra.plugin.airbyte.endpoints;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    private static StubEndpointContext airbyteTaskRun() {
        return new StubEndpointContext().output("jobId", 55L);
    }

    private static StubEndpointContext context() {
        return airbyteTaskRun().logs(
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
    void combines_filters_and_paginates() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(context().param("level", "TRACE").param("size", "2").param("page", "2")));

        assertThat(json.get("total").asInt(), is(4));
        assertThat(json.get("results").size(), is(2));
        assertThat(json.get("results").get(0).get("message").asText(), is("third"));
    }

    @Test
    void a_task_run_without_the_airbyte_marker_gets_an_empty_kestra_page() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(new StubEndpointContext().output("exitCode", 0).logs(List.of(log(1, Level.INFO, "secret", 0)))));

        assertThat(json.get("origin").asText(), is("kestra"));
        assertThat(json.get("total").asInt(), is(0));
        assertThat(json.get("results").size(), is(0));
    }

    @Test
    void a_task_run_without_outputs_gets_an_empty_kestra_page() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(new StubEndpointContext().logs(List.of(log(1, Level.INFO, "secret", 0)))));

        assertThat(json.get("origin").asText(), is("kestra"));
        assertThat(json.get("total").asInt(), is(0));
    }

    @Test
    void a_check_status_task_run_without_airbyte_logs_falls_back_to_the_kestra_logs() throws Exception {
        var stub = new StubEndpointContext().output("job", Map.of("attemptCount", 1)).logs(List.of(log(1, Level.INFO, "first", 0)));
        var json = StubEndpointContext.json(new Logs().handle(stub));

        assertThat(json.get("origin").asText(), is("kestra"));
        assertThat(json.get("total").asInt(), is(1));
    }

    @Test
    void an_attempt_sub_task_run_with_the_job_id_falls_back_to_its_kestra_logs() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(airbyteTaskRun().output("jobId", 55L).logs(List.of(log(1, Level.ERROR, "[source/config_error] boom", 0)))));

        assertThat(json.get("origin").asText(), is("kestra"));
        assertThat(json.get("results").get(0).get("message").asText(), is("[source/config_error] boom"));
    }

    @Test
    void no_logs_returns_an_empty_page() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(airbyteTaskRun()));

        assertThat(json.get("total").asInt(), is(0));
        assertThat(json.get("results").size(), is(0));
    }
}
