package io.kestra.plugin.airbyte.endpoints;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class SnapshotTest {
    private static final String URI = "kestra:///ns/flow/executions/exec/tasks/sync/taskrun/snapshot.json";

    private static final String SNAPSHOT = """
        {
          "connection": {
            "connectionId": "c1",
            "syncCatalog": { "streams": [
              { "stream": { "name": "users", "namespace": "public" },
                "config": { "selected": true, "syncMode": "incremental", "destinationSyncMode": "append_dedup", "cursorField": ["updated_at"], "primaryKey": [["id"]] } },
              { "stream": { "name": "Orders", "namespace": "public" }, "config": { "selected": false, "syncMode": "full_refresh" } }
            ] }
          },
          "source": { "sourceId": "s1", "name": "pg", "connectionConfiguration": { "password": "hunter2" } },
          "destination": { "destinationId": "d1" }
        }
        """;

    private static StubEndpointContext context() {
        return new StubEndpointContext().output("connectionSnapshot", URI).file(URI, SNAPSHOT.getBytes());
    }

    @Test
    void returns_streams_with_their_configuration() throws Exception {
        var response = new Snapshot().handle(context());
        var json = StubEndpointContext.json(response);

        assertThat(json.get("total").asInt(), is(2));
        var users = json.get("streams").get(0);
        assertThat(users.get("stream").get("name").asText(), is("users"));
        assertThat(users.get("config").get("syncMode").asText(), is("incremental"));
        assertThat(users.get("config").get("cursorField").get(0).asText(), is("updated_at"));
        assertThat(users.get("config").get("primaryKey").get(0).get(0).asText(), is("id"));
        assertThat(new String(response.body()), not(containsString("hunter2")));
    }

    @Test
    void filters_streams_by_name_ignoring_case() throws Exception {
        var json = StubEndpointContext.json(new Snapshot().handle(context().param("name", "ORDER")));

        assertThat(json.get("total").asInt(), is(1));
        assertThat(json.get("streams").get(0).get("stream").get("name").asText(), is("Orders"));
    }

    @Test
    void unmatched_filter_returns_no_streams() throws Exception {
        var json = StubEndpointContext.json(new Snapshot().handle(context().param("name", "nope")));

        assertThat(json.get("total").asInt(), is(0));
        assertThat(json.get("streams").size(), is(0));
    }

    @Test
    void missing_output_or_file_returns_no_streams() throws Exception {
        assertThat(StubEndpointContext.json(new Snapshot().handle(new StubEndpointContext())).get("total").asInt(), is(0));
        assertThat(StubEndpointContext.json(new Snapshot().handle(new StubEndpointContext().output("connectionSnapshot", URI))).get("streams").size(), is(0));
    }

    @Test
    void snapshot_without_catalog_returns_no_streams() throws Exception {
        var context = new StubEndpointContext().output("connectionSnapshot", URI).file(URI, "{\"connection\":{}}".getBytes());

        assertThat(StubEndpointContext.json(new Snapshot().handle(context)).get("total").asInt(), is(0));
    }
}
