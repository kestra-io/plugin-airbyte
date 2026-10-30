package io.kestra.plugin.airbyte.endpoints;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.plugins.endpoint.PluginEndpoint;
import io.kestra.core.plugins.endpoint.PluginEndpointContext;
import io.kestra.core.plugins.endpoint.PluginEndpointResponse;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.airbyte.models.AttemptRow;

/**
 * Pages through the {@code attempts} output file of the task run. Query parameters: {@code page} (default 1) and {@code size} (default 50, max 500).
 */
@Plugin
public class Attempts implements PluginEndpoint {
    @Override
    public String name() {
        return "attempts";
    }

    @Override
    public PluginEndpointResponse handle(PluginEndpointContext context) {
        var stored = StoredOutput.open(context, "attempts");
        if (stored.isEmpty()) {
            return PluginEndpointResponse.of(EndpointPage.of(context, List.<AttemptRow> of()));
        }

        try (var input = stored.get()) {
            return PluginEndpointResponse.of(EndpointPage.of(context, FileSerde.readAll(input, AttemptRow.class).toStream()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
