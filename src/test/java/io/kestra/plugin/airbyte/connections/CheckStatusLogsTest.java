package io.kestra.plugin.airbyte.connections;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTaskException;
import io.kestra.core.queues.DispatchQueueInterface;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.airbyte.models.AttemptRow;
import io.kestra.plugin.airbyte.models.LogRow;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class CheckStatusLogsTest {
    private static final String LIGHT = "/api/v1/jobs/get_light";
    private static final String GET = "/api/v1/jobs/get/";
    private static final String ATTEMPT = "/api/v1/attempt/get_for_job";

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private DispatchQueueInterface<LogEntry> logQueue;

    private final List<LogEntry> logs = new CopyOnWriteArrayList<>();

    private static String event(String level, String source, String message) {
        return json(Map.of("timestamp", 1700000000000L, "level", level, "logSource", source, "message", message, "caller", Map.of("className", "io.airbyte.Internal")));
    }

    private static String json(Object value) {
        try {
            return JacksonMapper.ofJson().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String attempt(int id, String attemptStatus, String... events) {
        return """
            { "attempt": { "id": %d, "status": "%s", "createdAt": 1700000010, "endedAt": 1700000070 },
              "logType": "structured",
              "logs": { "version": 1, "logLines": [], "events": [%s] } }
            """.formatted(id, attemptStatus, String.join(",", events));
    }

    private static String job(String status, String... attempts) {
        return """
            { "job": { "id": 55, "configType": "sync", "status": "%s", "createdAt": 1700000000 },
              "attempts": [%s] }
            """.formatted(status, String.join(",", attempts));
    }

    private static void stubLight(String... statuses) {
        for (var index = 0; index < statuses.length; index++) {
            var mapping = post(urlPathEqualTo(LIGHT))
                .inScenario("light")
                .whenScenarioStateIs(index == 0 ? Scenario.STARTED : "light-" + index)
                .willReturn(okJson("{\"job\":{\"id\":55,\"configType\":\"sync\",\"status\":\"" + statuses[index] + "\"}}"));
            if (index < statuses.length - 1) {
                mapping.willSetStateTo("light-" + (index + 1));
            }
            stubFor(mapping);
        }
    }

    private static void stubLightUpdatedAt(String[] statuses, long[] updatedAts) {
        for (var index = 0; index < statuses.length; index++) {
            var mapping = post(urlPathEqualTo(LIGHT))
                .inScenario("light")
                .whenScenarioStateIs(index == 0 ? Scenario.STARTED : "light-" + index)
                .willReturn(okJson("{\"job\":{\"id\":55,\"configType\":\"sync\",\"status\":\"" + statuses[index] + "\",\"updatedAt\":" + updatedAts[index] + "}}"));
            if (index < statuses.length - 1) {
                mapping.willSetStateTo("light-" + (index + 1));
            }
            stubFor(mapping);
        }
    }

    private static void stubGet(String... bodies) {
        for (var index = 0; index < bodies.length; index++) {
            var mapping = post(urlPathMatching("/api/v1/jobs/get/?"))
                .inScenario("get")
                .whenScenarioStateIs(index == 0 ? Scenario.STARTED : "get-" + index)
                .willReturn(okJson(bodies[index]));
            if (index < bodies.length - 1) {
                mapping.willSetStateTo("get-" + (index + 1));
            }
            stubFor(mapping);
        }
    }

    private CheckStatus.CheckStatusBuilder<?, ?> checkStatus(WireMockRuntimeInfo wiremock) {
        logQueue.addListener(logs::add);
        return CheckStatus.builder()
            .id(IdUtils.create())
            .type(CheckStatus.class.getName())
            .url(Property.ofValue(wiremock.getHttpBaseUrl()))
            .token(Property.ofValue("token"))
            .jobId(Property.ofValue("55"))
            .pollFrequency(Property.ofValue(Duration.ofMillis(20)))
            .logFrequency(Property.ofValue(Duration.ZERO));
    }

    private RunContext runContext(CheckStatus task) {
        return TestsUtils.mockRunContext(runContextFactory, task, Map.of());
    }

    private static List<LogRow> rows(RunContext runContext, URI uri) throws Exception {
        try (var stream = runContext.storage().getFile(uri)) {
            return FileSerde.readAll(stream, LogRow.class).collectList().block();
        }
    }

    private List<LogEntry> logsContaining(String fragment, int expected) {
        return TestsUtils.awaitLogs(logs, log -> log.getMessage() != null && log.getMessage().contains(fragment), expected);
    }

    @Test
    void polls_the_light_status_and_streams_each_event_once_with_its_source_and_level(WireMockRuntimeInfo wiremock) throws Exception {
        var token = IdUtils.create();
        stubLight("running", "running", "succeeded");
        stubGet(
            job("running", attempt(0, "running", event("info", "platform", token + " a"), event("warn", "source", token + " b"))),
            job(
                "succeeded", attempt(
                    0, "succeeded", event("info", "platform", token + " a"), event("warn", "source", token + " b"),
                    event("error", "destination", token + " c"), event("info", "replication-orchestrator", token + " d")
                )
            )
        );
        stubFor(
            post(urlPathEqualTo(ATTEMPT))
                .withRequestBody(matchingJsonPath("$.jobId", equalTo("55")))
                .withRequestBody(matchingJsonPath("$.attemptNumber", equalTo("0")))
                .willReturn(
                    okJson(
                        attempt(
                            0, "running", event("info", "platform", token + " a"), event("warn", "source", token + " b"),
                            event("error", "destination", token + " c")
                        )
                    )
                )
        );

        var task = checkStatus(wiremock).build();
        var runContext = runContext(task);
        var out = task.run(runContext);

        assertThat(logsContaining(token + " a", 1).getFirst().getMessage(), is("[platform] " + token + " a"));
        assertThat(logsContaining(token + " b", 1).getFirst().getLevel(), is(Level.WARN));
        assertThat(logsContaining(token + " c", 1).getFirst().getLevel(), is(Level.ERROR));
        assertThat(logsContaining(token + " c", 1).getFirst().getMessage(), is("[destination] " + token + " c"));
        assertThat(logsContaining(token + " d", 1).getFirst().getMessage(), is("[orchestrator] " + token + " d"));
        assertThat(logs.stream().filter(log -> log.getMessage() != null && log.getMessage().contains(token)).count(), is(4L));

        verify(3, postRequestedFor(urlPathEqualTo(LIGHT)));
        verify(1, postRequestedFor(urlPathEqualTo(ATTEMPT)));
        verify(2, postRequestedFor(urlPathMatching("/api/v1/jobs/get/?")));

        assertThat(
            rows(runContext, out.getAirbyteLogs()).stream().map(LogRow::getMessage).toList(),
            contains(token + " a", token + " b", token + " c", token + " d")
        );
    }

    @Test
    void falls_back_to_jobs_get_when_get_light_is_missing_and_sticks_with_it(WireMockRuntimeInfo wiremock) throws Exception {
        var token = IdUtils.create();
        stubGet(
            job("running", attempt(0, "running", event("info", "source", token + " a"))),
            job("running", attempt(0, "running", event("info", "source", token + " a"), event("info", "source", token + " b"))),
            job("succeeded", attempt(0, "succeeded", event("info", "source", token + " a"), event("info", "source", token + " b"), event("info", "source", token + " c")))
        );

        var task = checkStatus(wiremock).build();
        var out = task.run(runContext(task));

        verify(1, postRequestedFor(urlPathEqualTo(LIGHT)));
        verify(0, postRequestedFor(urlPathEqualTo(ATTEMPT)));
        verify(3, postRequestedFor(urlPathMatching("/api/v1/jobs/get/?")));
        assertThat(out.getFinalJobStatus(), is("succeeded"));
        assertThat(logsContaining(token + " a", 1).size(), is(1));
        assertThat(logsContaining(token + " b", 1).size(), is(1));
        assertThat(logsContaining(token + " c", 1).size(), is(1));
    }

    @Test
    void fetches_the_live_logs_at_most_once_per_log_frequency(WireMockRuntimeInfo wiremock) throws Exception {
        stubLight("running", "running", "running", "running", "succeeded");
        stubGet(job("running", attempt(0, "running", event("info", "source", "a"))), job("succeeded", attempt(0, "succeeded", event("info", "source", "a"))));

        var task = checkStatus(wiremock).logFrequency(Property.ofValue(Duration.ofHours(1))).build();
        task.run(runContext(task));

        verify(5, postRequestedFor(urlPathEqualTo(LIGHT)));
        verify(0, postRequestedFor(urlPathEqualTo(ATTEMPT)));
        verify(2, postRequestedFor(urlPathMatching("/api/v1/jobs/get/?")));
    }

    @Test
    void discovers_the_new_attempt_after_the_current_one_ended(WireMockRuntimeInfo wiremock) throws Exception {
        var token = IdUtils.create();
        stubLight("running", "running", "running", "succeeded");
        stubGet(
            job("running", attempt(0, "failed", event("info", "source", token + " first"))),
            job("running", attempt(0, "failed", event("info", "source", token + " first")), attempt(1, "running", event("info", "source", token + " second"))),
            job(
                "succeeded", attempt(0, "failed", event("info", "source", token + " first")),
                attempt(1, "succeeded", event("info", "source", token + " second"), event("info", "source", token + " third"))
            )
        );
        stubFor(
            post(urlPathEqualTo(ATTEMPT))
                .withRequestBody(matchingJsonPath("$.attemptNumber", equalTo("1")))
                .willReturn(okJson(attempt(1, "running", event("info", "source", token + " second"), event("info", "source", token + " third"))))
        );

        var task = checkStatus(wiremock).build();
        var runContext = runContext(task);
        var out = task.run(runContext);

        verify(1, postRequestedFor(urlPathEqualTo(ATTEMPT)).withRequestBody(matchingJsonPath("$.attemptNumber", equalTo("1"))));
        assertThat(
            rows(runContext, out.getAirbyteLogs()).stream().map(row -> row.getAttemptNumber() + ":" + row.getMessage()).toList(),
            contains("0:" + token + " first", "1:" + token + " second", "1:" + token + " third")
        );
        assertThat(logsContaining("Previous attempt failed", 1).size(), is(1));
    }

    @Test
    void skips_the_full_fetch_during_backoff_while_the_job_has_not_changed(WireMockRuntimeInfo wiremock) throws Exception {
        stubLightUpdatedAt(new String[]{"running", "incomplete", "incomplete", "incomplete", "incomplete", "succeeded"}, new long[]{1, 1, 1, 1, 1, 1});
        stubGet(
            job("running", attempt(0, "failed", event("info", "source", "a"))),
            job("succeeded", attempt(0, "failed", event("info", "source", "a")), attempt(1, "succeeded", event("info", "source", "b")))
        );

        var task = checkStatus(wiremock).build();
        task.run(runContext(task));

        verify(6, postRequestedFor(urlPathEqualTo(LIGHT)));
        verify(0, postRequestedFor(urlPathEqualTo(ATTEMPT)));
        verify(2, postRequestedFor(urlPathMatching("/api/v1/jobs/get/?")));
    }

    @Test
    void fetches_the_job_again_when_its_update_time_changes(WireMockRuntimeInfo wiremock) throws Exception {
        var token = IdUtils.create();
        stubLightUpdatedAt(new String[]{"incomplete", "incomplete", "incomplete", "incomplete", "succeeded"}, new long[]{1, 1, 2, 2, 2});
        stubGet(
            job("incomplete", attempt(0, "failed", event("info", "source", token + " first"))),
            job("incomplete", attempt(0, "failed", event("info", "source", token + " first")), attempt(1, "failed", event("info", "source", token + " second"))),
            job("succeeded", attempt(0, "failed", event("info", "source", token + " first")), attempt(1, "failed", event("info", "source", token + " second")))
        );

        var task = checkStatus(wiremock).build();
        task.run(runContext(task));

        verify(3, postRequestedFor(urlPathMatching("/api/v1/jobs/get/?")));
        verify(0, postRequestedFor(urlPathEqualTo(ATTEMPT)));
        assertThat(logsContaining("Previous attempt failed", 1).size(), is(1));
        assertThat(logsContaining(token + " second", 1).size(), is(1));
    }

    @Test
    void a_failing_live_log_fetch_does_not_fail_the_task(WireMockRuntimeInfo wiremock) throws Exception {
        stubLight("running", "running", "succeeded");
        stubGet(job("running", attempt(0, "running", event("info", "source", "a"))), job("succeeded", attempt(0, "succeeded", event("info", "source", "a"), event("info", "source", "b"))));
        stubFor(post(urlPathEqualTo(ATTEMPT)).willReturn(serverError().withBody("{\"errorId\":\"x\"}")));

        var task = checkStatus(wiremock).build();
        var runContext = runContext(task);
        var out = task.run(runContext);

        assertThat(rows(runContext, out.getAirbyteLogs()).size(), is(2));
        assertThat(logsContaining("Unable to fetch the live Airbyte logs", 1).size(), is(1));
    }

    @Test
    void stores_the_sanitized_logs_of_every_attempt_in_attempt_order_when_the_job_fails(WireMockRuntimeInfo wiremock) throws Exception {
        stubLight("failed");
        stubFor(post(urlPathMatching("/api/v1/jobs/get/?")).willReturn(okJson(Files.readString(Path.of("src/test/resources/airbyte-logs/job-failed.json")))));

        var task = checkStatus(wiremock).build();
        var runContext = runContext(task);
        var thrown = assertThrows(RunnableTaskException.class, () -> task.run(runContext));

        var output = (CheckStatus.Output) thrown.getOutput();
        var rows = rows(runContext, output.getAirbyteLogs());

        // 10 raw events, 2 of them a lone stack frame
        assertThat(rows.size(), is(8));
        assertThat(rows.stream().map(LogRow::getAttemptNumber).toList(), contains(0, 0, 0, 0, 1, 1, 1, 1));
        var stackTrace = rows.stream().filter(row -> row.getMessage().contains("Destination Pipeline Completed")).findFirst().orElseThrow();
        assertThat(stackTrace.getMessage(), containsString("Failing Destination"));
        assertThat(stackTrace.getMessage(), endsWith(AirbyteLogs.STACK_TRACE_OMITTED));
        assertThat(rows.stream().anyMatch(row -> row.getMessage().contains("\tat io.airbyte")), is(false));
        assertThat(rows.stream().anyMatch(row -> row.getLevel() == Level.WARN), is(true));

        try (var stream = runContext.storage().getFile(output.getAttempts())) {
            var attempts = FileSerde.readAll(stream, AttemptRow.class).collectList().block();
            assertThat(attempts.stream().map(AttemptRow::getAttemptNumber).toList(), contains(0, 1));
            assertThat(attempts.stream().map(AttemptRow::getCreatedAt).toList(), contains(Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700000100)));
        }

        assertThat(logsContaining("Backing off", 1).getFirst().getLevel(), is(Level.WARN));
    }

    @Test
    void streams_a_structured_job_end_to_end(WireMockRuntimeInfo wiremock) throws Exception {
        stubLight("succeeded");
        stubFor(post(urlPathMatching("/api/v1/jobs/get/?")).willReturn(okJson(Files.readString(Path.of("src/test/resources/airbyte-logs/job-succeeded.json")))));

        var task = checkStatus(wiremock).build();
        var runContext = runContext(task);
        var out = task.run(runContext);

        var rows = rows(runContext, out.getAirbyteLogs());
        assertThat(rows.size(), is(8));
        assertThat(rows.stream().map(LogRow::getLogSource).distinct().sorted().toList(), contains("destination", "platform", "replication-orchestrator", "source"));
        assertThat(rows.stream().allMatch(row -> row.getTimestamp() != null && row.getAttemptNumber() == 0), is(true));
        assertThat(logsContaining("[destination] ", 1).isEmpty(), is(false));
        assertThat(logsContaining("[orchestrator] ", 1).isEmpty(), is(false));
    }

    @Test
    void caps_the_events_at_max_log_events(WireMockRuntimeInfo wiremock) throws Exception {
        stubLight("succeeded");
        stubFor(post(urlPathMatching("/api/v1/jobs/get/?")).willReturn(okJson(Files.readString(Path.of("src/test/resources/airbyte-logs/job-succeeded.json")))));

        var task = checkStatus(wiremock).maxLogEvents(Property.ofValue(3)).build();
        var runContext = runContext(task);
        var out = task.run(runContext);

        assertThat(rows(runContext, out.getAirbyteLogs()).size(), is(3));
        assertThat(logsContaining("maxLogEvents", 1).size(), is(1));
    }

    @Test
    void zero_max_log_events_skips_the_logs_and_the_file(WireMockRuntimeInfo wiremock) throws Exception {
        stubLight("running", "running", "succeeded");
        stubGet(job("succeeded", attempt(0, "succeeded", event("info", "source", "a"))));

        var task = checkStatus(wiremock).maxLogEvents(Property.ofValue(0)).build();
        var out = task.run(runContext(task));

        assertThat(out.getAirbyteLogs(), is(nullValue()));
        verify(0, postRequestedFor(urlPathEqualTo(ATTEMPT)));
        verify(1, postRequestedFor(urlPathMatching("/api/v1/jobs/get/?")));
    }

    @Test
    void platform_log_level_applies_to_platform_info_events(WireMockRuntimeInfo wiremock) throws Exception {
        var token = IdUtils.create();
        stubLight("succeeded");
        stubFor(
            post(urlPathMatching("/api/v1/jobs/get/?"))
                .willReturn(okJson(job("succeeded", attempt(0, "succeeded", event("info", "platform", token + " noise"), event("info", "source", token + " records")))))
        );

        var task = checkStatus(wiremock).platformLogLevel(Property.ofValue(Level.DEBUG)).build();
        var out = task.run(runContext(task));

        assertThat(logsContaining(token + " noise", 1).getFirst().getLevel(), is(Level.DEBUG));
        assertThat(logsContaining(token + " records", 1).getFirst().getLevel(), is(Level.INFO));
        assertThat(out.getAirbyteLogs(), is(notNullValue()));
    }

    @Test
    void negative_max_log_events_is_rejected(WireMockRuntimeInfo wiremock) {
        var task = checkStatus(wiremock).maxLogEvents(Property.ofValue(-1)).build();

        var thrown = assertThrows(IllegalArgumentException.class, () -> task.run(runContext(task)));

        assertThat(thrown.getMessage(), containsString("maxLogEvents"));
    }
}
