package io.kestra.plugin.airbyte;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.assets.AssetIdentifier;
import io.kestra.core.models.assets.AssetsDeclaration;
import io.kestra.core.models.assets.External;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.AssetFailureBehavior;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.AssetEmitter;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextProperty;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.airbyte.models.Connection;
import io.kestra.plugin.airbyte.models.ConnectionSnapshot;
import io.kestra.plugin.airbyte.models.ConnectionStream;
import io.kestra.plugin.airbyte.models.Destination;
import io.kestra.plugin.airbyte.models.DestinationLocation;
import io.kestra.plugin.airbyte.models.Source;
import io.kestra.plugin.airbyte.models.StreamConfig;
import io.kestra.plugin.airbyte.models.StreamDescriptor;
import io.kestra.plugin.airbyte.models.SyncMetadata;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private static ConnectionSnapshot located(String type, DestinationLocation location, String namespaceDefinition, String prefix, ConnectionStream... streams) {
        var base = snapshot(namespaceDefinition, null, prefix, streams);
        return ConnectionSnapshot.builder()
            .connection(base.getConnection())
            .source(Source.builder().sourceId("src-1").name("production db").sourceName("Postgres").build())
            .destination(Destination.builder().destinationId("dst-1").name("warehouse").destinationName(type).build())
            .destinationLocation(location)
            .build();
    }

    private static Task task(AssetFailureBehavior behavior) throws IllegalVariableEvaluationException {
        var task = mock(Task.class);
        // A declaration without a behavior exercises the WARN default
        when(task.getAssets()).thenReturn(new AssetsDeclaration(null, null, null, behavior == null ? null : Property.ofValue(behavior)));
        return task;
    }

    private static RunContext runContext(AssetFailureBehavior behavior, AssetEmitter emitter, Logger logger) throws Exception {
        var runContext = mock(RunContext.class);
        when(runContext.logger()).thenReturn(logger);
        when(runContext.assets()).thenReturn(emitter);
        var property = mock(RunContextProperty.class);
        when(property.as(AssetFailureBehavior.class)).thenReturn(Optional.ofNullable(behavior));
        when(runContext.render(any(Property.class))).thenReturn(property);
        return runContext;
    }

    private static List<StreamAssets.Planned> oneAsset() {
        return StreamAssets.plan(
            snapshot("source", null, null, stream("customers", "public", null)),
            Map.of(descriptor("customers", "public"), rows(5)), 12L, "succeeded", true
        );
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

        assertThat(planned.size(), is(2));
        assertThat(planned.getFirst().output().getMetadata().get("recordsCommitted"), is(5L));
        assertThat(planned.getFirst().output().getMetadata().get("bytes"), is(50L));
        assertThat(planned.get(1).output().getMetadata().get("recordsCommitted"), is(0L));
        assertThat(StreamAssets.committed(null).isEmpty(), is(true));
    }

    @Test
    void zero_row_streams_are_emitted_only_when_the_job_succeeded() {
        var snapshot = snapshot("source", null, null, stream("customers", "public", null), stream("empty", "public", null), stream("absent", "public", null));
        var committed = Map.of(descriptor("customers", "public"), rows(5), descriptor("empty", "public"), rows(0));

        var succeeded = StreamAssets.plan(snapshot, committed, 12L, "succeeded", true);
        var warning = StreamAssets.plan(snapshot, committed, 12L, "failed", false);

        assertThat(succeeded.stream().map(asset -> asset.output().getDisplayName()).toList(), contains("customers", "empty"));
        var empty = succeeded.get(1).output().getMetadata();
        assertThat(empty.get("recordsCommitted"), is(0L));
        assertThat(empty.get("lastJobId"), is(12L));
        assertThat(empty.get("lastJobStatus"), is("succeeded"));
        assertThat(warning.stream().map(asset -> asset.output().getDisplayName()).toList(), contains("customers"));
    }

    private static ConnectionSnapshot withSource(String sourceType, DestinationLocation sourceLocation, String namespaceDefinition, ConnectionStream... streams) {
        var base = snapshot(namespaceDefinition, null, null, streams);
        return ConnectionSnapshot.builder()
            .connection(base.getConnection())
            .source(Source.builder().sourceId("src-1").name("production db").sourceName(sourceType).build())
            .destination(base.getDestination())
            .sourceLocation(sourceLocation)
            .build();
    }

    private static AssetIdentifier input(ConnectionSnapshot snapshot, String stream, String namespace) {
        return StreamAssets.plan(snapshot, Map.of(descriptor(stream, namespace), rows(5)), 12L, "succeeded", true).getFirst().input();
    }

    @Test
    void database_sources_give_table_inputs() {
        var postgres = input(withSource("Postgres", new DestinationLocation("shop", null, false), "source", stream("customers", "public", null)), "customers", "public");
        var snowflake = input(withSource("Snowflake", new DestinationLocation("SHOP", "PUBLIC", false), "source", stream("customers", "SALES", null)), "customers", "SALES");
        var bigquery = input(withSource("BigQuery", new DestinationLocation("my-project", "raw", false), "source", stream("customers", "events", null)), "customers", "events");

        assertThat(postgres.id(), is("shop.public.customers"));
        assertThat(postgres.type(), is(StreamAssets.TABLE_ASSET_TYPE));
        assertThat(postgres.namespace(), nullValue());
        assertThat(snowflake.id(), is("SHOP.SALES.customers"));
        assertThat(bigquery.id(), is("my-project.events.customers"));
    }

    @Test
    void schemaless_database_sources_use_the_namespace_as_database() {
        var mysql = withSource("MySQL", new DestinationLocation("shop", null, true), "source", stream("customers", "sales", null), stream("orders", null, null));

        assertThat(input(mysql, "customers", "sales").id(), is("sales.customers"));
        assertThat(input(mysql, "orders", null).id(), is("shop.orders"));
        assertThat(input(mysql, "orders", null).type(), is(StreamAssets.TABLE_ASSET_TYPE));
    }

    @Test
    void source_default_schema_is_used_without_a_stream_namespace() {
        var snowflake = withSource("Snowflake", new DestinationLocation("SHOP", "PUBLIC", false), "source", stream("customers", null, null));

        assertThat(input(snowflake, "customers", null).id(), is("SHOP.PUBLIC.customers"));
    }

    @Test
    void api_and_unresolved_sources_keep_the_external_input() {
        var api = input(withSource("Stripe", null, "source", stream("customers", null, null)), "customers", null);
        var noSchema = input(withSource("Postgres", new DestinationLocation("shop", null, false), "source", stream("customers", null, null)), "customers", null);

        assertThat(api.id(), is("src-1.customers"));
        assertThat(api.type(), is(External.ASSET_TYPE));
        assertThat(noSchema.id(), is("src-1.customers"));
        assertThat(noSchema.type(), is(External.ASSET_TYPE));
    }

    @Test
    void source_locations_are_read_per_connector_and_masked_values_ignored() {
        var config = Map.of("database", "shop", "project_id", "proj", "dataset_id", "ds", "password", "secret");

        assertThat(DestinationLocations.parse("Postgres", config).orElseThrow(), is(new DestinationLocation("shop", null, false)));
        assertThat(DestinationLocations.parse("BigQuery", config).orElseThrow(), is(new DestinationLocation("proj", "ds", false)));
        assertThat(DestinationLocations.parse("Stripe", config).isPresent(), is(false));
        assertThat(DestinationLocations.parse("MySQL", Map.of("database", "********")).isPresent(), is(false));
    }

    @Test
    void snapshot_json_never_contains_the_source_location() throws Exception {
        var json = JacksonMapper.ofJson().writeValueAsString(
            withSource("Postgres", new DestinationLocation("shopdb", "public", false), "source", stream("customers", "public", null))
        );

        assertThat(json, not(containsString("sourceLocation")));
        assertThat(json, not(containsString("shopdb")));
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
        StreamAssets.emit(runContext, task(null), "conn-1", () -> planned);

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
        var state = StreamAssets.emit(runContext, task(null), "conn-1", () -> planned);

        assertThat(state.isPresent(), is(false));
        verify(emitter, times(1)).emit(any());
        verify(logger, never()).warn(anyString(), any(Object.class), any(Object.class));
    }

    @Test
    void emit_fails_the_task_under_fail_behavior() throws Exception {
        var emitter = mock(AssetEmitter.class);
        doThrow(new IllegalArgumentException("invalid asset")).when(emitter).emit(any());

        var exception = assertThrows(
            IllegalStateException.class,
            () -> StreamAssets.emit(runContext(AssetFailureBehavior.FAIL, emitter, mock(Logger.class)), task(AssetFailureBehavior.FAIL), "conn-1", StreamAssetsTest::oneAsset)
        );

        assertThat(
            exception.getMessage(),
            is(
                "Failed to emit asset 'dst-1.public.customers' for connection conn-1: invalid asset. "
                    + "Set assets.assetFailureBehavior to WARN or IGNORE to not fail the task."
            )
        );
    }

    @Test
    void emit_ends_in_warning_under_warn_behavior_and_by_default() throws Exception {
        var emitter = mock(AssetEmitter.class);
        doThrow(new IllegalArgumentException("invalid asset")).when(emitter).emit(any());
        var logger = mock(Logger.class);

        var explicit = StreamAssets.emit(runContext(AssetFailureBehavior.WARN, emitter, logger), task(AssetFailureBehavior.WARN), "conn-1", StreamAssetsTest::oneAsset);
        var byDefault = StreamAssets.emit(runContext(null, emitter, logger), task(null), "conn-1", StreamAssetsTest::oneAsset);

        assertThat(explicit, is(Optional.of(State.Type.WARNING)));
        assertThat(byDefault, is(Optional.of(State.Type.WARNING)));
        verify(logger, times(2)).warn(anyString());
    }

    @Test
    void emit_is_silent_under_ignore_behavior() throws Exception {
        var emitter = mock(AssetEmitter.class);
        doThrow(new IllegalArgumentException("invalid asset")).when(emitter).emit(any());
        var logger = mock(Logger.class);

        var state = StreamAssets.emit(runContext(AssetFailureBehavior.IGNORE, emitter, logger), task(AssetFailureBehavior.IGNORE), "conn-1", StreamAssetsTest::oneAsset);

        assertThat(state.isPresent(), is(false));
        verify(logger, never()).warn(anyString());
    }

    @Test
    void emit_applies_the_behavior_when_building_the_assets_fails() throws Exception {
        var emitter = mock(AssetEmitter.class);
        Supplier<List<StreamAssets.Planned>> failing = () ->
        {
            throw new IllegalStateException("boom");
        };

        var exception = assertThrows(
            IllegalStateException.class,
            () -> StreamAssets.emit(runContext(AssetFailureBehavior.FAIL, emitter, mock(Logger.class)), task(AssetFailureBehavior.FAIL), "conn-1", failing)
        );
        assertThat(
            exception.getMessage(),
            is(
                "Failed to build the assets for connection conn-1: boom. "
                    + "Set assets.assetFailureBehavior to WARN or IGNORE to not fail the task."
            )
        );
        assertThat(
            StreamAssets.emit(runContext(AssetFailureBehavior.WARN, emitter, mock(Logger.class)), task(AssetFailureBehavior.WARN), "conn-1", failing),
            is(Optional.of(State.Type.WARNING))
        );
        verify(emitter, never()).emit(any());
    }

    @Test
    void emit_does_nothing_without_an_assets_declaration() throws Exception {
        var emitter = mock(AssetEmitter.class);
        var logger = mock(Logger.class);
        var planner = mock(Supplier.class);
        when(planner.get()).thenThrow(new IllegalStateException("boom"));

        var state = StreamAssets.emit(runContext(null, emitter, logger), mock(Task.class), "conn-1", planner);

        assertThat(state.isPresent(), is(false));
        verify(planner, never()).get();
        verify(emitter, never()).emit(any());
        verify(logger, never()).warn(anyString());
    }

    @Test
    void emit_falls_back_to_warn_when_the_behavior_cannot_be_rendered() throws Exception {
        var emitter = mock(AssetEmitter.class);
        doThrow(new IllegalArgumentException("invalid asset")).when(emitter).emit(any());
        var runContext = runContext(null, emitter, mock(Logger.class));
        when(runContext.render(any(Property.class))).thenThrow(new IllegalStateException("bad"));

        assertThat(StreamAssets.emit(runContext, task(AssetFailureBehavior.FAIL), "conn-1", StreamAssetsTest::oneAsset), is(Optional.of(State.Type.WARNING)));
    }

    @Test
    void postgres_location_gives_database_schema_table_ids() {
        var planned = StreamAssets.plan(
            located("Postgres", new DestinationLocation("analytics", "public", false), "destination", "raw_", stream("customers", "public", null)),
            Map.of(descriptor("customers", "public"), rows(5)), 12L, "succeeded", true
        );

        var table = planned.getFirst().output();
        assertThat(table.getId(), is("analytics.public.raw_customers"));
        assertThat(table.getMetadata().get("destinationId"), is("dst-1"));
        assertThat(table.getMetadata().get("database"), is("analytics"));
        assertThat(table.getMetadata().get("schema"), is("public"));
        assertThat(table.getMetadata().get("name"), is("raw_customers"));
        assertThat(table.getMetadata().get("sourceId"), is("src-1"));
        assertThat(table.getMetadata().get("sourceName"), is("production db"));
        assertThat(table.getMetadata().get("sourceType"), is("Postgres"));
        assertThat(planned.getFirst().input().id(), is("src-1.public.customers"));
    }

    @Test
    void stream_namespace_wins_over_the_default_schema() {
        var planned = StreamAssets.plan(
            located("Snowflake", new DestinationLocation("ANALYTICS", "PUBLIC", false), "source", null, stream("customers", "sales", "ORDERS_TBL")),
            Map.of(descriptor("customers", "sales"), rows(5)), 12L, "succeeded", true
        );

        assertThat(planned.getFirst().output().getId(), is("ANALYTICS.sales.ORDERS_TBL"));
    }

    @Test
    void bigquery_location_uses_project_and_dataset() {
        var planned = StreamAssets.plan(
            located("BigQuery", new DestinationLocation("my-project", "raw", false), "destination", null, stream("customers", null, null)),
            Map.of(descriptor("customers", null), rows(5)), 12L, "succeeded", true
        );

        assertThat(planned.getFirst().output().getId(), is("my-project.raw.customers"));
    }

    @Test
    void schemaless_destination_gives_database_table_ids() {
        var planned = StreamAssets.plan(
            located("MySQL", new DestinationLocation("shop", null, true), "source", null, stream("customers", "sales", null)),
            Map.of(descriptor("customers", "sales"), rows(5)), 12L, "succeeded", true
        );
        var noNamespace = StreamAssets.plan(
            located("MySQL", new DestinationLocation("shop", null, true), "destination", null, stream("customers", "sales", null)),
            Map.of(descriptor("customers", "sales"), rows(5)), 12L, "succeeded", true
        );

        assertThat(planned.getFirst().output().getId(), is("sales.customers"));
        assertThat(planned.getFirst().output().getMetadata().get("database"), is("sales"));
        assertThat(noNamespace.getFirst().output().getId(), is("shop.customers"));
        assertThat(noNamespace.getFirst().output().getMetadata().get("database"), is("shop"));
    }

    @Test
    void custom_format_namespace_is_used_with_a_location() {
        var base = snapshot("customformat", "raw_${SOURCE_NAMESPACE}", null, stream("customers", "sales", null));
        var snapshot = ConnectionSnapshot.builder()
            .connection(base.getConnection())
            .destination(base.getDestination())
            .destinationLocation(new DestinationLocation("analytics", "public", false))
            .build();

        var planned = StreamAssets.plan(snapshot, Map.of(descriptor("customers", "sales"), rows(5)), 12L, "succeeded", true);

        assertThat(planned.getFirst().output().getId(), is("analytics.raw_sales.customers"));
    }

    @Test
    void unresolved_location_falls_back_to_the_destination_uuid() {
        var planned = StreamAssets.plan(
            located("SomethingNew", null, "source", null, stream("customers", "public", null)),
            Map.of(descriptor("customers", "public"), rows(5)), 12L, "succeeded", true
        );
        var noSchema = StreamAssets.plan(
            located("Postgres", new DestinationLocation("analytics", null, false), "destination", null, stream("customers", "public", null)),
            Map.of(descriptor("customers", "public"), rows(5)), 12L, "succeeded", true
        );

        assertThat(planned.getFirst().output().getId(), is("dst-1.public.customers"));
        assertThat(noSchema.getFirst().output().getId(), is("dst-1.customers"));
        assertThat(planned.getFirst().output().getMetadata().get("destinationId"), is("dst-1"));
    }

    @Test
    void ids_are_sanitized_and_capped() {
        assertThat(StreamAssets.sanitize("my project", "raw.data", "tab le"), is("my_project.raw_data.tab_le"));
        assertThat(StreamAssets.sanitize("_db", "schema", "table"), is("db.schema.table"));

        var capped = StreamAssets.sanitize("db", "schema", "t".repeat(200));
        assertThat(capped.length(), is(150));
        assertThat(capped, matchesPattern("db\\.schema\\.t+_[0-9a-f]{8}"));
        assertThat(StreamAssets.sanitize("db", "schema", "t".repeat(200)), is(capped));
    }

    @Test
    void destination_locations_are_read_per_connector() {
        var config = Map.of("database", "analytics", "schema", "public", "project_id", "proj", "dataset_id", "ds", "password", "secret");

        assertThat(DestinationLocations.parse("Postgres", config).orElseThrow(), is(new DestinationLocation("analytics", "public", false)));
        assertThat(DestinationLocations.parse("Snowflake", config).orElseThrow(), is(new DestinationLocation("analytics", "public", false)));
        assertThat(DestinationLocations.parse("MS SQL Server", config).orElseThrow(), is(new DestinationLocation("analytics", "public", false)));
        assertThat(DestinationLocations.parse("bigquery", config).orElseThrow(), is(new DestinationLocation("proj", "ds", false)));
        assertThat(DestinationLocations.parse("MySQL", config).orElseThrow(), is(new DestinationLocation("analytics", null, true)));
        assertThat(DestinationLocations.parse("S3", config).isPresent(), is(false));
        assertThat(DestinationLocations.parse("Postgres", null).isPresent(), is(false));
        assertThat(DestinationLocations.parse("Postgres", "not a config").isPresent(), is(false));
    }

    @Test
    void masked_location_values_are_ignored() {
        assertThat(DestinationLocations.parse("Postgres", Map.of("database", "**********", "schema", "public")).isPresent(), is(false));
        assertThat(DestinationLocations.parse("Postgres", Map.of("database", "analytics", "schema", "******")).orElseThrow().schema(), nullValue());
    }

    @Test
    void snapshot_json_never_contains_the_location_or_config_keys() throws Exception {
        var json = JacksonMapper.ofJson().writeValueAsString(
            located("Postgres", new DestinationLocation("analytics", "public", false), "source", null, stream("customers", "public", null))
        );

        assertThat(json, not(containsString("destinationLocation")));
        assertThat(json, not(containsString("analytics")));
        assertThat(json, not(containsString("connectionConfiguration")));
        assertThat(json, not(containsString("password")));
    }
}
