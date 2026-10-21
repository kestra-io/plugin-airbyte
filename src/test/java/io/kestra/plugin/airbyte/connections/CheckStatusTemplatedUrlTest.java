package io.kestra.plugin.airbyte.connections;

import java.util.Map;

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
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
@WireMockTest(httpPort = 18083)
class CheckStatusTemplatedUrlTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void renders_templated_url(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(
            post(urlPathMatching("/api/v1/jobs/get/?"))
                .withRequestBody(matchingJsonPath("$.id", equalTo("971")))
                .willReturn(okJson("""
                    {
                      "job": { "id": 971, "status": "succeeded" },
                      "attempts": []
                    }
                    """))
        );

        CheckStatus task = CheckStatus.builder()
            .id(IdUtils.create())
            .type(CheckStatus.class.getName())
            .url(Property.ofExpression("{{ inputs.airbyteUrl }}"))
            .jobId(Property.ofValue("971"))
            .build();

        var out = task.run(
            TestsUtils.mockRunContext(
                runContextFactory,
                task,
                Map.of("airbyteUrl", wireMockRuntimeInfo.getHttpBaseUrl())
            )
        );

        assertThat(out, notNullValue());
        assertThat(out.getFinalJobStatus(), is("succeeded"));
        verify(postRequestedFor(urlPathMatching("/api/v1/jobs/get/?")));
    }
}
