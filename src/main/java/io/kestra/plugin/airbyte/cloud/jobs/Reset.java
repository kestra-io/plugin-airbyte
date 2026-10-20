package io.kestra.plugin.airbyte.cloud.jobs;

import com.airbyte.api.models.shared.JobTypeEnum;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run an Airbyte Cloud reset",
    description = """
        Starts a reset job for an Airbyte Cloud connection. By default, the task waits for completion and emits job metrics.

        The task also exposes the `connection` that ran the job, a `connectionSnapshot` file (connector configurations are never included) and a `jobUrl` to the job in Airbyte Cloud. A reset clears data, so it declares no assets.

        Breaking change: this task now fails when the Airbyte job ends `FAILED` or `CANCELLED`. The outputs stay available: use `allowFailure: true` or an `errors` branch to keep the previous behavior.

        Per-stream and per-attempt statistics, the `attempts` output and `onPartialSuccess` are not available on Airbyte Cloud, because its public API only reports `bytesSynced`, `rowsSynced` and `duration` for a job.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: airbyte_reset
                namespace: company.team

                tasks:
                  - id: reset
                    type: io.kestra.plugin.airbyte.cloud.jobs.Reset
                    token: \"{{ secret('AIRBYTE_TOKEN') }}\"
                    connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd
                """
        )
    },
    metrics = {
        @Metric(name = "bytes_synced", type = Counter.TYPE),
        @Metric(name = "rows_synced", type = Counter.TYPE),
        @Metric(name = "duration", type = Timer.TYPE)
    }
)
public class Reset extends AbstractTrigger {
    protected JobTypeEnum syncType() {
        return JobTypeEnum.RESET;
    }
}
