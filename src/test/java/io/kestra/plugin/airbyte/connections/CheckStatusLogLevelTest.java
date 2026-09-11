package io.kestra.plugin.airbyte.connections;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.models.property.Property;
import io.kestra.core.queues.QueueFactoryInterface;
import io.kestra.core.queues.QueueInterface;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
@WireMockTest(httpPort = 18082)
class CheckStatusLogLevelTest {
    private static final String ANSI = "\u001b[";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    @Named(QueueFactoryInterface.WORKERTASKLOG_NAMED)
    private QueueInterface<LogEntry> logQueue;

    @Test
    void keeps_warn_logs_as_warn(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        String infoLine = "2024-01-01 00:00:00 INFO" + ANSI + "0m info line";
        String warnLine = "2024-01-01 00:00:00 WARN" + ANSI + "0m warn line";
        String errorLine = "2024-01-01 00:00:00 ERROR" + ANSI + "0m error line";
        String debugLine = "2024-01-01 00:00:00 DEBUG" + ANSI + "0m debug line";
        String traceLine = "2024-01-01 00:00:00 TRACE" + ANSI + "0m trace line";

        stubFor(
            post(urlPathMatching("/api/v1/jobs/get/?"))
                .withRequestBody(matchingJsonPath("$.id"))
                .willReturn(
                    okJson(
                        MAPPER.writeValueAsString(
                            Map.of(
                                "job", Map.of("id", 970, "status", "succeeded"),
                                "attempts", List.of(
                                    Map.of(
                                        "attempt", Map.of("id", 0, "status", "succeeded"),
                                        "logs", Map.of(
                                            "logLines",
                                            List.of(infoLine, warnLine, errorLine, debugLine, traceLine)
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
        );

        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        Runnable cancel = logQueue.receive(either ->
        {
            if (either.isLeft()) {
                logs.add(either.getLeft());
            }
        });

        try {
            CheckStatus task = CheckStatus.builder()
                .id(IdUtils.create())
                .type(CheckStatus.class.getName())
                .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
                .jobId(Property.ofValue("970"))
                .build();

            var out = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

            assertThat(out, notNullValue());
            assertThat(out.getFinalJobStatus(), is("succeeded"));

            assertThat(levelFor(logs, "info line"), is(Level.INFO));
            assertThat(levelFor(logs, "warn line"), is(Level.WARN));
            assertThat(levelFor(logs, "error line"), is(Level.ERROR));
            assertThat(levelFor(logs, "debug line"), is(Level.DEBUG));
            assertThat(levelFor(logs, "trace line"), is(Level.TRACE));
        } finally {
            cancel.run();
        }
    }

    private static Level levelFor(List<LogEntry> logs, String messageFragment) {
        List<LogEntry> matching = TestsUtils.awaitLogs(
            logs,
            logEntry -> logEntry.getMessage() != null && logEntry.getMessage().contains(messageFragment),
            1
        );

        assertThat("No log event contained: " + messageFragment, matching, notNullValue());
        assertThat(matching.isEmpty(), is(false));
        return matching.getFirst().getLevel();
    }
}
