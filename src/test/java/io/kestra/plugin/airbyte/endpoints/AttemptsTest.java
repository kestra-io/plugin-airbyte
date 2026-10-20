package io.kestra.plugin.airbyte.endpoints;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.airbyte.models.AttemptRow;
import io.kestra.plugin.airbyte.models.AttemptStatus;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class AttemptsTest {
    private static final String URI = "kestra:///ns/flow/executions/exec/tasks/sync/taskrun/attempts.ion";

    private static StubEndpointContext contextWithAttempts(int count) throws Exception {
        var output = new ByteArrayOutputStream();
        for (var index = 0; index < count; index++) {
            FileSerde.write(
                output,
                AttemptRow.builder()
                    .attemptNumber(index)
                    .status(index == count - 1 ? AttemptStatus.SUCCEEDED : AttemptStatus.FAILED)
                    .recordsSynced(10L * index)
                    .failures(List.of())
                    .streams(List.of(AttemptRow.Stream.builder().name("users").recordsCommitted(5L).build()))
                    .build()
            );
        }
        return new StubEndpointContext().output("attempts", URI).file(URI, output.toByteArray());
    }

    @Test
    void returns_the_attempts_of_the_stored_file() throws Exception {
        var json = StubEndpointContext.json(new Attempts().handle(contextWithAttempts(2)));

        assertThat(json.get("page").asInt(), is(1));
        assertThat(json.get("size").asInt(), is(50));
        assertThat(json.get("total").asInt(), is(2));
        assertThat(json.get("results").size(), is(2));
        assertThat(json.get("results").get(0).get("attemptNumber").asInt(), is(0));
        assertThat(json.get("results").get(0).get("status").asText(), is("failed"));
        assertThat(json.get("results").get(1).get("recordsSynced").asLong(), is(10L));
        assertThat(json.get("results").get(1).get("streams").get(0).get("name").asText(), is("users"));
    }

    @Test
    void paginates() throws Exception {
        var json = StubEndpointContext.json(new Attempts().handle(contextWithAttempts(5).param("page", "2").param("size", "2")));

        assertThat(json.get("page").asInt(), is(2));
        assertThat(json.get("size").asInt(), is(2));
        assertThat(json.get("total").asInt(), is(5));
        assertThat(json.get("results").size(), is(2));
        assertThat(json.get("results").get(0).get("attemptNumber").asInt(), is(2));
    }

    @Test
    void last_page_can_be_partial_and_a_page_past_the_end_is_empty() throws Exception {
        var last = StubEndpointContext.json(new Attempts().handle(contextWithAttempts(5).param("page", "3").param("size", "2")));
        assertThat(last.get("results").size(), is(1));

        var beyond = StubEndpointContext.json(new Attempts().handle(contextWithAttempts(5).param("page", "9").param("size", "2")));
        assertThat(beyond.get("results").size(), is(0));
        assertThat(beyond.get("total").asInt(), is(5));
    }

    @Test
    void invalid_or_oversized_pagination_falls_back_to_bounds() throws Exception {
        var json = StubEndpointContext.json(new Attempts().handle(contextWithAttempts(3).param("page", "abc").param("size", "100000")));
        assertThat(json.get("page").asInt(), is(1));
        assertThat(json.get("size").asInt(), is(EndpointPage.MAX_SIZE));

        var negative = StubEndpointContext.json(new Attempts().handle(contextWithAttempts(3).param("page", "-4").param("size", "0")));
        assertThat(negative.get("page").asInt(), is(1));
        assertThat(negative.get("size").asInt(), is(EndpointPage.DEFAULT_SIZE));
    }

    @Test
    void missing_output_returns_an_empty_page() throws Exception {
        var json = StubEndpointContext.json(new Attempts().handle(new StubEndpointContext()));

        assertThat(json.get("total").asInt(), is(0));
        assertThat(json.get("results").size(), is(0));
    }

    @Test
    void missing_file_returns_an_empty_page() throws Exception {
        var json = StubEndpointContext.json(new Attempts().handle(new StubEndpointContext().output("attempts", URI)));

        assertThat(json.get("total").asInt(), is(0));
        assertThat(json.get("results").size(), is(0));
    }

    @Test
    void non_uri_output_returns_an_empty_page() throws Exception {
        var json = StubEndpointContext.json(new Attempts().handle(new StubEndpointContext().output("attempts", "not a uri")));

        assertThat(json.get("results").size(), is(0));
    }
}
