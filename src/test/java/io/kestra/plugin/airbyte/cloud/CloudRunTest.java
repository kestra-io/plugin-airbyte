package io.kestra.plugin.airbyte.cloud;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.event.Level;

import com.airbyte.api.Airbyte;
import com.airbyte.api.models.shared.Security;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.assets.AssetsDeclaration;
import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.AssetFailureBehavior;
import io.kestra.core.models.tasks.RunnableTaskException;
import io.kestra.core.queues.DispatchQueueInterface;
import io.kestra.core.runners.AssetEmitter;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.airbyte.cloud.jobs.AbstractTrigger;
import io.kestra.plugin.airbyte.cloud.jobs.Sync;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class CloudRunTest {
    private static final String CONNECTION_ID = "e3b1ce92-547c-436f-b1e8-23b6936c12cd";

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private DispatchQueueInterface<LogEntry> logQueue;

    // The SDK takes a server URL, and the protected client factories are reachable from this package, so no production seam is needed.
    private static Sync task(WireMockRuntimeInfo wiremock) throws Exception {
        return task(wiremock, null);
    }

    private static Sync task(WireMockRuntimeInfo wiremock, AssetFailureBehavior behavior) throws Exception {
        var task = Mockito.spy(
            Sync.builder()
                .id(IdUtils.create())
                .type(Sync.class.getName())
                .connectionId(Property.ofValue(CONNECTION_ID))
                .assets(new AssetsDeclaration(null, null, null, behavior == null ? null : Property.ofValue(behavior)))
                .build()
        );
        var client = Airbyte.builder().serverURL(wiremock.getHttpBaseUrl() + "/v1").security(new Security().withBearerAuth("token")).build();
        Mockito.doReturn(client).when(task).client(Mockito.any());
        Mockito.doReturn(client).when(task).snapshotClient(Mockito.any());
        return task;
    }

    private static String job(long id, String status) {
        return """
            {
              "jobId": %d, "status": "%s", "jobType": "sync", "connectionId": "%s",
              "startTime": "2024-01-15T10:00:00Z", "lastUpdatedAt": "2024-01-15T10:01:00Z",
              "duration": "PT1M", "bytesSynced": 1000, "rowsSynced": 20
            }
            """.formatted(id, status, CONNECTION_ID);
    }

    private void stubJob(long id, String status) {
        stubFor(post(urlPathEqualTo("/v1/jobs")).willReturn(okJson(job(id, "pending"))));
        stubFor(get(urlPathEqualTo("/v1/jobs/" + id)).willReturn(okJson(job(id, status))));
    }

    private void stubSnapshot() {
        stubFor(
            get(urlPathEqualTo("/v1/connections/" + CONNECTION_ID))
                .willReturn(okJson("""
                    {
                      "connectionId": "%s", "name": "pg to snowflake", "workspaceId": "ws-1", "sourceId": "src-1", "destinationId": "dst-1",
                      "status": "active", "schedule": { "scheduleType": "manual" }, "dataResidency": "auto", "createdAt": 1700000000, "tags": [],
                      "configurations": { "streams": [ { "name": "customers" }, { "name": "orders" } ] }
                    }
                    """.formatted(CONNECTION_ID)))
        );
        stubFor(
            get(urlPathEqualTo("/v1/sources/src-1"))
                .willReturn(okJson("""
                    {
                      "sourceId": "src-1", "name": "production db", "sourceType": "postgres", "definitionId": "def-src", "workspaceId": "ws-1",
                      "createdAt": 1700000000, "configuration": { "sourceType": "faker", "seed": 1, "password": "hunter2-SECRET" }
                    }
                    """))
        );
        stubFor(
            get(urlPathEqualTo("/v1/destinations/dst-1"))
                .willReturn(okJson("""
                    {
                      "destinationId": "dst-1", "name": "warehouse", "destinationType": "snowflake", "definitionId": "def-dst", "workspaceId": "ws-1",
                      "createdAt": 1700000000, "configuration": { "destinationType": "dev-null", "test_destination": { "test_destination_type": "SILENT" } }
                    }
                    """))
        );
    }

    @Test
    void succeeded_job_returns_snapshot_and_emits_assets(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(51, "succeeded");
        stubSnapshot();

        var task = task(wiremock);
        var runContext = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));
        var out = task.run(runContext);

        assertThat(out.getJob().getRowsSynced(), is(20L));
        assertThat(out.getConnection().getDestinationType(), is("snowflake"));
        assertThat(out.getConnection().getStreamCount(), is(2));
        assertThat(out.getJobUrl(), is("https://cloud.airbyte.com/workspaces/ws-1/connections/" + CONNECTION_ID + "/timeline?openLogs=true&jobId=51"));
        try (var stream = runContext.storage().getFile(out.getConnectionSnapshot())) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8), not(containsString("hunter2")));
        }
        Mockito.verify(runContext, Mockito.atLeastOnce()).assets();
    }

    private AbstractTrigger.Output runWithFailingEmitter(WireMockRuntimeInfo wiremock, AssetFailureBehavior behavior) throws Exception {
        stubJob(55, "succeeded");
        stubSnapshot();

        var emitter = Mockito.mock(AssetEmitter.class);
        Mockito.doThrow(new IllegalArgumentException("invalid asset")).when(emitter).emit(Mockito.any());

        var task = task(wiremock, behavior);
        var runContext = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));
        Mockito.doReturn(emitter).when(runContext).assets();
        return task.run(runContext);
    }

    @Test
    void failing_asset_ends_in_warning_by_default(WireMockRuntimeInfo wiremock) throws Exception {
        var out = runWithFailingEmitter(wiremock, null);

        assertThat(out.finalState(), is(Optional.of(State.Type.WARNING)));
        assertThat(out.getJob().getJobId(), is(55L));
    }

    @Test
    void failing_asset_is_ignored_under_ignore_behavior(WireMockRuntimeInfo wiremock) throws Exception {
        var out = runWithFailingEmitter(wiremock, AssetFailureBehavior.IGNORE);

        assertThat(out.finalState(), is(Optional.empty()));
    }

    @Test
    void failing_asset_fails_the_task_but_keeps_the_outputs_under_fail_behavior(WireMockRuntimeInfo wiremock) throws Exception {
        var thrown = assertThrows(RunnableTaskException.class, () -> runWithFailingEmitter(wiremock, AssetFailureBehavior.FAIL));

        assertThat(thrown.getMessage(), containsString("invalid asset"));
        var output = (AbstractTrigger.Output) thrown.getOutput();
        assertThat(output.getJob().getJobId(), is(55L));
        assertThat(output.getConnection(), notNullValue());
    }

    @Test
    void unreadable_source_configuration_keeps_the_connection_snapshot(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(54, "succeeded");
        stubSnapshot();
        stubFor(
            get(urlPathEqualTo("/v1/sources/src-1"))
                .willReturn(okJson("""
                    {
                      "sourceId": "src-1", "name": "new connector", "sourceType": "brand-new", "definitionId": "def-src", "workspaceId": "ws-1",
                      "createdAt": 1700000000, "configuration": { "sourceType": "brand-new", "unknown_shape": true }
                    }
                    """))
        );

        var logs = new CopyOnWriteArrayList<LogEntry>();
        logQueue.addListener(logs::add);

        var task = task(wiremock);
        var runContext = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));
        var out = task.run(runContext);

        assertThat(out.getJobUrl(), is("https://cloud.airbyte.com/workspaces/ws-1/connections/" + CONNECTION_ID + "/timeline?openLogs=true&jobId=54"));
        assertThat(out.getConnection().getSourceType(), nullValue());
        assertThat(out.getConnection().getDestinationType(), is("snowflake"));
        assertThat(out.getConnection().getStreamCount(), is(2));
        Mockito.verify(runContext, Mockito.atLeastOnce()).assets();

        var warnings = TestsUtils.awaitLogs(
            logs, log -> log.getLevel() == Level.WARN && log.getMessage() != null && log.getMessage().contains("Could not read the Airbyte Cloud source src-1"), 1
        );
        assertThat(warnings, hasSize(1));
    }

    @Test
    void failed_job_throws_with_outputs_and_emits_no_assets(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(52, "failed");
        stubSnapshot();

        var task = task(wiremock);
        var runContext = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));
        var thrown = assertThrows(RunnableTaskException.class, () -> task.run(runContext));

        assertThat(thrown.getMessage(), containsString("Airbyte Cloud job 52 ended failed"));
        assertThat(thrown.getMessage(), containsString("jobId=52"));
        var output = (AbstractTrigger.Output) thrown.getOutput();
        assertThat(output.getJob().getStatus().value(), is("failed"));
        assertThat(output.getConnection(), notNullValue());
        Mockito.verify(runContext, Mockito.never()).assets();
    }

    @Test
    void forbidden_snapshot_only_warns_and_the_task_continues(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(53, "succeeded");
        stubFor(get(urlPathMatching("/v1/(connections|sources|destinations)/.*")).willReturn(forbidden().withBody("{\"message\":\"forbidden\"}")));

        var logs = new CopyOnWriteArrayList<LogEntry>();
        logQueue.addListener(logs::add);

        var task = task(wiremock);
        var out = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

        assertThat(out.getJob().getRowsSynced(), is(20L));
        assertThat(out.getConnection(), nullValue());
        assertThat(out.getJobUrl(), nullValue());
        var warnings = TestsUtils.awaitLogs(
            logs, log -> log.getLevel() == Level.WARN && log.getMessage() != null && log.getMessage().contains("Unable to read connection " + CONNECTION_ID + " from Airbyte Cloud"), 1
        );
        assertThat(warnings, hasSize(1));
        verify(exactly(1), getRequestedFor(urlPathMatching("/v1/connections/.*")));
        verify(exactly(0), getRequestedFor(urlPathMatching("/v1/(sources|destinations)/.*")));
    }
}
