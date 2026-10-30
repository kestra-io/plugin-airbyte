package io.kestra.plugin.airbyte.endpoints;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.airbyte.models.LogRow;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class LogsAirbyteTest {
    private static final String URI = "kestra:///ns/flow/executions/exec/tasks/sync/taskrun/airbyte-logs.ion";
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private static LogRow row(int attempt, int second, Level level, String source, String message) {
        return LogRow.builder().attemptNumber(attempt).timestamp(BASE.plusSeconds(second)).level(level).logSource(source).message(message).build();
    }

    private static StubEndpointContext context(LogRow... rows) throws Exception {
        var output = new ByteArrayOutputStream();
        for (var row : rows) {
            FileSerde.write(output, row);
        }
        return new StubEndpointContext().output("airbyteLogs", URI).file(URI, output.toByteArray());
    }

    private static StubEndpointContext sample() throws Exception {
        return context(
            row(0, 1, Level.INFO, "platform", "stage build"),
            row(0, 2, Level.INFO, "source", "reading users"),
            row(0, 3, Level.ERROR, "destination", "Failing destination"),
            row(0, 4, Level.WARN, "replication-orchestrator", "Backing off"),
            row(1, 5, Level.INFO, "source", "Reading USERS again"),
            row(1, 6, Level.DEBUG, "destination", "debug detail")
        );
    }

    private static List<String> messages(JsonNode json) {
        return json.get("results").findValuesAsText("message");
    }

    @Test
    void serves_the_airbyte_events_oldest_first_at_info_level_by_default() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(sample()));

        assertThat(json.get("origin").asText(), is("airbyte"));
        assertThat(json.get("total").asInt(), is(5));
        assertThat(messages(json), contains("stage build", "reading users", "Failing destination", "Backing off", "Reading USERS again"));
        var first = json.get("results").get(0);
        assertThat(first.get("attempt").asInt(), is(0));
        assertThat(first.get("source").asText(), is("platform"));
        assertThat(first.get("level").asText(), is("INFO"));
        assertThat(json.get("results").get(3).get("source").asText(), is("orchestrator"));
    }

    @Test
    void filters_by_attempt() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(sample().param("attempt", "1").param("level", "TRACE")));

        assertThat(messages(json), contains("Reading USERS again", "debug detail"));
    }

    @Test
    void filters_by_one_or_more_sources_repeated_or_comma_separated() throws Exception {
        var single = StubEndpointContext.json(new Logs().handle(sample().param("source", "source")));
        assertThat(messages(single), contains("reading users", "Reading USERS again"));

        var repeated = StubEndpointContext.json(new Logs().handle(sample().params("source", "destination", "platform")));
        assertThat(messages(repeated), contains("stage build", "Failing destination"));

        var commaSeparated = StubEndpointContext.json(new Logs().handle(sample().param("source", "Destination, orchestrator")));
        assertThat(messages(commaSeparated), contains("Failing destination", "Backing off"));

        var alias = StubEndpointContext.json(new Logs().handle(sample().param("source", "replication-orchestrator")));
        assertThat(messages(alias), contains("Backing off"));
    }

    @Test
    void filters_by_minimum_level_and_message_substring_case_insensitively() throws Exception {
        var warnings = StubEndpointContext.json(new Logs().handle(sample().param("level", "warn")));
        assertThat(messages(warnings), contains("Failing destination", "Backing off"));

        var query = StubEndpointContext.json(new Logs().handle(sample().param("q", "users")));
        assertThat(messages(query), contains("reading users", "Reading USERS again"));
    }

    @Test
    void combines_the_filters_and_paginates() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(sample().param("q", "users").param("attempt", "0").param("size", "1").param("page", "1")));

        assertThat(json.get("total").asInt(), is(1));
        assertThat(messages(json), contains("reading users"));

        var second = StubEndpointContext.json(new Logs().handle(sample().param("level", "TRACE").param("size", "4").param("page", "2")));
        assertThat(second.get("total").asInt(), is(6));
        assertThat(messages(second), contains("Reading USERS again", "debug detail"));
    }

    @Test
    void ignores_invalid_filter_values() throws Exception {
        var json = StubEndpointContext.json(
            new Logs().handle(sample().param("attempt", "first").param("source", "nope").param("level", "LOUD").param("page", "x").param("size", "-4"))
        );

        assertThat(json.get("total").asInt(), is(5));
        assertThat(json.get("page").asInt(), is(1));
        assertThat(json.get("size").asInt(), is(50));
    }

    @Test
    void serves_the_kestra_logs_when_the_task_run_has_no_airbyte_logs() throws Exception {
        var stub = new StubEndpointContext().output("jobId", 55L).logs(
            List.of(
                LogEntry.builder().namespace("ns").flowId("flow").timestamp(BASE.plusSeconds(2)).level(Level.WARN).message("Attempt 0 failed").build(),
                LogEntry.builder().namespace("ns").flowId("flow").timestamp(BASE.plusSeconds(1)).level(Level.INFO).message("Job started").build()
            )
        ).param("attempt", "1").param("source", "source");
        var json = StubEndpointContext.json(new Logs().handle(stub));

        assertThat(json.get("origin").asText(), is("kestra"));
        assertThat(messages(json), contains("Job started", "Attempt 0 failed"));
        assertThat(json.get("results").get(0).get("attempt"), is(nullValue()));
        assertThat(json.get("results").get(0).get("source"), is(nullValue()));
    }

    @Test
    void the_query_also_applies_to_the_kestra_logs() throws Exception {
        var stub = new StubEndpointContext().output("jobId", 55L).logs(
            List.of(
                LogEntry.builder().namespace("ns").flowId("flow").timestamp(BASE).level(Level.INFO).message("Job started").build(),
                LogEntry.builder().namespace("ns").flowId("flow").timestamp(BASE.plusSeconds(1)).level(Level.INFO).message("Job done").build()
            )
        ).param("q", "DONE");

        assertThat(messages(StubEndpointContext.json(new Logs().handle(stub))), contains("Job done"));
    }

    @Test
    void falls_back_to_the_kestra_logs_when_the_stored_file_is_missing() throws Exception {
        var stub = new StubEndpointContext().output("jobId", 55L).output("airbyteLogs", URI).logs(
            List.of(LogEntry.builder().namespace("ns").flowId("flow").timestamp(BASE).level(Level.INFO).message("Job started").build())
        );
        var json = StubEndpointContext.json(new Logs().handle(stub));

        assertThat(json.get("origin").asText(), is("kestra"));
        assertThat(messages(json), contains("Job started"));
    }

    @Test
    void counts_every_match_in_total_and_returns_an_empty_window_past_the_end() throws Exception {
        var json = StubEndpointContext.json(new Logs().handle(sample().param("size", "2").param("page", "9")));

        assertThat(json.get("total").asInt(), is(5));
        assertThat(json.get("results").size(), is(0));
    }
}
