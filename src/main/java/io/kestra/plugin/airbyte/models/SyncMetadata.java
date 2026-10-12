package io.kestra.plugin.airbyte.models;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(title = "Airbyte sync metadata")
public class SyncMetadata {
    @Schema(
        title = "Rows synced",
        description = "Total number of records committed to the destination"
    )
    Long rowsSynced;

    @Schema(
        title = "Source tables",
        description = "Source streams affected by the sync and the number of records emitted from each one. Contains one entry per stream returned by Airbyte, so its size follows the connection's stream count"
    )
    List<Table> source;

    @Schema(
        title = "Destination tables",
        description = "Destination streams affected by the sync and the number of records committed to each one. Contains one entry per stream returned by Airbyte, so its size follows the connection's stream count"
    )
    List<Table> destination;

    @Value
    @Builder
    @Schema(title = "Airbyte table sync metadata")
    public static class Table {
        @Schema(title = "Table name")
        String name;

        @Schema(title = "Table namespace")
        String namespace;

        @Schema(title = "Rows processed")
        Long rows;
    }
}
