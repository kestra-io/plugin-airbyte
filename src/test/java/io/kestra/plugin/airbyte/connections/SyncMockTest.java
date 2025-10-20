package io.kestra.plugin.airbyte.connections;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.airbyte.AbstractAirbyteConnectionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
@WireMockTest(httpPort = 18080)
class SyncMockTest extends AbstractAirbyteConnectionTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run_local_with_app(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/api/v1/applications/token"))
            .withHeader("Content-Type", containing("application/json"))
            .withRequestBody(matchingJsonPath("$.client_id", equalTo("local-client")))
            .withRequestBody(matchingJsonPath("$.client_secret", equalTo("local-secret")))
            .withRequestBody(matchingJsonPath("$.grant-type", equalTo("client_credentials")))
            .willReturn(okJson("{\"access_token\":\"ey.mock.local\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));

        stubFor(post(urlPathMatching("/api/v1/connections/sync/?"))
            .withHeader("Authorization", equalTo("Bearer ey.mock.local"))
            .withRequestBody(matchingJsonPath("$.connectionId", equalTo(connectionId)))
            .willReturn(okJson("""
                  {
                    "job": { "id": 123, "status": "running" },
                    "attempts": []
                  }
                  """)));


        stubFor(post(urlPathMatching("/api/v1/jobs/get/?"))
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
                  """)));


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
    }
}
