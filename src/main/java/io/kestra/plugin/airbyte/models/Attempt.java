package io.kestra.plugin.airbyte.models;

import java.time.Instant;
import java.util.List;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class Attempt {
    Long id;
    AttemptStatus status;
    Instant createdAt;
    Instant updatedAt;
    Instant endedAt;
    Long bytesSynced;
    Long recordsSynced;
    AttemptStats totalStats;
    List<AttemptStreamStats> streamStats;
    AttemptFailureSummary failureSummary;
}
