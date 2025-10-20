package io.kestra.plugin.airbyte.connections;

import com.google.common.collect.ImmutableMap;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.plugin.airbyte.AbstractAirbyteConnectionTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class SyncAlreadyRunningTest extends AbstractAirbyteConnectionTest {
    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of(ImmutableMap.of());

        Sync task = Sync.builder()
            .url(Property.ofValue(url))
            .username(Property.ofValue(username))
            .password(Property.ofValue(password))
            .connectionId(Property.ofValue(connectionId))
            .failOnActiveSync(Property.ofValue(false))
            .wait(Property.ofValue(false))
            .build();

        Sync.Output firstOut = task.run(runContext);
        assertThat(firstOut, notNullValue());

        Sync second = Sync.builder()
            .url(Property.ofValue(url))
            .username(Property.ofValue(username))
            .password(Property.ofValue(password))
            .connectionId(Property.ofValue(connectionId))
            .failOnActiveSync(Property.ofValue(false))
            .wait(Property.ofValue(false))
            .build();

        Sync.Output runOutput2 = second.run(runContext);
        assertThat(runOutput2.getAlreadyRunning(), is(true));

        // we check status until the job is finished to make other tests start sync properly
        if (firstOut.getJobId() != null) {
            CheckStatus checkStatus = CheckStatus.builder()
                .url(Property.ofValue(url))
                .username(Property.ofValue(username))
                .password(Property.ofValue(password))
                .jobId(Property.ofValue(firstOut.getJobId().toString()))
                .pollFrequency(Property.ofValue(Duration.ofSeconds(2)))
                .maxDuration(Property.ofValue(Duration.ofMinutes(10)))
                .build();

            checkStatus.run(runContext);
        }
    }
}
