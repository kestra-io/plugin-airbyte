package io.kestra.plugin.airbyte.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AttemptFailureOrigin {
    SOURCE("source"),
    DESTINATION("destination"),
    REPLICATION("replication"),
    PERSISTENCE("persistence"),
    NORMALIZATION("normalization"),
    DBT("dbt"),
    AIRBYTE_PLATFORM("airbyte_platform");

    private String value;

    AttemptFailureOrigin(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String toString() {
        return String.valueOf(value);
    }

    @JsonCreator
    public static AttemptFailureOrigin fromValue(String text) {
        for (AttemptFailureOrigin b : AttemptFailureOrigin.values()) {
            if (String.valueOf(b.value).equals(text)) {
                return b;
            }
        }
        return null;
    }
}
