package io.kestra.plugin.airbyte;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;

@KestraTest
public class AbstractAirbyteConnectionTest {
    @Inject
    protected RunContextFactory runContextFactory;

    @Value("${airbyte.api.url}")
    protected String url;

    @Value("${airbyte.api.username}")
    protected String username;

    @Value("${airbyte.api.password}")
    protected String password;

    @Value("${airbyte.connectionId}")
    protected String connectionId;
}
