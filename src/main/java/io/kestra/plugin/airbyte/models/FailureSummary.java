package io.kestra.plugin.airbyte.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
@Schema(title = "Airbyte failure reason", description = "Sanitized failure reason of an Airbyte attempt. Internal messages and stack traces are never kept")
public class FailureSummary {
    static final int MAX_MESSAGE_LENGTH = 2048;

    @Schema(title = "Origin", description = "Airbyte component that failed, for example `source` or `destination`")
    AttemptFailureOrigin origin;

    @Schema(title = "Type", description = "Failure category reported by Airbyte, for example `config_error` or `system_error`")
    AttemptFailureType type;

    @Schema(title = "External message", description = "User-facing failure message, truncated to 2 KiB")
    String externalMessage;

    @Schema(title = "Retryable", description = "Whether Airbyte considers the failure retryable")
    Boolean retryable;

    @Schema(title = "Stream", description = "Stream affected by the failure, formatted as `namespace.name` or `name`. Absent when the failure is not tied to a stream")
    String stream;

    public static FailureSummary from(AttemptFailureReason reason) {
        var descriptor = reason.getStreamDescriptor();
        String stream = null;
        if (descriptor != null && descriptor.getName() != null) {
            stream = descriptor.getNamespace() == null ? descriptor.getName() : descriptor.getNamespace() + "." + descriptor.getName();
        }

        return FailureSummary.builder()
            .origin(reason.getFailureOrigin())
            .type(reason.getFailureType())
            .externalMessage(truncate(reason.getExternalMessage()))
            .retryable(reason.getRetryable())
            .stream(stream)
            .build();
    }

    static String truncate(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH) + "... [truncated]";
    }
}
