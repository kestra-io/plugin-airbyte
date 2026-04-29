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
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest(httpPort = 18081)
class SyncRetryTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void retries_on_503_then_succeeds(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(
            post(urlPathEqualTo("/api/v1/applications/token"))
                .willReturn(okJson("{\"access_token\":\"ey.mock.token\",\"token_type\":\"Bearer\",\"expires_in\":3600}"))
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
        stubFor(
            post(urlPathEqualTo("/api/v1/applications/token"))
                .willReturn(okJson("{\"access_token\":\"ey.mock.token\",\"token_type\":\"Bearer\",\"expires_in\":3600}"))
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
}
