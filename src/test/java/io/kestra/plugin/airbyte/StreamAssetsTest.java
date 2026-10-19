package io.kestra.plugin.airbyte;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import io.kestra.core.models.assets.External;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.AssetEmitter;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.airbyte.models.Connection;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionStream;
import io.kestra.plugin.airbyte.models.Destination;
import io.kestra.plugin.airbyte.models.StreamConfig;
import io.kestra.plugin.airbyte.models.StreamDescriptor;
import io.kestra.plugin.airbyte.models.SyncMetadata;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamAssetsTest {
    private static ConnectionStream stream(String name, String namespace, String destinationObjectName) {
        return ConnectionStream.builder()
            .stream(StreamDescriptor.builder().name(name).namespace(namespace).build())
            .config(StreamConfig.builder().selected(true).syncMode("incremental").destinationSyncMode("append_dedup").destinationObjectName(destinationObjectName).build())
            .build();
    }

    private static ConnectionSnapshot snapshot(String namespaceDefinition, String namespaceFormat, String prefix, ConnectionStream... streams) {
        return ConnectionSnapshot.builder()
            .connection(
                Connection.builder()
                    .connectionId("conn-1")
                    .sourceId("src-1")
                    .destinationId("dst-1")
                    .namespaceDefinition(namespaceDefinition)
                    .namespaceFormat(namespaceFormat)
                    .prefix(prefix)
                    .syncCatalog(Connection.SyncCatalog.builder().streams(List.of(streams)).build())
                    .build()
            )
            .destination(Destination.builder().destinationId("dst-1").name("warehouse").destinationName("Snowflake").build())
            .build();
    }

    private static StreamAssets.Committed rows(long records) {
        return new StreamAssets.Committed(records, records * 10);
    }

    private static StreamDescriptor descriptor(String name, String namespace) {
        return StreamDescriptor.builder().name(name).namespace(namespace).build();
    }

    @Test
    void source_namespace_definition_uses_the_stream_namespace() {
        var planned = StreamAssets.plan(
            snapshot("source", null, null, stream("customers", "public", null)),
            Map.of(descriptor("customers", "public"), rows(5)), 12L, "succeeded", true
        );

        assertThat(planned.size(), is(1));
        var table = planned.getFirst().output();
        assertThat(table.getId(), is("dst-1.public.customers"));
        assertThat(table.getType(), is("io.kestra.plugin.ee.assets.Table"));
        assertThat(table.getDisplayName(), is("customers"));
        assertThat(table.getMetadata().get("system"), is("Snowflake"));
        assertThat(table.getMetadata().get("recordsCommitted"), is(5L));
        assertThat(table.getMetadata().get("bytes"), is(50L));
        assertThat(table.getMetadata().get("lastJobId"), is(12L));
        assertThat(table.getMetadata().get("syncMode"), is("incremental"));

        var input = planned.getFirst().input();
        assertThat(input.id(), is("src-1.public.customers"));
        assertThat(input.type(), is(External.ASSET_TYPE));
        assertThat(input.namespace(), nullValue());
    }

    @Test
    void destination_namespace_definition_leaves_the_namespace_out() {
        var planned = StreamAssets.plan(
            snapshot("destination", null, null, stream("customers", "public", null)),
            Map.of(descriptor("customers", "public"), rows(5)), 12L, "succeeded", true
        );

        assertThat(planned.getFirst().output().getId(), is("dst-1.customers"));
    }

    @Test
    void custom_format_replaces_the_source_namespace_placeholder() {
        var planned = StreamAssets.plan(
            snapshot("customformat", "raw_${SOURCE_NAMESPACE}", null, stream("customers", "public", null), stream("orders", null, null)),
            Map.of(descriptor("customers", "public"), rows(5), descriptor("orders", null), rows(1)), 12L, "succeeded", true
        );

        assertThat(planned.getFirst().output().getId(), is("dst-1.raw_public.customers"));
        assertThat(planned.get(1).output().getId(), is("dst-1.orders"));
        assertThat(planned.get(1).input().id(), is("src-1.orders"));
    }

    @Test
    void custom_format_without_placeholder_is_used_as_is() {
        var planned = StreamAssets.plan(
            snapshot("custom_format", "landing", null, stream("customers", "public", null)),
            Map.of(descriptor("customers", "public"), rows(5)), 12L, "succeeded", true
        );

        assertThat(planned.getFirst().output().getId(), is("dst-1.landing.customers"));
    }

    @Test
    void destination_object_name_and_prefix_drive_the_table_name() {
        var planned = StreamAssets.plan(
            snapshot("source", null, "raw_", stream("customers", "public", null), stream("orders", "public", "ORDERS_TBL")),
            Map.of(descriptor("customers", "public"), rows(5), descriptor("orders", "public"), rows(5)), 12L, "succeeded", true
        );

        assertThat(planned.getFirst().output().getId(), is("dst-1.public.raw_customers"));
        assertThat(planned.get(1).output().getId(), is("dst-1.public.ORDERS_TBL"));
        assertThat(planned.get(1).output().getDisplayName(), is("ORDERS_TBL"));
    }

    @Test
    void only_streams_that_committed_records_get_an_asset_even_when_the_job_failed() {
        var planned = StreamAssets.plan(
            snapshot("source", null, null, stream("customers", "public", null), stream("orders", "public", null), stream("empty", "public", null)),
            Map.of(descriptor("customers", "public"), rows(5), descriptor("empty", "public"), rows(0)), 12L, "failed", false
        );

        assertThat(planned.stream().map(asset -> asset.output().getDisplayName()).toList(), contains("customers"));
        assertThat(planned.getFirst().output().getMetadata().get("lastJobStatus"), is("failed"));
    }

    @Test
    void without_per_stream_stats_assets_are_emitted_only_when_the_job_succeeded() {
        var snapshot = snapshot("source", null, null, stream("customers", "public", null));

        assertThat(StreamAssets.plan(snapshot, Map.of(), 12L, "succeeded", true).size(), is(1));
        assertThat(StreamAssets.plan(snapshot, Map.of(), 12L, "failed", false), empty());
    }

    @Test
    void unselected_streams_are_ignored() {
        var unselected = ConnectionStream.builder()
            .stream(descriptor("customers", "public"))
            .config(StreamConfig.builder().selected(false).build())
            .build();

        assertThat(StreamAssets.plan(snapshot("source", null, null, unselected), Map.of(descriptor("customers", "public"), rows(5)), 12L, "succeeded", true), empty());
    }

    @Test
    void asset_counts_come_from_the_job_metadata() {
        var metadata = SyncMetadata.builder()
            .destination(
                List.of(
                    SyncMetadata.Table.builder().name("customers").namespace("public").rows(5L).bytes(50L).build(),
                    SyncMetadata.Table.builder().name("empty").namespace("public").rows(0L).build()
                )
            )
            .build();

        var planned = StreamAssets.plan(
            snapshot("source", null, null, stream("customers", "public", null), stream("empty", "public", null)),
            StreamAssets.committed(metadata), 12L, "succeeded", true
        );

        assertThat(planned.size(), is(1));
        assertThat(planned.getFirst().output().getMetadata().get("recordsCommitted"), is(5L));
        assertThat(planned.getFirst().output().getMetadata().get("bytes"), is(50L));
        assertThat(StreamAssets.committed(null).isEmpty(), is(true));
    }

    @Test
    void job_url_uses_the_timeline_route() {
        assertThat(
            ConnectionSnapshots.jobUrl("https://airbyte.example.com", "ws-1", "conn-1", 42L, 2),
            is("https://airbyte.example.com/workspaces/ws-1/connections/conn-1/timeline?openLogs=true&jobId=42&attemptNumber=2")
        );
        assertThat(
            ConnectionSnapshots.jobUrl("https://airbyte.example.com", "ws-1", "conn-1", 42L, null),
            is("https://airbyte.example.com/workspaces/ws-1/connections/conn-1/timeline?openLogs=true&jobId=42")
        );
        assertThat(ConnectionSnapshots.jobUrl("https://airbyte.example.com", null, "conn-1", 42L, null), nullValue());
    }

    @Test
    void emit_sends_one_lineage_per_stream() throws Exception {
        var runContext = mock(RunContext.class);
        var emitter = mock(AssetEmitter.class);
        when(runContext.logger()).thenReturn(mock(Logger.class));
        when(runContext.assets()).thenReturn(emitter);

        var planned = StreamAssets.plan(
            snapshot("source", null, null, stream("customers", "public", null), stream("orders", "public", null)),
            Map.of(descriptor("customers", "public"), rows(5), descriptor("orders", "public"), rows(2)), 12L, "succeeded", true
        );
        StreamAssets.emit(runContext, planned);

        var captor = ArgumentCaptor.forClass(AssetEmit.class);
        verify(emitter, times(2)).emit(captor.capture());
        captor.getAllValues().forEach(emit ->
        {
            assertThat(emit.inputs().size(), is(1));
            assertThat(emit.outputs().size(), is(1));
        });
        assertThat(captor.getAllValues().getFirst().inputs().getFirst().id(), is("src-1.public.customers"));
        assertThat(captor.getAllValues().getFirst().outputs().getFirst().getId(), is("dst-1.public.customers"));
        assertThat(captor.getAllValues().get(1).outputs().getFirst().getId(), is("dst-1.public.orders"));
    }

    @Test
    void emit_is_quiet_when_assets_are_not_supported() throws Exception {
        var runContext = mock(RunContext.class);
        var emitter = mock(AssetEmitter.class);
        var logger = mock(Logger.class);
        when(runContext.logger()).thenReturn(logger);
        when(runContext.assets()).thenReturn(emitter);
        doThrow(new UnsupportedOperationException()).when(emitter).emit(any());

        var planned = StreamAssets.plan(
            snapshot("source", null, null, stream("customers", "public", null), stream("orders", "public", null)),
            Map.of(descriptor("customers", "public"), rows(5), descriptor("orders", "public"), rows(2)), 12L, "succeeded", true
        );
        StreamAssets.emit(runContext, planned);

        verify(emitter, times(1)).emit(any());
        verify(logger, never()).warn(anyString(), any(Object.class), any(Object.class));
    }
}
