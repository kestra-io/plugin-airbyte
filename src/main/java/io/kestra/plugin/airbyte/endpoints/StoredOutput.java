package io.kestra.plugin.airbyte.endpoints;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.plugins.endpoint.PluginEndpointContext;

final class StoredOutput {
    private StoredOutput() {
    }

    /**
     * Whether the task run is an Airbyte one: Airbyte tasks and their {@code attempt-N} sub task runs output a {@code jobId},
     * and {@code CheckStatus} outputs the {@code job} summary. Other tasks of the execution must not be readable through the endpoints.
     */
    static boolean isAirbyteTaskRun(PluginEndpointContext context) {
        var outputs = context.outputs().get();
        if (outputs == null) {
            return false;
        }
        return outputs.get("jobId") != null || (outputs.get("job") instanceof Map<?, ?> job && job.containsKey("attemptCount"));
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
