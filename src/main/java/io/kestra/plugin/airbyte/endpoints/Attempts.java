package io.kestra.plugin.airbyte.endpoints;

import java.io.IOException;
import java.io.InputStream;
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
        var rows = StoredOutput.open(context, "attempts").map(Attempts::read).orElseGet(List::of);
        return PluginEndpointResponse.of(EndpointPage.of(context, rows));
    }

    private static List<AttemptRow> read(InputStream input) {
        try (input) {
            return FileSerde.readAll(input, AttemptRow.class).collectList().blockOptional().orElseGet(List::of);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
