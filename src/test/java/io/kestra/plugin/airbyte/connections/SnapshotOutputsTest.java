package io.kestra.plugin.airbyte.connections;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.event.Level;

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
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.AssetEmitter;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class SnapshotOutputsTest {
    private static final String CONNECTION_ID = "e3b1ce92-547c-436f-b1e8-23b6936c12cd";
    private static final String SECRET_PASSWORD = "hunter2-SECRET-PASSWORD";
    private static final String SECRET_HOST = "db.internal.example.com";

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private DispatchQueueInterface<LogEntry> logQueue;

    private void stubSnapshotEndpoints() {
        stubSnapshotEndpoints(true);
    }

    private void stubSnapshotEndpoints(boolean connectionHasWorkspace) {
        stubFor(
            post(urlPathMatching("/api/v1/connections/get/?"))
                .withRequestBody(matchingJsonPath("$.connectionId", equalTo(CONNECTION_ID)))
                .willReturn(okJson("""
                    {
                      "connectionId": "%s",
                      "name": "pg to snowflake",
                      %s
                      "sourceId": "src-1",
                      "destinationId": "dst-1",
                      "status": "active",
                      "scheduleType": "manual",
                      "namespaceDefinition": "source",
                      "prefix": "",
                      "operationIds": ["op-1"],
                      "syncCatalog": {
                        "streams": [
                          { "stream": { "name": "customers", "namespace": "public", "jsonSchema": { "properties": {} }, "supportedSyncModes": ["full_refresh"] },
                            "config": { "selected": true, "syncMode": "full_refresh", "destinationSyncMode": "overwrite", "aliasName": "customers" } },
                          { "stream": { "name": "orders", "namespace": "public" },
                            "config": { "selected": true, "syncMode": "incremental", "destinationSyncMode": "append", "cursorField": ["updated_at"] } },
                          { "stream": { "name": "ignored", "namespace": "public" }, "config": { "selected": false } }
                        ]
                      }
                    }
                    """.formatted(CONNECTION_ID, connectionHasWorkspace ? "\"workspaceId\": \"ws-1\"," : "")))
        );
        stubFor(
            post(urlPathMatching("/api/v1/sources/get/?"))
                .withRequestBody(matchingJsonPath("$.sourceId", equalTo("src-1")))
                .willReturn(okJson("""
                    {
                      "sourceId": "src-1", "name": "production db", "sourceName": "Postgres", "sourceDefinitionId": "def-src", "workspaceId": "ws-1",
                      "connectionConfiguration": { "host": "%s", "password": "%s" }
                    }
                    """.formatted(SECRET_HOST, SECRET_PASSWORD)))
        );
        stubFor(
            post(urlPathMatching("/api/v1/destinations/get/?"))
                .withRequestBody(matchingJsonPath("$.destinationId", equalTo("dst-1")))
                .willReturn(okJson("""
                    {
                      "destinationId": "dst-1", "name": "warehouse", "destinationName": "Snowflake", "destinationDefinitionId": "def-dst", "workspaceId": "ws-1",
                      "connectionConfiguration": { "host": "%s", "password": "%s", "credentials": { "private_key": "%s" } }
                    }
                    """.formatted(SECRET_HOST, SECRET_PASSWORD, SECRET_PASSWORD)))
        );
    }

    private void stubJob(long id, String status) {
        stubFor(
            post(urlPathMatching("/api/v1/jobs/get/?"))
                .withRequestBody(matchingJsonPath("$.id", equalTo(String.valueOf(id))))
                .willReturn(okJson("""
                    {
                      "job": { "id": %d, "configType": "sync", "configId": "%s", "status": "%s", "createdAt": 1700000000 },
                      "attempts": [
                        { "attempt": { "id": 0, "status": "succeeded", "createdAt": 1700000010, "endedAt": 1700000070,
                            "streamStats": [
                              { "streamName": "customers", "streamNamespace": "public", "stats": { "recordsEmitted": 5, "recordsCommitted": 5, "bytesCommitted": 50 } },
                              { "streamName": "orders", "streamNamespace": "public", "stats": { "recordsEmitted": 0, "recordsCommitted": 0 } }
                            ] },
                          "logs": { "logLines": [] } }
                      ]
                    }
                    """.formatted(id, CONNECTION_ID, status)))
        );
    }

    private void stubTrigger(long id) {
        stubFor(post(urlPathMatching("/api/v1/jobs/list/?")).willReturn(okJson("{\"jobs\":[],\"totalJobCount\":0}")));
        stubFor(post(urlPathMatching("/api/v1/connections/sync/?")).willReturn(okJson("{\"job\":{\"id\":" + id + ",\"status\":\"running\"},\"attempts\":[]}")));
    }

    private static Sync.SyncBuilder<?, ?> sync(WireMockRuntimeInfo wiremock) {
        return Sync.builder()
            .id(IdUtils.create())
            .type(Sync.class.getName())
            .assets(new AssetsDeclaration(null, null, null, null))
            .url(Property.ofValue(wiremock.getHttpBaseUrl()))
            .token(Property.ofValue("token"))
            .connectionId(Property.ofValue(CONNECTION_ID));
    }

    @Test
    void sync_exposes_connection_snapshot_and_job_link_without_connector_secrets(WireMockRuntimeInfo wiremock) throws Exception {
        stubSnapshotEndpoints();
        stubTrigger(91);
        stubJob(91, "succeeded");

        var task = sync(wiremock).webappUrl(Property.ofValue("https://airbyte.example.com/")).build();
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var out = task.run(runContext);

        assertThat(out.getConnection().getName(), is("pg to snowflake"));
        assertThat(out.getConnection().getSourceType(), is("Postgres"));
        assertThat(out.getConnection().getDestinationType(), is("Snowflake"));
        assertThat(out.getConnection().getStreamCount(), is(2));
        assertThat(out.getJobUrl(), is("https://airbyte.example.com/workspaces/ws-1/connections/" + CONNECTION_ID + "/timeline?openLogs=true&jobId=91&attemptNumber=0"));

        try (var stream = runContext.storage().getFile(out.getConnectionSnapshot())) {
            var file = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(file, containsString("customers"));
            assertThat(file, containsString("Snowflake"));
            assertThat(file, not(containsString(SECRET_PASSWORD)));
            assertThat(file, not(containsString(SECRET_HOST)));
            assertThat(file, not(containsString("connectionConfiguration")));
            assertThat(file, not(containsString("jsonSchema")));
        }

        var rendered = out.toMap().toString() + out.getConnection();
        assertThat(rendered, not(containsString(SECRET_PASSWORD)));
        assertThat(rendered, not(containsString(SECRET_HOST)));
    }

    @Test
    void job_url_falls_back_to_the_source_workspace_when_the_connection_has_none(WireMockRuntimeInfo wiremock) throws Exception {
        stubSnapshotEndpoints(false);
        stubTrigger(96);
        stubJob(96, "succeeded");

        var task = sync(wiremock).build();
        var out = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

        assertThat(out.getConnection().getWorkspaceId(), is("ws-1"));
        assertThat(out.getJobUrl(), is(wiremock.getHttpBaseUrl() + "/workspaces/ws-1/connections/" + CONNECTION_ID + "/timeline?openLogs=true&jobId=96&attemptNumber=0"));
    }

    @Test
    void assets_are_only_emitted_when_the_task_does_not_fail(WireMockRuntimeInfo wiremock) throws Exception {
        stubSnapshotEndpoints();
        stubTrigger(97);
        stubJob(97, "succeeded");

        var task = sync(wiremock).build();
        var succeeded = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));
        task.run(succeeded);
        Mockito.verify(succeeded, Mockito.atLeastOnce()).assets();

        stubTrigger(98);
        stubJob(98, "failed");
        var failed = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));
        assertThrows(RunnableTaskException.class, () -> task.run(failed));
        Mockito.verify(failed, Mockito.never()).assets();
    }

    private void stubPostgresDestination() {
        stubFor(
            post(urlPathMatching("/api/v1/destinations/get/?"))
                .withRequestBody(matchingJsonPath("$.destinationId", equalTo("dst-1")))
                .willReturn(okJson("""
                    {
                      "destinationId": "dst-1", "name": "warehouse", "destinationName": "Postgres", "destinationDefinitionId": "def-dst", "workspaceId": "ws-1",
                      "connectionConfiguration": { "host": "%s", "database": "analytics", "schema": "public", "password": "%s" }
                    }
                    """.formatted(SECRET_HOST, SECRET_PASSWORD)))
        );
    }

    @Test
    void assets_use_the_destination_location_without_persisting_it(WireMockRuntimeInfo wiremock) throws Exception {
        stubSnapshotEndpoints();
        stubPostgresDestination();
        stubTrigger(101);
        stubJob(101, "succeeded");

        var emitter = Mockito.mock(AssetEmitter.class);
        var task = sync(wiremock).build();
        var runContext = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));
        Mockito.doReturn(emitter).when(runContext).assets();
        var out = task.run(runContext);

        var captor = ArgumentCaptor.forClass(AssetEmit.class);
        Mockito.verify(emitter, Mockito.atLeastOnce()).emit(captor.capture());
        assertThat(captor.getAllValues().getFirst().outputs().getFirst().getId(), is("analytics.public.customers"));

        try (var stream = runContext.storage().getFile(out.getConnectionSnapshot())) {
            var file = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(file, not(containsString("analytics")));
            assertThat(file, not(containsString(SECRET_PASSWORD)));
        }
    }

    @Test
    void asset_failures_follow_the_asset_failure_behavior(WireMockRuntimeInfo wiremock) throws Exception {
        stubSnapshotEndpoints();
        stubTrigger(102);
        stubJob(102, "succeeded");

        var emitter = Mockito.mock(AssetEmitter.class);
        Mockito.doThrow(new IllegalArgumentException("invalid asset")).when(emitter).emit(Mockito.any());

        var warn = sync(wiremock).build();
        var warnContext = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, warn, Map.of()));
        Mockito.doReturn(emitter).when(warnContext).assets();
        assertThat(warn.run(warnContext).finalState(), is(Optional.of(State.Type.WARNING)));

        var ignore = sync(wiremock).assets(new AssetsDeclaration(null, null, null, Property.ofValue(AssetFailureBehavior.IGNORE))).build();
        var ignoreContext = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, ignore, Map.of()));
        Mockito.doReturn(emitter).when(ignoreContext).assets();
        assertThat(ignore.run(ignoreContext).finalState(), is(Optional.empty()));

        var fail = sync(wiremock).assets(new AssetsDeclaration(null, null, null, Property.ofValue(AssetFailureBehavior.FAIL))).build();
        var failContext = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, fail, Map.of()));
        Mockito.doReturn(emitter).when(failContext).assets();
        var exception = assertThrows(RunnableTaskException.class, () -> fail.run(failContext));
        assertThat(exception.getMessage(), containsString("assets.assetFailureBehavior"));
        assertThat(exception.getOutput(), notNullValue());
    }

    @Test
    void clear_jobs_keep_the_snapshot_but_never_emit_assets(WireMockRuntimeInfo wiremock) throws Exception {
        stubSnapshotEndpoints();
        stubFor(
            post(urlPathMatching("/api/v1/jobs/get/?"))
                .withRequestBody(matchingJsonPath("$.id", equalTo("99")))
                .willReturn(okJson("""
                    {
                      "job": { "id": 99, "configType": "clear", "configId": "%s", "status": "succeeded", "createdAt": 1700000000 },
                      "attempts": [ { "attempt": { "id": 0, "status": "succeeded" }, "logs": { "logLines": [] } } ]
                    }
                    """.formatted(CONNECTION_ID)))
        );

        var task = CheckStatus.builder()
            .id(IdUtils.create())
            .type(CheckStatus.class.getName())
            .url(Property.ofValue(wiremock.getHttpBaseUrl()))
            .token(Property.ofValue("token"))
            .jobId(Property.ofValue("99"))
            .build();
        var runContext = Mockito.spy(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));
        var out = task.run(runContext);

        assertThat(out.getConnection().getConnectionId(), is(CONNECTION_ID));
        assertThat(out.getJobUrl(), containsString("jobId=99"));
        Mockito.verify(runContext, Mockito.never()).assets();
    }

    @Test
    void sync_without_wait_still_exposes_the_snapshot(WireMockRuntimeInfo wiremock) throws Exception {
        stubSnapshotEndpoints();
        stubTrigger(92);

        var task = sync(wiremock).wait(Property.ofValue(false)).build();
        var out = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

        assertThat(out.getJobId(), is(92L));
        assertThat(out.getConnection().getDestinationType(), is("Snowflake"));
        assertThat(out.getConnectionSnapshot(), notNullValue());
        assertThat(out.getJobUrl(), is(wiremock.getHttpBaseUrl() + "/workspaces/ws-1/connections/" + CONNECTION_ID + "/timeline?openLogs=true&jobId=92"));
        assertThat(out.getJob(), nullValue());
    }

    @Test
    void sync_continues_with_a_warning_when_the_snapshot_cannot_be_read(WireMockRuntimeInfo wiremock) throws Exception {
        stubFor(post(urlPathMatching("/api/v1/connections/get/?")).willReturn(serverError()));
        stubTrigger(93);
        stubJob(93, "succeeded");

        var logs = new CopyOnWriteArrayList<LogEntry>();
        logQueue.addListener(logs::add);

        var task = sync(wiremock).build();
        var out = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

        assertThat(out.getJob().getStatus().toString(), is("succeeded"));
        assertThat(out.getConnection(), nullValue());
        assertThat(out.getConnectionSnapshot(), nullValue());
        assertThat(out.getJobUrl(), nullValue());
        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/connections/get/?")));

        var warnings = TestsUtils.awaitLogs(
            logs,
            log -> log.getLevel() == Level.WARN && log.getMessage() != null && log.getMessage().contains("Unable to read the configuration of connection"),
            1
        );
        assertThat(warnings.getFirst().getMessage(), containsString(CONNECTION_ID));
    }

    @Test
    void standalone_check_status_resolves_the_connection_through_the_job_config_id(WireMockRuntimeInfo wiremock) throws Exception {
        stubSnapshotEndpoints();
        stubJob(94, "succeeded");

        var task = CheckStatus.builder()
            .id(IdUtils.create())
            .type(CheckStatus.class.getName())
            .url(Property.ofValue(wiremock.getHttpBaseUrl()))
            .token(Property.ofValue("token"))
            .jobId(Property.ofValue("94"))
            .build();
        var out = task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

        assertThat(out.getConnection().getConnectionId(), is(CONNECTION_ID));
        assertThat(out.getConnection().getWorkspaceId(), is("ws-1"));
        assertThat(out.getConnectionSnapshot(), notNullValue());
        assertThat(out.getJobUrl(), containsString("/workspaces/ws-1/connections/" + CONNECTION_ID + "/timeline?openLogs=true&jobId=94&attemptNumber=0"));
        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/connections/get/?")));
    }

    @Test
    void sync_with_wait_fetches_the_snapshot_only_once(WireMockRuntimeInfo wiremock) throws Exception {
        stubSnapshotEndpoints();
        stubTrigger(95);
        stubJob(95, "succeeded");

        var task = sync(wiremock).build();
        task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of()));

        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/connections/get/?")));
        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/sources/get/?")));
        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/destinations/get/?")));
    }
}
