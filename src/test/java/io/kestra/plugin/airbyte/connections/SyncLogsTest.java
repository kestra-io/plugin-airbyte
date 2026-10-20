package io.kestra.plugin.airbyte.connections;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTaskException;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.airbyte.models.LogRow;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class SyncLogsTest {
    private static final String CONNECTION_ID = "e3b1ce92-547c-436f-b1e8-23b6936c12cd";

    @Inject
    private RunContextFactory runContextFactory;

    private static Sync.SyncBuilder<?, ?> sync(WireMockRuntimeInfo wiremock) {
        stubFor(post(urlPathMatching("/api/v1/jobs/list/?")).willReturn(okJson("{\"jobs\":[]}")));
        stubFor(post(urlPathMatching("/api/v1/connections/sync/?")).willReturn(okJson("{\"job\":{\"id\":20,\"status\":\"running\"},\"attempts\":[]}")));

        return Sync.builder()
            .id(IdUtils.create())
            .type(Sync.class.getName())
            .url(Property.ofValue(wiremock.getHttpBaseUrl()))
            .token(Property.ofValue("token"))
            .connectionId(Property.ofValue(CONNECTION_ID))
            .pollFrequency(Property.ofValue(Duration.ofMillis(20)));
    }

    private static void stubStatus(String status, String fixture) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/jobs/get_light")).willReturn(okJson("{\"job\":{\"id\":20,\"configType\":\"sync\",\"status\":\"" + status + "\"}}")));
        stubFor(post(urlPathMatching("/api/v1/jobs/get/?")).willReturn(okJson(Files.readString(Path.of("src/test/resources/airbyte-logs/" + fixture)))));
    }

    @Test
    void exposes_the_airbyte_logs_file(WireMockRuntimeInfo wiremock) throws Exception {
        var task = sync(wiremock).platformLogLevel(Property.ofValue(Level.DEBUG)).build();
        stubStatus("succeeded", "job-succeeded.json");

        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var out = task.run(runContext);

        assertThat(out.getAirbyteLogs(), is(notNullValue()));
        try (var stream = runContext.storage().getFile(out.getAirbyteLogs())) {
            assertThat(FileSerde.readAll(stream, LogRow.class).collectList().block().size(), is(8));
        }
    }

    @Test
    void keeps_the_airbyte_logs_file_when_the_job_fails(WireMockRuntimeInfo wiremock) throws Exception {
        var task = sync(wiremock).build();
        stubStatus("failed", "job-failed.json");

        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var thrown = assertThrows(RunnableTaskException.class, () -> task.run(runContext));

        var output = (Sync.Output) thrown.getOutput();
        assertThat(output.getAirbyteLogs(), is(notNullValue()));
        try (var stream = runContext.storage().getFile(output.getAirbyteLogs())) {
            assertThat(FileSerde.readAll(stream, LogRow.class).collectList().block().size(), is(8));
        }
    }

    @Test
    void passes_max_log_events_to_the_wait(WireMockRuntimeInfo wiremock) throws Exception {
        var task = sync(wiremock).maxLogEvents(Property.ofValue(0)).build();
        stubStatus("succeeded", "job-succeeded.json");

        var out = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

        assertThat(out.getAirbyteLogs(), is(nullValue()));
    }
}
