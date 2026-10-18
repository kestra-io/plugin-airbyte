package io.kestra.plugin.airbyte.connections;

import java.time.Duration;
import java.util.Collections;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class ResetTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void resets_only_selected_stream_and_waits_by_default(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            post(urlEqualTo("/api/v1/connections/reset/stream"))
                .withBasicAuth("airbyte", "password")
                .withRequestBody(equalToJson("""
                    {"connectionId":"connection-1","streams":[{"streamName":"customers","streamNamespace":"public"}]}
                    """))
                .willReturn(okJson("""
                    {"job":{"id":123,"status":"pending","configType":"reset_connection"},"attempts":[]}
                    """))
        );
        stubFor(
            post(urlEqualTo("/api/v1/jobs/get/"))
                .withBasicAuth("airbyte", "password")
                .withRequestBody(equalToJson("{\"id\":123}"))
                .willReturn(okJson("""
                    {"job":{"id":123,"status":"succeeded"},"attempts":[]}
                    """))
        );

        var task = Reset.builder()
            .url(new Property<>("{{ inputs.url }}"))
            .username(Property.ofValue("airbyte"))
            .password(Property.ofValue("password"))
            .connectionId(new Property<>("{{ inputs.connection }}"))
            .streams(
                List.of(
                    Reset.Stream.builder()
                        .streamName(new Property<>("{{ inputs.stream }}"))
                        .streamNamespace(new Property<>("{{ inputs.namespace }}"))
                        .build()
                )
            )
            .pollFrequency(Property.ofValue(Duration.ofMillis(10)))
            .build();
        var output = task.run(
            runContextFactory.of(
                Map.of(
                    "inputs", Map.of(
                        "url", server.getHttpBaseUrl(), "connection", "connection-1", "stream", "customers", "namespace", "public"
                    )
                )
            )
        );

        assertThat(output.getJobId(), is(123L));
        verify(1, postRequestedFor(urlEqualTo("/api/v1/connections/reset/stream")));
        verify(1, postRequestedFor(urlEqualTo("/api/v1/jobs/get/")));
        verify(0, postRequestedFor(urlMatching(".*/connections/reset/?")));
        verify(0, postRequestedFor(urlMatching(".*/connections/sync/?")));
    }

    @Test
    void starts_reset_without_waiting_and_preserves_optional_namespaces(WireMockRuntimeInfo server) throws Exception {
        stubFor(
            post(urlEqualTo("/api/v1/connections/reset/stream"))
                .withHeader("Authorization", equalTo("Bearer local-token"))
                .withRequestBody(equalToJson("""
                    {"connectionId":"connection-1","streams":[
                      {"streamName":"customers"},
                      {"streamName":"orders","streamNamespace":""}
                    ]}
                    """))
                .willReturn(okJson("{\"job\":{\"id\":124,\"status\":\"pending\"}}"))
        );

        var task = Reset.builder()
            .url(Property.ofValue(server.getHttpBaseUrl()))
            .token(Property.ofValue("local-token"))
            .connectionId(Property.ofValue("connection-1"))
            .streams(
                List.of(
                    Reset.Stream.builder().streamName(Property.ofValue("customers")).build(),
                    Reset.Stream.builder().streamName(Property.ofValue("orders")).streamNamespace(Property.ofValue("")).build()
                )
            )
            .wait(Property.ofValue(false))
            .build();

        assertThat(task.run(runContextFactory.of(Map.of())).getJobId(), is(124L));
        verify(1, postRequestedFor(urlEqualTo("/api/v1/connections/reset/stream")));
        verify(0, postRequestedFor(urlEqualTo("/api/v1/jobs/get/")));
    }

    @Test
    void rejects_missing_or_empty_stream_selection_before_sending_requests(WireMockRuntimeInfo server) {
        var task = Reset.builder()
            .url(Property.ofValue(server.getHttpBaseUrl()))
            .connectionId(Property.ofValue("connection-1"))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        var emptyTask = Reset.builder()
            .url(Property.ofValue(server.getHttpBaseUrl()))
            .connectionId(Property.ofValue("connection-1"))
            .streams(List.of())
            .build();
        assertThrows(IllegalArgumentException.class, () -> emptyTask.run(runContextFactory.of(Map.of())));
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void rejects_missing_blank_or_null_stream_names_before_sending_requests(WireMockRuntimeInfo server) {
        for (var streams : List.of(
            Collections.<Reset.Stream> singletonList(null),
            List.of(Reset.Stream.builder().build()),
            List.of(Reset.Stream.builder().streamName(Property.ofValue(" ")).build())
        )) {
            var task = Reset.builder()
                .url(Property.ofValue(server.getHttpBaseUrl()))
                .connectionId(Property.ofValue("connection-1"))
                .streams(streams)
                .build();
            assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        }
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void propagates_reset_api_errors_without_falling_back_to_connection_reset(WireMockRuntimeInfo server) {
        stubFor(post(urlEqualTo("/api/v1/connections/reset/stream")).willReturn(badRequest().withBody("Unknown stream")));

        var task = task(server).wait(Property.ofValue(false)).build();

        assertThrows(Exception.class, () -> task.run(runContextFactory.of(Map.of())));
        verify(1, postRequestedFor(urlEqualTo("/api/v1/connections/reset/stream")));
        verify(0, postRequestedFor(urlMatching(".*/connections/reset/?")));
    }

    @Test
    void rejects_responses_without_a_job_id(WireMockRuntimeInfo server) {
        for (var body : List.of("{}", "{\"job\":{}}")) {
            stubFor(post(urlEqualTo("/api/v1/connections/reset/stream")).willReturn(okJson(body)));
            var error = assertThrows(IllegalStateException.class, () -> task(server).build().run(runContextFactory.of(Map.of())));
            assertThat(error.getMessage(), containsString("no reset job ID"));
        }
        verify(0, postRequestedFor(urlEqualTo("/api/v1/jobs/get/")));
    }

    @Test
    void fails_when_reset_job_fails(WireMockRuntimeInfo server) {
        stubFor(
            post(urlEqualTo("/api/v1/connections/reset/stream"))
                .willReturn(okJson("{\"job\":{\"id\":125,\"status\":\"pending\"}}"))
        );
        stubFor(
            post(urlEqualTo("/api/v1/jobs/get/"))
                .willReturn(okJson("{\"job\":{\"id\":125,\"status\":\"failed\"},\"attempts\":[]}"))
        );

        var error = assertThrows(Exception.class, () -> task(server).build().run(runContextFactory.of(Map.of())));

        assertThat(error.getMessage(), containsString("Failed run with status 'failed'"));
    }

    private Reset.ResetBuilder<?, ?> task(WireMockRuntimeInfo server) {
        return Reset.builder()
            .url(Property.ofValue(server.getHttpBaseUrl()))
            .connectionId(Property.ofValue("connection-1"))
            .streams(List.of(Reset.Stream.builder().streamName(Property.ofValue("customers")).build()));
    }
}
