package io.kestra.plugin.airbyte.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AttemptFailureType {
    CONFIG_ERROR("config_error"),
    SYSTEM_ERROR("system_error"),
    MANUAL_CANCELLATION("manual_cancellation");

    private String value;

    AttemptFailureType(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String toString() {
        return String.valueOf(value);
    }

    @JsonCreator
    public static AttemptFailureType fromValue(String text) {
        for (AttemptFailureType b : AttemptFailureType.values()) {
            if (String.valueOf(b.value).equals(text)) {
                return b;
            }
        }
        return null;
    }
}
