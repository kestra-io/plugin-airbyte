package io.kestra.plugin.airbyte.models;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class AttemptFailureReason {
    AttemptFailureOrigin failureOrigin;
    AttemptFailureType failureType;
    String externalMessage;
    String internalMessage;
    String stacktrace;
    Boolean retryable;
    Long timestamp;
}
