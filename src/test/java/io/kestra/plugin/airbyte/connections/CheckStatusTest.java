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
                .url(Property.of("http://localhost:8001"))
                .username(Property.of("airbyte"))
                .password(Property.of("password"))
                .wait(Property.of(false))
                .connectionId(Property.of("571304a1-498f-4382-b2ff-e791291b6363"))
                .build();

        Sync.Output runOutput = task.run(runContext);

        CheckStatus checkStatus = CheckStatus.builder()
                        .url(Property.of("http://localhost:8001"))
                        .username(Property.of("airbyte"))
                        .password(Property.of("password"))
                        .jobId(Property.of(runOutput.getJobId().toString()))
                        .maxDuration(Property.of(Duration.ofMinutes(60)))
                        .build();

        CheckStatus.Output checkStatusOutput = checkStatus.run(runContext);

        assertThat(checkStatusOutput, is(notNullValue()));
        assertThat(checkStatusOutput.getFinalJobStatus(), is(notNullValue()));
    }
}
