package io.kestra.plugin.airbyte;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    protected void assumeAirbyteIntegrationIsConfigured() {
        assumeTrue(this.url != null && !this.url.isBlank(), "Missing airbyte.api.url integration test configuration");
        assumeTrue(this.username != null && !this.username.isBlank(), "Missing airbyte.api.username integration test configuration");
        assumeTrue(this.password != null && !this.password.isBlank(), "Missing airbyte.api.password integration test configuration");
        assumeTrue(this.connectionId != null && !this.connectionId.isBlank(), "Missing airbyte.connectionId integration test configuration");
    }
}
