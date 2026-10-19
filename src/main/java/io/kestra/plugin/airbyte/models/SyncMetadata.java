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
        title = "Bytes synced",
        description = "Total number of bytes committed to the destination. Absent when Airbyte does not report per-stream committed bytes"
    )
    Long bytesSynced;

    @Schema(
        title = "Records rejected",
        description = "Total number of records the destination rejected. Absent when Airbyte does not report rejected records"
    )
    Long recordsRejected;

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

        @Schema(title = "Bytes processed", description = "Bytes emitted by the source, or committed to the destination")
        Long bytes;

        @Schema(title = "Records rejected", description = "Records rejected by the destination. Only set on destination tables")
        Long recordsRejected;
    }
}
