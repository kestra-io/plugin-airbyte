package io.kestra.plugin.airbyte.connections;

import com.google.common.collect.ImmutableMap;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.core.junit.annotations.KestraTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class SyncTest {
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
            .connectionId(Property.of("3ef5d9a0-4f16-42db-9ab5-8dd3c4822391"))
            .build();

        Sync.Output runOutput = task.run(runContext);

        assertThat(runOutput, is(notNullValue()));
        assertThat(runOutput.getJobId(), is(notNullValue()));
    }

    @Disabled
    @Test
    void run_local_with_app() throws Exception {
        RunContext runContext = runContextFactory.of(ImmutableMap.of());

        Sync task = Sync.builder()
            .url(Property.of("http://localhost:8000"))
            .applicationCredentials(AbstractAirbyteConnection.ApplicationCredentials.builder()
                .clientId(Property.of("b112220e-3116-4c00-a4e5-8b828836081b"))
                .clientSecret(Property.of("U4n9TZIVkfRVFK6GadANGjjTZRQkZTlD"))
                .build())
            .connectionId(Property.of("31380e9c-0fb4-4cd1-915a-81b305ac4733"))
            .wait(Property.of(true))
            .build();

        Sync.Output runOutput = task.run(runContext);

        assertThat(runOutput, is(notNullValue()));
        assertThat(runOutput.getJobId(), is(notNullValue()));
    }
}
