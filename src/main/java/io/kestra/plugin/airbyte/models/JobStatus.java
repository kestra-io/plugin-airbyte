package io.kestra.plugin.airbyte.models;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum JobStatus {
    PENDING("pending"),
    RUNNING("running"),
    INCOMPLETE("incomplete"),
    FAILED("failed"),
    SUCCEEDED("succeeded"),
    CANCELLED("cancelled");

    private String value;

    JobStatus(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    @JsonCreator
    public static JobStatus fromValue(String text) {
        for (JobStatus b : JobStatus.values()) {
            if (String.valueOf(b.value).equals(text)) {
                return b;
            }
        }
        return null;
    }
}
