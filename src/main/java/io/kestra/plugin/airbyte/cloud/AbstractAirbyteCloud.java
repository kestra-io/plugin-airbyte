package io.kestra.plugin.airbyte.cloud;

import com.airbyte.api.Airbyte;
import com.airbyte.api.models.shared.Security;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAirbyteCloud extends Task {
    @Schema(
        title = "API key"
    )
    @PluginProperty(dynamic = true)
    String token;

    protected Airbyte client(RunContext runContext) throws Exception {
        return Airbyte.builder()
            .setSecurity(new Security(runContext.render(this.token)))
            .build();
    }

    protected void validate(HttpResponse<byte[]> response) throws Exception {
        if (response.statusCode() >= 400) {
            throw new Exception("Failed request with status " + response.statusCode() + " and body " +
                new String(response.body(), StandardCharsets.UTF_8)
            );
        }
    }
}
