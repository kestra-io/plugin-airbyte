package io.kestra.plugin.airbyte.endpoints;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class EndpointPageTest {
    @Test
    void total_is_exact_below_the_cap() {
        var page = EndpointPage.of(new StubEndpointContext(), IntStream.range(0, 120).boxed());

        assertThat(page.total(), is(120L));
        assertThat(page.totalCapped(), is(false));
        assertThat(page.results().size(), is(50));
    }

    @Test
    void total_is_exact_at_exactly_the_cap() {
        var page = EndpointPage.of(new StubEndpointContext(), IntStream.range(0, EndpointPage.MAX_COUNTED).boxed());

        assertThat(page.total(), is((long) EndpointPage.MAX_COUNTED));
        assertThat(page.totalCapped(), is(false));
    }

    @Test
    void counting_stops_at_the_cap_and_reports_it() {
        var read = new int[1];
        var rows = IntStream.range(0, EndpointPage.MAX_COUNTED * 3).boxed().peek(row -> read[0]++);
        var page = EndpointPage.of(new StubEndpointContext(), rows);

        assertThat(page.total(), is((long) EndpointPage.MAX_COUNTED));
        assertThat(page.totalCapped(), is(true));
        assertThat(page.results().size(), is(50));
        // hasNext() pre-fetches the one row that proves the total is capped
        assertThat(read[0], is(EndpointPage.MAX_COUNTED + 1));
    }

    @Test
    void a_page_beyond_the_cap_is_still_filled() {
        var context = new StubEndpointContext().param("page", "250").param("size", "50");
        var page = EndpointPage.of(context, IntStream.range(0, EndpointPage.MAX_COUNTED * 3).boxed());

        assertThat(page.results().size(), is(50));
        assertThat(page.results().getFirst(), is(249 * 50));
        assertThat(page.totalCapped(), is(true));
        assertThat(page.total(), is(250L * 50));
    }
}
