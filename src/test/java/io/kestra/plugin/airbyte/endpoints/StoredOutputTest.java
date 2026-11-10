package io.kestra.plugin.airbyte.endpoints;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class StoredOutputTest {
    @Test
    void a_non_airbyte_task_run_with_a_job_id_is_rejected() {
        assertThat(StoredOutput.isAirbyteTaskRun(new StubEndpointContext().output("jobId", "bq-job-1")), is(false));
        assertThat(StoredOutput.isAirbyteTaskRun(new StubEndpointContext().output("jobId", 55L).output("job", Map.of("status", "DONE"))), is(false));
    }

    @Test
    void an_attempt_sub_task_run_is_accepted() {
        assertThat(StoredOutput.isAirbyteTaskRun(new StubEndpointContext().output("jobId", 55L).output("airbyteAttempt", 0)), is(true));
    }

    @Test
    void a_job_summary_with_the_attempt_count_is_accepted() {
        assertThat(StoredOutput.isAirbyteTaskRun(new StubEndpointContext().output("job", Map.of("attemptCount", 2))), is(true));
    }

    @Test
    void a_sync_that_does_not_wait_is_accepted() {
        var outputs = new StubEndpointContext().output("jobId", 55L).output("alreadyRunning", false).output("adopted", false);

        assertThat(StoredOutput.isAirbyteTaskRun(outputs), is(true));
    }

    @Test
    void no_outputs_is_rejected() {
        assertThat(StoredOutput.isAirbyteTaskRun(new StubEndpointContext()), is(false));
    }
}
