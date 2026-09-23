package io.kestra.plugin.airbyte.connections;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest(httpPort = 18081)
class SyncRetryTest {
    @Inject
    private RunContextFactory runContextFactory;

    private void stubApplicationToken() {
        stubFor(
            post(urlPathEqualTo("/api/v1/applications/token"))
                .willReturn(okJson("{\"access_token\":\"ey.mock.token\",\"token_type\":\"Bearer\",\"expires_in\":3600}"))
        );
    }

    @Test
    void retries_on_503_then_succeeds(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();

        stubFor(
            post(urlPathMatching("/api/v1/jobs/list/?"))
                .willReturn(okJson("""
                    { "jobs": [], "totalJobCount": 0 }
                    """))
        );

        stubFor(
            post(urlPathMatching("/api/v1/connections/sync/?"))
                .inScenario("retry-503")
                .whenScenarioStateIs("Started")
                .willReturn(serviceUnavailable())
                .willSetStateTo("first-attempt-done")
        );

        stubFor(
            post(urlPathMatching("/api/v1/connections/sync/?"))
                .inScenario("retry-503")
                .whenScenarioStateIs("first-attempt-done")
                .willReturn(okJson("""
                    {
                      "job": { "id": 456, "status": "running" },
                      "attempts": []
                    }
                    """))
        );

        stubFor(
            post(urlPathMatching("/api/v1/jobs/get/?"))
                .willReturn(okJson("""
                    {
                      "job": { "id": 456, "status": "succeeded" },
                      "attempts": [
                        {
                          "attempt": { "id": 0, "status": "succeeded" },
                          "logs": { "logLines": [] }
                        }
                      ]
                    }
                    """))
        );

        var runContext = runContextFactory.of(Map.of());

        var task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("client-id"))
                    .clientSecret(Property.ofValue("client-secret"))
                    .build()
            )
            .connectionId(Property.ofValue("conn-retry-test"))
            .wait(Property.ofValue(true))
            .build();

        var out = task.run(runContext);

        assertThat(out, notNullValue());
        assertThat(out.getJobId(), notNullValue());

        verify(moreThanOrExactly(2), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void does_not_retry_on_409(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubApplicationToken();

        stubFor(
            post(urlPathMatching("/api/v1/jobs/list/?"))
                .inScenario("no-retry-409")
                .whenScenarioStateIs("Started")
                .willReturn(okJson("""
                    { "jobs": [], "totalJobCount": 0 }
                    """))
                .willSetStateTo("trigger-conflicted")
        );

        stubFor(
            post(urlPathMatching("/api/v1/jobs/list/?"))
                .inScenario("no-retry-409")
                .whenScenarioStateIs("trigger-conflicted")
                .willReturn(okJson("""
                    {
                      "jobs": [
                        { "job": { "id": 789, "configType": "sync", "status": "running" }, "attempts": [] }
                      ],
                      "totalJobCount": 1
                    }
                    """))
        );

        stubFor(
            post(urlPathMatching("/api/v1/connections/sync/?"))
                .willReturn(aResponse().withStatus(409).withBody("""
                    {
                      "message": "A sync is already running for this connection"
                    }
                    """))
        );

        var runContext = runContextFactory.of(Map.of());

        var task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("client-id"))
                    .clientSecret(Property.ofValue("client-secret"))
                    .build()
            )
            .connectionId(Property.ofValue("conn-no-retry-409"))
            .failOnActiveSync(Property.ofValue(true))
            .wait(Property.ofValue(false))
            .build();

        assertThrows(SyncAlreadyRunningException.class, () -> task.run(runContext));

        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void race_adopts_job_when_sync_trigger_returns_409(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();

        stubFor(
            post(urlPathMatching("/api/v1/jobs/list/?"))
                .inScenario("race-adopt")
                .whenScenarioStateIs("Started")
                .willReturn(okJson("""
                    { "jobs": [], "totalJobCount": 0 }
                    """))
                .willSetStateTo("trigger-conflicted")
        );

        stubFor(
            post(urlPathMatching("/api/v1/jobs/list/?"))
                .inScenario("race-adopt")
                .whenScenarioStateIs("trigger-conflicted")
                .willReturn(okJson("""
                    {
                      "jobs": [
                        { "job": { "id": 890, "configType": "sync", "status": "running" }, "attempts": [] }
                      ],
                      "totalJobCount": 1
                    }
                    """))
        );

        stubFor(
            post(urlPathMatching("/api/v1/connections/sync/?"))
                .willReturn(aResponse().withStatus(409).withBody("""
                    {
                      "message": "A sync is already running for this connection"
                    }
                    """))
        );

        stubFor(
            post(urlPathMatching("/api/v1/jobs/get/?"))
                .willReturn(okJson("""
                    {
                      "job": { "id": 890, "status": "succeeded" },
                      "attempts": [
                        {
                          "attempt": { "id": 0, "status": "succeeded" },
                          "logs": { "logLines": [] }
                        }
                      ]
                    }
                    """))
        );

        var runContext = runContextFactory.of(Map.of());

        var task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("client-id"))
                    .clientSecret(Property.ofValue("client-secret"))
                    .build()
            )
            .connectionId(Property.ofValue("conn-race-adopt"))
            .wait(Property.ofValue(true))
            .build();

        var out = task.run(runContext);

        assertThat(out, notNullValue());
        assertThat(out.getJobId(), is(890L));
        assertThat(out.getAdopted(), is(true));
        assertThat(out.getAlreadyRunning(), is(true));

        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void fails_when_conflict_is_caused_by_a_non_sync_job(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubApplicationToken();

        stubFor(
            post(urlPathMatching("/api/v1/jobs/list/?"))
                .willReturn(okJson("""
                    { "jobs": [], "totalJobCount": 0 }
                    """))
        );

        stubFor(
            post(urlPathMatching("/api/v1/connections/sync/?"))
                .willReturn(aResponse().withStatus(409).withBody("""
                    {
                      "message": "A sync is already running for this connection"
                    }
                    """))
        );

        var runContext = runContextFactory.of(Map.of());

        var task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("client-id"))
                    .clientSecret(Property.ofValue("client-secret"))
                    .build()
            )
            .connectionId(Property.ofValue("conn-reset-conflict"))
            .wait(Property.ofValue(false))
            .build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), is("Airbyte reported a job already running for connection conn-reset-conflict, but no active sync job was found; retry the task"));

        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }

    @Test
    void skips_when_conflict_is_caused_by_a_non_sync_job_and_onActiveSync_is_SKIP(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubApplicationToken();

        stubFor(
            post(urlPathMatching("/api/v1/jobs/list/?"))
                .willReturn(okJson("""
                    { "jobs": [], "totalJobCount": 0 }
                    """))
        );

        stubFor(
            post(urlPathMatching("/api/v1/connections/sync/?"))
                .willReturn(aResponse().withStatus(409).withBody("""
                    {
                      "message": "A sync is already running for this connection"
                    }
                    """))
        );

        var runContext = runContextFactory.of(Map.of());

        var task = Sync.builder()
            .url(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .applicationCredentials(
                io.kestra.plugin.airbyte.AbstractAirbyteConnection.ApplicationCredentials.builder()
                    .clientId(Property.ofValue("client-id"))
                    .clientSecret(Property.ofValue("client-secret"))
                    .build()
            )
            .connectionId(Property.ofValue("conn-reset-conflict-skip"))
            .failOnActiveSync(Property.ofValue(false))
            .wait(Property.ofValue(false))
            .build();

        var out = task.run(runContext);

        assertThat(out, notNullValue());
        assertThat(out.getAlreadyRunning(), is(true));
        assertThat(out.getAdopted(), is(false));
        assertThat(out.getJobId(), is(nullValue()));

        verify(exactly(1), postRequestedFor(urlPathMatching("/api/v1/connections/sync/?")));
    }
}
