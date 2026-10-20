package io.kestra.plugin.airbyte.cloud.jobs;

import org.junit.jupiter.api.Test;

import com.airbyte.api.models.shared.JobStatusEnum;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

class JobOutcomeTest {
    @Test
    void failed_and_cancelled_jobs_fail_the_task_while_succeeded_and_running_do_not() {
        assertThat(AbstractTrigger.isFailure(JobStatusEnum.FAILED), is(true));
        assertThat(AbstractTrigger.isFailure(JobStatusEnum.CANCELLED), is(true));
        assertThat(AbstractTrigger.isFailure(JobStatusEnum.SUCCEEDED), is(false));
        assertThat(AbstractTrigger.isFailure(JobStatusEnum.RUNNING), is(false));
    }

    @Test
    void failure_message_says_what_happened_and_where_to_look() {
        var message = AbstractTrigger.failureMessage(12L, JobStatusEnum.FAILED, "https://cloud.airbyte.com/workspaces/ws/connections/c/timeline?openLogs=true&jobId=12");
        assertThat(message, containsString("Airbyte Cloud job 12 ended failed"));
        assertThat(message, containsString("See https://cloud.airbyte.com"));
        assertThat(AbstractTrigger.failureMessage(12L, JobStatusEnum.CANCELLED, null), containsString("Check the job logs in Airbyte Cloud"));
    }

    @Test
    void only_sync_declares_assets() {
        assertThat(Sync.builder().build().emitsAssets(), is(true));
        assertThat(Reset.builder().build().emitsAssets(), is(false));
    }
}
