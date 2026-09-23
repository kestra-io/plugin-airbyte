package io.kestra.plugin.airbyte.connections;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.airbyte.AbstractAirbyteConnectionTest;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest(httpPort = 18080)
class SyncMockTest extends AbstractAirbyteConnectionTest {
    @Inject
    private RunContextFactory runContextFactory;

    private void stubApplicationToken() {
        stubFor(
            post(urlPathEqualTo("/api/v1/applications/token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.client_id", equalTo("local-client")))
                .withRequestBody(matchingJsonPath("$.client_secret", equalTo("local-secret")))
                .withRequestBody(matchingJsonPath("$.grant-type", equalTo("client_credentials")))
                .willReturn(okJson("{\"access_token\":\"ey.mock.local\",\"token_type\":\"Bearer\",\"expires_in\":3600}"))
        );
    }

    private void stubJobsList(String jobsBody) {
        stubFor(
            post(urlPathMatching("/api/v1/jobs/list/?"))
                .withHeader("Authorization", equalTo("Bearer ey.mock.local"))
                .withRequestBody(matchingJsonPath("$.configId", equalTo(connectionId)))
                .willReturn(okJson(jobsBody))
        );
    }

    @Test
    void run_local_with_app(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();
        stubJobsList("""
            { "jobs": [], "totalJobCount": 0 }
            """);

        stubFor(
            post(urlPathMatching("/api/v1/connections/sync/?"))
                .withHeader("Authorization", equalTo("Bearer ey.mock.local"))
                .withRequestBody(matchingJsonPath("$.connectionId", equalTo(connectionId)))
                .willReturn(okJson("""
                    {
                      "job": { "id": 123, "status": "running" },
                      "attempts": []
                    }
                    """))
        );

        stubFor(
            post(urlPathMatching("/api/v1/jobs/get/?"))
                .withHeader("Authorization", equalTo("Bearer ey.mock.local"))
                .withRequestBody(matchingJsonPath("$.id"))
                .willReturn(okJson("""
                    {
                      "job": { "id": 123, "status": "succeeded" },
                      "attempts": [
                        {
                          "attempt": { "id": 0, "status": "succeeded" },
                          "logs": { "logLines": ["sync started", "sync finished"] }
                        }
                      ]
                    }
                    """))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        Sync task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("local-client"))
                    .clientSecret(Property.ofValue("local-secret"))
                    .build()
            )
            .connectionId(Property.ofValue(connectionId))
            .wait(Property.ofValue(true))
            .build();

        var out = task.run(runContext);
        assertThat(out, notNullValue());
        assertThat(out.getJobId(), notNullValue());
        assertThat(out.getAdopted(), is(false));

        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void run_succeeded_history_triggers_new_sync(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();
        stubJobsList("""
            {
              "jobs": [
                { "job": { "id": 100, "status": "succeeded" }, "attempts": [] }
              ],
              "totalJobCount": 1
            }
            """);

        stubFor(
            post(urlPathMatching("/api/v1/connections/sync/?"))
                .withHeader("Authorization", equalTo("Bearer ey.mock.local"))
                .willReturn(okJson("""
                    { "job": { "id": 101, "status": "running" }, "attempts": [] }
                    """))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        Sync task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("local-client"))
                    .clientSecret(Property.ofValue("local-secret"))
                    .build()
            )
            .connectionId(Property.ofValue(connectionId))
            .wait(Property.ofValue(false))
            .build();

        var out = task.run(runContext);

        assertThat(out.getJobId(), is(101L));
        assertThat(out.getAdopted(), is(false));
        assertThat(out.getAlreadyRunning(), is(false));

        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void run_adopts_active_sync_job_and_polls_to_completion(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();
        stubJobsList("""
            {
              "jobs": [
                { "job": { "id": 200, "status": "running" }, "attempts": [] }
              ],
              "totalJobCount": 1
            }
            """);

        stubFor(
            post(urlPathMatching("/api/v1/jobs/get/?"))
                .withHeader("Authorization", equalTo("Bearer ey.mock.local"))
                .withRequestBody(matchingJsonPath("$.id"))
                .willReturn(okJson("""
                    {
                      "job": { "id": 200, "status": "succeeded" },
                      "attempts": [
                        {
                          "attempt": { "id": 0, "status": "succeeded" },
                          "logs": { "logLines": [] }
                        }
                      ]
                    }
                    """))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        Sync task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("local-client"))
                    .clientSecret(Property.ofValue("local-secret"))
                    .build()
            )
            .connectionId(Property.ofValue(connectionId))
            .wait(Property.ofValue(true))
            .build();

        var out = task.run(runContext);

        assertThat(out.getJobId(), is(200L));
        assertThat(out.getAdopted(), is(true));
        assertThat(out.getAlreadyRunning(), is(true));

