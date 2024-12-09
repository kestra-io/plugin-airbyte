package io.kestra.plugin.airbyte.connections;

import com.google.common.collect.ImmutableMap;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.junit.annotations.KestraTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
class SyncAlreadyRunningTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @Disabled("Unable to spawn airbyte cluster with connection configured")
    void run() throws Exception {
        RunContext runContext = runContextFactory.of(ImmutableMap.of());

        Sync task = Sync.builder()
            .url(Property.of("http://localhost:8000"))
            .username(Property.of("airbyte"))
            .password(Property.of("password"))
            .connectionId(Property.of("6f8804bf-9327-4634-be47-f2170c5346bd"))
            .failOnActiveSync(Property.of(false))
            .wait(Property.of(false))
            .build();

        Sync.Output runOutput = task.run(runContext);

        Sync task2 = Sync.builder()
                .url(Property.of("http://localhost:8000"))
                .username(Property.of("airbyte"))
                .password(Property.of("password"))
                .connectionId(Property.of("6f8804bf-9327-4634-be47-f2170c5346bd"))
                .failOnActiveSync(Property.of(false))
                .wait(Property.of(false))
                .build();

        Sync.Output runOutput2 = task.run(runContext);

        assertThat(runOutput2.getAlreadyRunning(), is(true));
    }
}
