package io.kestra.plugin.airbyte.connections;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class SyncCheckpointTest {
    private static final String CONNECTION_ID = "e3b1ce92-547c-436f-b1e8-23b6936c12cd";

    @Inject
    private RunContextFactory runContextFactory;

    private static Sync task(WireMockRuntimeInfo wiremock) {
        return Sync.builder()
            .id(IdUtils.create())
            .type(Sync.class.getName())
            .url(Property.ofValue(wiremock.getHttpBaseUrl()))
            .connectionId(Property.ofValue(CONNECTION_ID))
            .wait(Property.ofValue(false))
            .build();
    }

    private static String job(long id, String status) {
        return """
            { "job": { "id": %d, "configType": "sync", "configId": "%s", "status": "%s" }, "attempts": [] }
            """.formatted(id, CONNECTION_ID, status);
    }

    private static void stubCheckpointedJob(String body) {
        stubFor(post(urlPathMatching("/api/v1/jobs/get/?")).withRequestBody(matchingJsonPath("$.id", equalTo("123"))).willReturn(okJson(body)));
    }

    private static void stubNewSync() {
        stubFor(post(urlPathMatching("/api/v1/jobs/list/?")).willReturn(okJson("{ \"jobs\": [], \"totalJobCount\": 0 }")));
        stubFor(post(urlPathMatching("/api/v1/connections/sync/?")).willReturn(okJson(job(124, "running"))));
    }

    @Test
    void resumesTheCheckpointedJobInsteadOfTriggering(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        runContext.checkpoints().set(Sync.CHECKPOINT_KEY, "123");
        stubCheckpointedJob(job(123, "running"));

        var output = task.run(runContext);

        assertThat(output.getJobId(), is(123L));
        assertThat(output.getAdopted(), is(false));
        verify(0, postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void triggersAndOverwritesTheCheckpointWhenTheJobFailed(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        runContext.checkpoints().set(Sync.CHECKPOINT_KEY, "123");
        stubCheckpointedJob(job(123, "failed"));
        stubNewSync();

        var output = task.run(runContext);

        assertThat(output.getJobId(), is(124L));
        assertThat(runContext.checkpoints().get(Sync.CHECKPOINT_KEY), is(Optional.of("124")));
    }

    @Test
    void ignoresTheCheckpointedJobOfAnotherConnection(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        runContext.checkpoints().set(Sync.CHECKPOINT_KEY, "123");
        stubCheckpointedJob("""
            { "job": { "id": 123, "configType": "sync", "configId": "another-connection", "status": "running" }, "attempts": [] }
            """);
        stubNewSync();

        assertThat(task.run(runContext).getJobId(), is(124L));
    }

    @Test
    void triggersWhenTheCheckpointedJobNoLongerExists(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        runContext.checkpoints().set(Sync.CHECKPOINT_KEY, "123");
        stubFor(post(urlPathMatching("/api/v1/jobs/get/?")).willReturn(aResponse().withStatus(404)));
        stubNewSync();

        assertThat(task.run(runContext).getJobId(), is(124L));
    }

    @Test
    void failsWithoutTriggeringWhenTheCheckpointedJobCannotBeRead(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        runContext.checkpoints().set(Sync.CHECKPOINT_KEY, "123");
        stubFor(post(urlPathMatching("/api/v1/jobs/get/?")).willReturn(aResponse().withStatus(403)));
        stubNewSync();

        assertThrows(RuntimeException.class, () -> task.run(runContext));
        verify(0, postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void killCancelsTheJobOnce(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        stubNewSync();
        stubFor(post(urlPathMatching("/api/v1/jobs/cancel/?")).willReturn(okJson(job(124, "cancelled"))));

        task.run(runContext);
        task.kill();
        task.kill();

        verify(1, postRequestedFor(urlPathMatching("/api/v1/jobs/cancel/?")).withRequestBody(matchingJsonPath("$.id", equalTo("124"))));
    }

    @Test
    void killBeforeTriggerStartsNoSync(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        stubNewSync();

        task.kill();

        assertThrows(IllegalStateException.class, () -> task.run(runContext));
        verify(0, postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }
}
