package io.kestra.plugin.airbyte.connections;

import java.util.List;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest(httpPort = 18081)
class ResetTest {
    private static final String CONNECTION_ID = "e3b1ce92-547c-436f-b1e8-23b6936c12cd";

    @Inject
    private RunContextFactory runContextFactory;

    private static Reset.StreamSelection stream(String name, String namespace) {
        return Reset.StreamSelection.builder()
            .streamName(Property.ofValue(name))
            .streamNamespace(namespace == null ? null : Property.ofValue(namespace))
            .build();
    }

    private static Reset.ResetBuilder<?, ?> task(WireMockRuntimeInfo wm) {
        return Reset.builder()
            .url(Property.ofValue(wm.getHttpBaseUrl()))
            .username(Property.ofValue("airbyte"))
            .password(Property.ofValue("password"))
            .connectionId(Property.ofValue(CONNECTION_ID));
    }

    @Test
    void resetsOneStreamAndReturnsJobId(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlPathMatching("/api/v1/connections/reset/stream/?"))
                .withHeader("Authorization", matching("Basic .+"))
                .withRequestBody(matchingJsonPath("$.connectionId", equalTo(CONNECTION_ID)))
                .withRequestBody(matchingJsonPath("$.streams.length()", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.streams[0].streamName", equalTo("customers")))
                .withRequestBody(matchingJsonPath("$.streams[0].streamNamespace", equalTo("public")))
                .willReturn(okJson("""
                    { "job": { "id": 456, "configType": "reset_connection", "status": "pending" }, "attempts": [] }
                    """))
        );

        var output = task(wm).streams(List.of(stream("customers", "public"))).build()
            .run(runContextFactory.of(Map.of()));

        assertThat(output, notNullValue());
        assertThat(output.getJobId(), is(456L));
    }

    @Test
    void omitsNamespaceWhenNotSetAndRendersExpressions(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlPathMatching("/api/v1/connections/reset/stream/?"))
                .withRequestBody(matchingJsonPath("$.streams.length()", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.streams[0].streamName", equalTo("customers")))
                .withRequestBody(matchingJsonPath("$.streams[0].streamNamespace", equalTo("public")))
                .withRequestBody(matchingJsonPath("$.streams[1].streamName", equalTo("orders")))
                .withRequestBody(notMatching("(?s).*orders[^}]*streamNamespace.*"))
                .willReturn(okJson("""
                    { "job": { "id": 789, "status": "pending" } }
                    """))
        );

        var task = task(wm)
            .streams(List.of(
                Reset.StreamSelection.builder()
                    .streamName(Property.ofExpression("{{ vars.stream }}"))
                    .streamNamespace(Property.ofExpression("{{ vars.schema }}"))
                    .build(),
                stream("orders", null)
            ))
            .build();

        var output = task.run(runContextFactory.of(Map.of("vars", Map.of("stream", "customers", "schema", "public"))));

        assertThat(output.getJobId(), is(789L));
    }

    @Test
    void failsWithoutStreams(WireMockRuntimeInfo wm) {
        var runContext = runContextFactory.of(Map.of());

        var noStreams = assertThrows(IllegalArgumentException.class, () -> task(wm).streams(List.of()).build().run(runContext));
        assertThat(noStreams.getMessage(), containsString("At least one stream"));

        var nullStreams = assertThrows(IllegalArgumentException.class, () -> task(wm).build().run(runContext));
        assertThat(nullStreams.getMessage(), containsString("At least one stream"));
    }

    @Test
    void failsOnBlankStreamName(WireMockRuntimeInfo wm) {
        var runContext = runContextFactory.of(Map.of());

        var blank = assertThrows(IllegalArgumentException.class, () -> task(wm).streams(List.of(stream("  ", "public"))).build().run(runContext));
        assertThat(blank.getMessage(), containsString("streamName"));
    }

    @Test
    void reportsAJobAlreadyRunning(WireMockRuntimeInfo wm) {
        stubFor(
            post(urlPathMatching("/api/v1/connections/reset/stream/?"))
                .willReturn(aResponse().withStatus(409).withBody("{\"message\":\"A job is already running for this connection\"}"))
        );

        var e = assertThrows(IllegalStateException.class, () -> task(wm).streams(List.of(stream("customers", "public"))).build()
            .run(runContextFactory.of(Map.of())));
        assertThat(e.getMessage(), containsString(CONNECTION_ID));
    }

    @Test
    void failsWhenAirbyteRejectsTheStream(WireMockRuntimeInfo wm) {
        stubFor(
            post(urlPathMatching("/api/v1/connections/reset/stream/?"))
                .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"Stream does not exist\"}"))
        );

        var e = assertThrows(IllegalStateException.class, () -> task(wm).streams(List.of(stream("missing", "public"))).build()
            .run(runContextFactory.of(Map.of())));

        assertThat(e.getMessage(), containsString("422"));
        assertThat(e.getMessage(), containsString(CONNECTION_ID));
        assertThat(e.getCause(), notNullValue());
        // a client error is not retryable: Airbyte must be called exactly once
        verify(1, postRequestedFor(urlPathMatching("/api/v1/connections/reset/stream/?")));
    }
}
