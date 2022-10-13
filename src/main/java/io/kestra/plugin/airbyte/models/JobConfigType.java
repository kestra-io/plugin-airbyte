package io.kestra.plugin.airbyte.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum JobConfigType {
    CHECK_CONNECTION_SOURCE("check_connection_source"),
    CHECK_CONNECTION_DESTINATION("check_connection_destination"),
    DISCOVER_SCHEMA("discover_schema"),
    GET_SPEC("get_spec"),
    SYNC("sync"),
    RESET_CONNECTION("reset_connection");

    private String value;

    JobConfigType(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String toString() {
        return String.valueOf(value);
    }

    @JsonCreator
    public static JobConfigType fromValue(String text) {
        for (JobConfigType b : JobConfigType.values()) {
            if (String.valueOf(b.value).equals(text)) {
                return b;
            }
        }
        return null;
    }
}
