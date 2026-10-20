package io.kestra.plugin.airbyte.cloud.jobs;

import org.junit.jupiter.api.Test;

import com.airbyte.api.models.shared.JobStatusEnum;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class AbstractTriggerTest {
    @Test
    void incompleteIsNotAnEndedStatus() {
        assertThat(AbstractTrigger.isEnded(JobStatusEnum.INCOMPLETE), is(false));
        assertThat(AbstractTrigger.isEnded(JobStatusEnum.PENDING), is(false));
        assertThat(AbstractTrigger.isEnded(JobStatusEnum.RUNNING), is(false));
    }

    @Test
    void finalStatusesAreEnded() {
        assertThat(AbstractTrigger.isEnded(JobStatusEnum.FAILED), is(true));
        assertThat(AbstractTrigger.isEnded(JobStatusEnum.CANCELLED), is(true));
        assertThat(AbstractTrigger.isEnded(JobStatusEnum.SUCCEEDED), is(true));
    }
}
