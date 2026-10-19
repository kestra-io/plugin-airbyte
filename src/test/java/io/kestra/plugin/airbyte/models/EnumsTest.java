package io.kestra.plugin.airbyte.models;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

class EnumsTest {
    @Test
    void jobStatusCoversAirbyteValues() {
        for (var value : List.of("pending", "queued", "running", "incomplete", "failed", "succeeded", "cancelled")) {
            assertThat(value, JobStatus.fromValue(value), is(notNullValue()));
        }
        assertThat(JobStatus.fromValue("some_future_status"), is(nullValue()));
    }

    @Test
    void jobConfigTypeCoversAirbyteValues() {
        for (var value : List.of("check_connection_source", "check_connection_destination", "discover_schema", "get_spec", "sync", "reset_connection", "refresh", "clear")) {
            assertThat(value, JobConfigType.fromValue(value), is(notNullValue()));
        }
        assertThat(JobConfigType.fromValue("some_future_type"), is(nullValue()));
    }

    @Test
    void attemptFailureTypeCoversAirbyteValues() {
        for (var value : List.of("config_error", "system_error", "manual_cancellation", "refresh_schema", "heartbeat_timeout", "destination_timeout", "transient_error")) {
            assertThat(value, AttemptFailureType.fromValue(value), is(notNullValue()));
        }
        assertThat(AttemptFailureType.fromValue("some_future_type"), is(nullValue()));
    }

    @Test
    void attemptFailureOriginCoversAirbyteValues() {
        for (var value : List.of("source", "destination", "replication", "persistence", "normalization", "dbt", "airbyte_platform", "unknown")) {
            assertThat(value, AttemptFailureOrigin.fromValue(value), is(notNullValue()));
        }
        assertThat(AttemptFailureOrigin.fromValue("some_future_origin"), is(nullValue()));
    }
}
