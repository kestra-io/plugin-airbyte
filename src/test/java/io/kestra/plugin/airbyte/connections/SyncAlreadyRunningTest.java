package io.kestra.plugin.airbyte.connections;

import com.google.common.collect.ImmutableMap;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@MicronautTest
class SyncAlreadyRunningTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @Disabled("Unable to spawn airbyte cluster with connection configured")
    void run() throws Exception {
        RunContext runContext = runContextFactory.of(ImmutableMap.of());

        Sync task = Sync.builder()
            .url("http://localhost:8001")
            .username("airbyte")
            .password("password")
            .connectionId("6f8804bf-9327-4634-be47-f2170c5346bd")
            .failOnActiveSync(false)
            .wait(false)
            .build();

        Sync.Output runOutput = task.run(runContext);

        Sync task2 = Sync.builder()
                .url("http://localhost:8001")
                .username("airbyte")
                .password("password")
                .connectionId("6f8804bf-9327-4634-be47-f2170c5346bd")
                .failOnActiveSync(false)
                .wait(false)
                .build();

        Sync.Output runOutput2 = task.run(runContext);

        assertThat(runOutput2.getAlreadyRunning(), is(true));
    }
}
