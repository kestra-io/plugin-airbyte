package io.kestra.plugin.airbyte.endpoints;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.plugins.endpoint.PluginEndpointContext;
import io.kestra.plugin.airbyte.models.AttemptRow;

final class StoredOutput {
    private StoredOutput() {
    }

    // `jobId` alone is too common (BigQuery...): require the attempt marker, the `job` summary or the `wait: false` Sync flags.
    static boolean isAirbyteTaskRun(PluginEndpointContext context) {
        var outputs = context.outputs().get();
        if (outputs == null) {
            return false;
        }
        return outputs.containsKey(AttemptRow.TASK_RUN_MARKER)
            || (outputs.get("job") instanceof Map<?, ?> job && job.containsKey("attemptCount"))
            || (outputs.get("jobId") != null && outputs.get("alreadyRunning") != null && outputs.get("adopted") != null);
    }

    // A URI outside the execution is rejected by the scoped storage and treated like a missing file.
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
