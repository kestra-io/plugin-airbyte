package io.kestra.plugin.airbyte.cloud;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.airbyte.api.models.shared.ConnectionResponse;
import com.airbyte.api.models.shared.ConnectionScheduleResponse;
import com.airbyte.api.models.shared.ConnectionStatusEnum;
import com.airbyte.api.models.shared.ConnectionSyncModeEnum;
import com.airbyte.api.models.shared.DestinationConfiguration;
import com.airbyte.api.models.shared.DestinationPostgres;
import com.airbyte.api.models.shared.DestinationResponse;
import com.airbyte.api.models.shared.NamespaceDefinitionEnum;
import com.airbyte.api.models.shared.ScheduleTypeWithBasicEnum;
import com.airbyte.api.models.shared.SourceConfiguration;
import com.airbyte.api.models.shared.SourceMysql;
import com.airbyte.api.models.shared.SourceMysqlScanChangesWithUserDefinedCursor;
import com.airbyte.api.models.shared.SourceMysqlUpdateMethod;
import com.airbyte.api.models.shared.SourceResponse;
import com.airbyte.api.models.shared.StreamConfiguration;
import com.airbyte.api.models.shared.StreamConfigurations;

import io.kestra.core.models.assets.External;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.airbyte.ConnectionSnapshots;
import io.kestra.plugin.airbyte.StreamAssets;
import io.kestra.plugin.airbyte.models.ConnectionSummary;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudSnapshotsTest {
    private static ConnectionResponse connection(NamespaceDefinitionEnum definition, String format) {
        var response = mock(ConnectionResponse.class);
        when(response.connectionId()).thenReturn("conn-1");
        when(response.name()).thenReturn("pg to snowflake");
        when(response.workspaceId()).thenReturn("ws-1");
        when(response.sourceId()).thenReturn("src-1");
        when(response.destinationId()).thenReturn("dst-1");
        when(response.status()).thenReturn(ConnectionStatusEnum.ACTIVE);
        when(response.schedule()).thenReturn(new ConnectionScheduleResponse(ScheduleTypeWithBasicEnum.MANUAL));
        when(response.namespaceDefinition()).thenReturn(Optional.ofNullable(definition));
        when(response.namespaceFormat()).thenReturn(Optional.ofNullable(format));
        when(response.prefix()).thenReturn(Optional.of("raw_"));
        when(response.configurations()).thenReturn(
            new StreamConfigurations(
                Optional.of(
                    List.of(
                        new StreamConfiguration("customers"),
                        new StreamConfiguration(
                            Optional.of(List.of("updated_at")), Optional.empty(), "orders", Optional.of(List.of(List.of("id"))), Optional.empty(),
                            Optional.of(ConnectionSyncModeEnum.INCREMENTAL_APPEND)
                        )
                    )
                )
            )
        );
        return response;
    }

    private static SourceResponse source() {
        var response = mock(SourceResponse.class);
        when(response.sourceId()).thenReturn("src-1");
        when(response.name()).thenReturn("production db");
        when(response.sourceType()).thenReturn("postgres");
        when(response.definitionId()).thenReturn("def-src");
        when(response.workspaceId()).thenReturn("ws-1");
        return response;
    }

    private static DestinationResponse destination() {
        var response = mock(DestinationResponse.class);
        when(response.destinationId()).thenReturn("dst-1");
        when(response.name()).thenReturn("warehouse");
        when(response.destinationType()).thenReturn("snowflake");
        when(response.definitionId()).thenReturn("def-dst");
        when(response.workspaceId()).thenReturn("ws-1");
        return response;
    }

    @Test
    void maps_the_allow_listed_fields_and_only_reads_the_connector_locations() throws Exception {
        var source = source();
        var destination = destination();

        var snapshot = CloudSnapshots.toSnapshot(connection(NamespaceDefinitionEnum.DESTINATION, null), source, destination);

        verify(source).configuration();
        verify(destination).configuration();

        var summary = ConnectionSummary.from(snapshot);
        assertThat(summary.getConnectionId(), is("conn-1"));
        assertThat(summary.getWorkspaceId(), is("ws-1"));
        assertThat(summary.getStatus(), is("active"));
        assertThat(summary.getScheduleType(), is("manual"));
        assertThat(summary.getSourceType(), is("postgres"));
        assertThat(summary.getDestinationType(), is("snowflake"));
        assertThat(summary.getStreamCount(), is(2));

        var orders = snapshot.getConnection().getSyncCatalog().getStreams().get(1);
        assertThat(orders.getStream().getName(), is("orders"));
        assertThat(orders.getConfig().getSyncMode(), is("incremental_append"));
        assertThat(orders.getConfig().getCursorField(), contains("updated_at"));
        assertThat(orders.getConfig().getPrimaryKey(), contains(contains("id")));

        var json = JacksonMapper.ofJson().writeValueAsString(snapshot);
        assertThat(json, not(containsString("configuration")));
        assertThat(json, not(containsString("destinationLocation")));
    }

    @Test
    void cloud_assets_use_the_real_destination_location() throws Exception {
        var destination = destination();
        when(destination.destinationType()).thenReturn("postgres");
        when(destination.configuration()).thenReturn(
            DestinationConfiguration.of(
                DestinationPostgres.builder().host("db.example.com").database("analytics").schema("public").username("airbyte").password("s3cret").build()
            )
        );

        var snapshot = CloudSnapshots.toSnapshot(connection(NamespaceDefinitionEnum.DESTINATION, null), source(), destination);
        var planned = StreamAssets.plan(snapshot, Map.of(), 7L, "succeeded", true);

        assertThat(planned.stream().map(asset -> asset.output().getId()).toList(), contains("analytics.public.raw_customers", "analytics.public.raw_orders"));
        assertThat(planned.getFirst().output().getMetadata().get("destinationId"), is("dst-1"));

        var json = JacksonMapper.ofJson().writeValueAsString(snapshot);
        assertThat(json, not(containsString("s3cret")));
        assertThat(json, not(containsString("analytics")));
    }

    @Test
    void cloud_database_sources_give_table_inputs_without_persisting_the_location() throws Exception {
        var source = source();
        when(source.sourceType()).thenReturn("mysql");
        when(source.configuration()).thenReturn(
            SourceConfiguration.of(
                SourceMysql.builder().host("db.example.com").database("shopdb").username("airbyte").password("s3cret")
                    .replicationMethod(SourceMysqlUpdateMethod.of(SourceMysqlScanChangesWithUserDefinedCursor.builder().build())).build()
            )
        );

        var snapshot = CloudSnapshots.toSnapshot(connection(NamespaceDefinitionEnum.DESTINATION, null), source, destination());
        var input = StreamAssets.plan(snapshot, Map.of(), 7L, "succeeded", true).getFirst().input();

        assertThat(input.id(), is("shopdb.customers"));
        assertThat(input.type(), is(StreamAssets.TABLE_ASSET_TYPE));

        var json = JacksonMapper.ofJson().writeValueAsString(snapshot);
        assertThat(json, not(containsString("shopdb")));
        assertThat(json, not(containsString("s3cret")));
    }

    @Test
    void cloud_assets_leave_the_namespace_out_unless_a_custom_format_gives_one() {
        var withoutNamespace = StreamAssets.plan(
            CloudSnapshots.toSnapshot(connection(NamespaceDefinitionEnum.SOURCE, null), source(), destination()), Map.of(), 7L, "succeeded", true
        );
        assertThat(withoutNamespace.stream().map(asset -> asset.output().getId()).toList(), contains("dst-1.raw_customers", "dst-1.raw_orders"));
        assertThat(withoutNamespace.getFirst().input().id(), is("src-1.customers"));
        assertThat(withoutNamespace.getFirst().input().type(), is(External.ASSET_TYPE));

        var custom = StreamAssets.plan(
            CloudSnapshots.toSnapshot(connection(NamespaceDefinitionEnum.CUSTOM_FORMAT, "landing"), source(), destination()), Map.of(), 7L, "succeeded", true
        );
        assertThat(custom.getFirst().output().getId(), is("dst-1.landing.raw_customers"));
    }

    @Test
    void cloud_assets_ignore_the_destination_location_when_the_namespace_comes_from_the_source() {
        var destination = destination();
        when(destination.destinationType()).thenReturn("postgres");
        when(destination.configuration()).thenReturn(
            DestinationConfiguration.of(DestinationPostgres.builder().host("db.example.com").database("analytics").schema("public").username("airbyte").build())
        );

        var source = StreamAssets.plan(
            CloudSnapshots.toSnapshot(connection(NamespaceDefinitionEnum.SOURCE, null), source(), destination), Map.of(), 7L, "succeeded", true
        );
        var customWithSourceNamespace = StreamAssets.plan(
            CloudSnapshots.toSnapshot(connection(NamespaceDefinitionEnum.CUSTOM_FORMAT, "raw_${SOURCE_NAMESPACE}"), source(), destination), Map.of(), 7L, "succeeded", true
        );

        assertThat(source.stream().map(asset -> asset.output().getId()).toList(), contains("dst-1.raw_customers", "dst-1.raw_orders"));
        assertThat(customWithSourceNamespace.getFirst().output().getId(), is("dst-1.raw_customers"));
    }

    @Test
    void job_link_targets_the_cloud_timeline() {
        assertThat(
            ConnectionSnapshots.jobUrl(CloudSnapshots.WEBAPP_URL, "ws-1", "conn-1", 99L, null),
            is("https://cloud.airbyte.com/workspaces/ws-1/connections/conn-1/timeline?openLogs=true&jobId=99")
        );
    }
}
