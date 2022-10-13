package io.kestra.plugin.airbyte.models;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class AttemptStats {
    Long recordsEmitted;
    Long bytesEmitted;
    Long stateMessagesEmitted;
    Long recordsCommitted;
}
