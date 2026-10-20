package io.kestra.plugin.airbyte.endpoints;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;

import io.kestra.core.plugins.endpoint.PluginEndpointContext;

final class StoredOutput {
    private StoredOutput() {
    }

    /**
     * Opens the internal storage file referenced by a task run output, empty when the output or the file does not exist.
     * The scoped storage rejects any URI outside the execution, which is treated the same as a missing file.
     */
    static Optional<InputStream> open(PluginEndpointContext context, String outputName) {
        var outputs = context.outputs().get();
        if (outputs == null || !(outputs.get(outputName) instanceof String value) || value.isBlank()) {
            return Optional.empty();
        }

        try {
            var uri = URI.create(value);
            if (!context.storage().exists(uri)) {
                return Optional.empty();
            }
            return Optional.of(context.storage().getFile(uri));
        } catch (IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
    }
}
