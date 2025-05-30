package io.kestra.plugin.airbyte.connections;

import com.google.common.collect.ImmutableMap;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.junit.annotations.KestraTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class CheckStatusTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @Disabled("Unable to spawn airbyte cluster with connection configured")
    void run() throws Exception {
        RunContext runContext = runContextFactory.of(ImmutableMap.of());

        Sync task = Sync.builder()
                .url(Property.ofValue("http://localhost:8001"))
                .username(Property.ofValue("airbyte"))
                .password(Property.ofValue("password"))
                .wait(Property.ofValue(false))
                .connectionId(Property.ofValue("571304a1-498f-4382-b2ff-e791291b6363"))
                .build();

        Sync.Output runOutput = task.run(runContext);

        CheckStatus checkStatus = CheckStatus.builder()
                        .url(Property.ofValue("http://localhost:8001"))
                        .username(Property.ofValue("airbyte"))
                        .password(Property.ofValue("password"))
                        .jobId(Property.ofValue(runOutput.getJobId().toString()))
                        .maxDuration(Property.ofValue(Duration.ofMinutes(60)))
                        .build();

        CheckStatus.Output checkStatusOutput = checkStatus.run(runContext);

        assertThat(checkStatusOutput, is(notNullValue()));
        assertThat(checkStatusOutput.getFinalJobStatus(), is(notNullValue()));
    }
}
