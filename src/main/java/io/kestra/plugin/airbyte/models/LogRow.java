package io.kestra.plugin.airbyte.models;

import java.time.Instant;

import org.slf4j.event.Level;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
@Schema(
    title = "Airbyte log event",
    description = "One row of the `airbyteLogs` file. Connector stack traces and the Airbyte `caller` details are never kept"
)
public class LogRow {
    @Schema(title = "Attempt number", description = "Number of the Airbyte attempt that produced the event, as reported by Airbyte")
    Integer attemptNumber;

    @Schema(title = "Timestamp", description = "When Airbyte recorded the event. Absent for the plain text lines returned by older Airbyte versions")
    Instant timestamp;

    @Schema(title = "Level", description = "Level reported by Airbyte. Unknown levels are stored as `INFO`")
    Level level;

    @Schema(
        title = "Log source",
        description = "Airbyte component that emitted the event: `platform`, `replication-orchestrator`, `source` or `destination`. Absent for the plain text lines returned by older Airbyte versions"
    )
    String logSource;

    @Schema(title = "Message", description = "Event message, which can span several lines. Stack trace frames are replaced by a single marker line")
    String message;

    /** Short source name, as shown in the Kestra logs: `replication-orchestrator` becomes `orchestrator`. */
    public String label() {
        return "replication-orchestrator".equals(logSource) ? "orchestrator" : logSource;
    }
}
