package io.kestra.plugin.airbyte.connections;

import com.google.common.collect.ImmutableMap;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.airbyte.connections.Sync;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@MicronautTest
class SyncTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @Disabled("Unable to spawn airbyte cluster with connection configured")
    void run() throws Exception {
        RunContext runContext = runContextFactory.of(ImmutableMap.of());

        Sync task = Sync.builder()
            .url("http://localhost:8000")
            .username("airbyte")
            .password("password")
            .connectionId("3ef5d9a0-4f16-42db-9ab5-8dd3c4822391")
            .build();

        Sync.Output runOutput = task.run(runContext);

        assertThat(runOutput, is(notNullValue()));
        assertThat(runOutput.getJobId(), is(notNullValue()));
    }
}
