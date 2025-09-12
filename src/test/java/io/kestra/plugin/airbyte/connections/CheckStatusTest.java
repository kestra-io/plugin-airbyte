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
class CheckStatusTest extends AbstractAirbyteConnectionTest {

    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of(ImmutableMap.of());

        Sync task = Sync.builder()
                .url(Property.ofValue(url))
                .username(Property.ofValue(username))
                .password(Property.ofValue(password))
                .connectionId(Property.ofValue(connectionId))
                .wait(Property.ofValue(false))
                .build();

        Sync.Output runOutput = task.run(runContext);

        CheckStatus checkStatus = CheckStatus.builder()
                        .url(Property.ofValue(url))
                        .username(Property.ofValue(username))
                        .password(Property.ofValue(password))
                        .jobId(Property.ofValue(runOutput.getJobId().toString()))
                        .maxDuration(Property.ofValue(Duration.ofMinutes(60)))
                        .build();

        CheckStatus.Output checkStatusOutput = checkStatus.run(runContext);

        assertThat(checkStatusOutput, is(notNullValue()));
        assertThat(checkStatusOutput.getFinalJobStatus(), is(notNullValue()));
    }
}