        verify(exactly(0), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void run_wait_false_with_adopted_job_returns_without_polling(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();
        stubJobsList("""
            {
              "jobs": [
                { "job": { "id": 201, "status": "pending" }, "attempts": [] }
              ],
              "totalJobCount": 1
            }
            """);

        RunContext runContext = runContextFactory.of(Map.of());

        Sync task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("local-client"))
                    .clientSecret(Property.ofValue("local-secret"))
                    .build()
            )
            .connectionId(Property.ofValue(connectionId))
            .wait(Property.ofValue(false))
            .build();

        var out = task.run(runContext);

        assertThat(out.getJobId(), is(201L));
        assertThat(out.getAdopted(), is(true));
        assertThat(out.getAlreadyRunning(), is(true));

        verify(exactly(0), postRequestedFor(urlPathMatching("/api/v1/jobs/get/?")));
        verify(exactly(0), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void run_fails_when_onActiveSync_is_FAIL_and_job_is_running(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();
        stubJobsList("""
            {
              "jobs": [
                { "job": { "id": 202, "status": "running" }, "attempts": [] }
              ],
              "totalJobCount": 1
            }
            """);

        RunContext runContext = runContextFactory.of(Map.of());

        Sync task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("local-client"))
                    .clientSecret(Property.ofValue("local-secret"))
                    .build()
            )
            .connectionId(Property.ofValue(connectionId))
            .onActiveSync(Property.ofValue(Sync.OnActiveSync.FAIL))
            .wait(Property.ofValue(false))
            .build();

        assertThrows(SyncAlreadyRunningException.class, () -> task.run(runContext));

        verify(exactly(0), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void run_skips_when_onActiveSync_is_SKIP_and_job_is_running(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();
        stubJobsList("""
            {
              "jobs": [
                { "job": { "id": 203, "status": "running" }, "attempts": [] }
              ],
              "totalJobCount": 1
            }
            """);

        RunContext runContext = runContextFactory.of(Map.of());

        Sync task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("local-client"))
                    .clientSecret(Property.ofValue("local-secret"))
                    .build()
            )
            .connectionId(Property.ofValue(connectionId))
            .onActiveSync(Property.ofValue(Sync.OnActiveSync.SKIP))
            .wait(Property.ofValue(true))
            .build();

        var out = task.run(runContext);

        assertThat(out.getAlreadyRunning(), is(true));
        assertThat(out.getAdopted(), is(false));
        assertThat(out.getJobId(), is(nullValue()));

        verify(exactly(0), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void run_legacy_failOnActiveSync_true_maps_to_FAIL(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();
        stubJobsList("""
            {
              "jobs": [
                { "job": { "id": 204, "status": "running" }, "attempts": [] }
              ],
              "totalJobCount": 1
            }
            """);

        RunContext runContext = runContextFactory.of(Map.of());

        Sync task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("local-client"))
                    .clientSecret(Property.ofValue("local-secret"))
                    .build()
            )
            .connectionId(Property.ofValue(connectionId))
            .failOnActiveSync(Property.ofValue(true))
            .wait(Property.ofValue(false))
            .build();

        assertThrows(SyncAlreadyRunningException.class, () -> task.run(runContext));
    }

    @Test
    void run_handles_already_running_when_airbyte_returns_500(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();

        stubFor(
            post(urlPathMatching("/api/v1/jobs/list/?"))
                .withHeader("Authorization", equalTo("Bearer ey.mock.local"))
                .inScenario("race-500")
                .whenScenarioStateIs("Started")
                .willReturn(okJson("""
                    { "jobs": [], "totalJobCount": 0 }
                    """))
                .willSetStateTo("trigger-failed")
        );

        stubFor(
            post(urlPathMatching("/api/v1/jobs/list/?"))
                .withHeader("Authorization", equalTo("Bearer ey.mock.local"))
                .inScenario("race-500")
                .whenScenarioStateIs("trigger-failed")
                .willReturn(okJson("""
                    {
                      "jobs": [
                        { "job": { "id": 205, "status": "running" }, "attempts": [] }
                      ],
                      "totalJobCount": 1
                    }
                    """))
        );

        stubFor(
            post(urlPathMatching("/api/v1/connections/sync/?"))
                .withHeader("Authorization", equalTo("Bearer ey.mock.local"))
                .withRequestBody(matchingJsonPath("$.connectionId", equalTo(connectionId)))
                .willReturn(serverError().withBody("""
                    {
                      "message": "A sync is already running for this connection"
                    }
                    """))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        Sync task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("local-client"))
                    .clientSecret(Property.ofValue("local-secret"))
                    .build()
            )
            .connectionId(Property.ofValue(connectionId))
            .failOnActiveSync(Property.ofValue(false))
            .wait(Property.ofValue(false))
            .build();

        var out = task.run(runContext);

        assertThat(out, notNullValue());
        assertThat(out.getAlreadyRunning(), is(true));
        assertThat(out.getJobId(), is(nullValue()));
    }
}
