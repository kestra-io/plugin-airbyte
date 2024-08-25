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
    title = "Reset a job."
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
                    token: <token>
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
