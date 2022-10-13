package io.kestra.plugin.airbyte.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AttemptStatus {
    RUNNING("running"),
    FAILED("failed"),
    SUCCEEDED("succeeded");

    private String value;

    AttemptStatus(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String toString() {
        return String.valueOf(value);
    }

    @JsonCreator
    public static AttemptStatus fromValue(String text) {
        for (AttemptStatus b : AttemptStatus.values()) {
            if (String.valueOf(b.value).equals(text)) {
                return b;
            }
        }
        return null;
    }
}
