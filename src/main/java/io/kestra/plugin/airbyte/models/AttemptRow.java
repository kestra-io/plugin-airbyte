package io.kestra.plugin.airbyte.models;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
@Schema(title = "Airbyte attempt", description = "One row of the `attempts` file, describing a single attempt of the job")
public class AttemptRow {
    @Schema(title = "Attempt number", description = "Zero-based position of the attempt within the job")
    Integer attemptNumber;

    @Schema(title = "Status", description = "Attempt status")
    AttemptStatus status;

    @Schema(title = "Created at")
    Instant createdAt;

    @Schema(title = "Ended at")
    Instant endedAt;

    @Schema(title = "Bytes synced")
    Long bytesSynced;

    @Schema(title = "Records synced")
    Long recordsSynced;

    @Schema(title = "Partial success", description = "Whether Airbyte reports that the attempt committed records before failing")
    Boolean partialSuccess;

    @Schema(title = "Failures", description = "Sanitized failure reasons of the attempt")
    List<FailureSummary> failures;

    @Schema(title = "Streams", description = "Per-stream statistics of the attempt")
    List<Stream> streams;

    @Value
    @Jacksonized
    @SuperBuilder
    @Schema(title = "Airbyte attempt stream statistics")
    public static class Stream {
        @Schema(title = "Name")
        String name;

        @Schema(title = "Namespace")
        String namespace;

        @Schema(title = "Records emitted")
        Long recordsEmitted;

        @Schema(title = "Records committed")
        Long recordsCommitted;

        @Schema(title = "Records rejected")
        Long recordsRejected;

        @Schema(title = "Bytes emitted")
        Long bytesEmitted;

        @Schema(title = "Bytes committed")
        Long bytesCommitted;
    }
}
