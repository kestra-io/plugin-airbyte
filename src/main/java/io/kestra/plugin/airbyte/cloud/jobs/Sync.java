package io.kestra.plugin.airbyte.cloud.jobs;

import com.airbyte.api.models.shared.JobTypeEnum;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run an Airbyte Cloud sync",
    description = """
        Starts a sync job for an Airbyte Cloud connection. By default, the task waits for completion and emits sync metrics.

        The task also exposes the `connection` that ran the job, a `connectionSnapshot` file (connector configurations are never included) and a `jobUrl` to the job in Airbyte Cloud. When Kestra runs with assets enabled (Enterprise Edition) and the job succeeds, each replicated stream is emitted as a table asset with the source stream as its input. Airbyte Cloud does not report per-stream statistics, so the asset id carries no namespace unless the connection uses a custom namespace format.

        Breaking change: this task now fails when the Airbyte job ends `FAILED` or `CANCELLED`. The outputs stay available: use `allowFailure: true` or an `errors` branch to keep the previous behavior.

        Per-stream and per-attempt statistics, the `attempts` output and `onPartialSuccess` are not available on Airbyte Cloud, because its public API only reports `bytesSynced`, `rowsSynced` and `duration` for a job.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: airbyte_sync
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.airbyte.cloud.jobs.Sync
                    token: \"{{ secret('AIRBYTE_TOKEN') }}\"
                    connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd
                """
        ),
        @Example(
            full = true,
            title = "Link to the Airbyte Cloud job when the sync fails",
            code = """
                id: airbyte_cloud_sync_with_link
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.airbyte.cloud.jobs.Sync
                    token: \"{{ secret('AIRBYTE_TOKEN') }}\"
                    connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd

                errors:
                  - id: alert
                    type: io.kestra.plugin.core.log.Log
                    level: ERROR
                    message: "Airbyte Cloud sync failed, see {{ outputs.sync.jobUrl }}"
                """
        )
    },
    metrics = {
        @Metric(name = "bytes_synced", type = Counter.TYPE),
        @Metric(name = "rows_synced", type = Counter.TYPE),
        @Metric(name = "duration", type = Timer.TYPE)
    }
)
public class Sync extends AbstractTrigger {
    @Override
    protected boolean emitsAssets() {
        return true;
    }

    protected JobTypeEnum syncType() {
        return JobTypeEnum.SYNC;
    }
}
