package io.kestra.plugin.airbyte.cloud;

import com.airbyte.api.Airbyte;
import com.airbyte.api.models.shared.SchemeBasicAuth;
import com.airbyte.api.models.shared.Security;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.retrys.Exponential;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.RetryUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAirbyteCloud extends Task {
    @Schema(
        title = "API key."
    )
    Property<String> token;

    @Schema(
        title = "BasicAuth authentication username."
    )
    private Property<String> username;

    @Schema(
        title = "BasicAuth authentication password."
    )
    private Property<String> password;

    protected Airbyte client(RunContext runContext) throws Exception {
        RetryUtils.Instance<HttpResponse<byte[]>, Exception> retry = new RetryUtils()
            .of(
                Exponential.builder()
                    .delayFactor(2.0)
                    .interval(Duration.ofSeconds(1))
                    .maxInterval(Duration.ofSeconds(15))
                    .maxAttempt(-1)
                    .maxDuration(Duration.ofMinutes(5))
                    .build(),
                runContext.logger()
            );

        Security security = new Security();
        if (this.token != null) {
            security.withBearerAuth(runContext.render(this.token).as(String.class).orElseThrow());
        }

        if (this.token != null) {
            security.withBasicAuth(new SchemeBasicAuth(runContext.render(this.username).as(String.class).orElseThrow(),
                runContext.render(this.password).as(String.class).orElseThrow()));
        }

        return Airbyte.builder()
            .setClient(request -> {
                HttpClient client = HttpClient.newHttpClient();

                HttpRequest req = request.build();

                try {
                    return retry
                        .run(
                            (httpResponse) -> httpResponse.statusCode() == 408,
                            () -> client.send(req, HttpResponse.BodyHandlers.ofByteArray())
                        );
                } catch (Exception e) {
                    throw new IOException(e);
                }
            })
            .setSecurity(security)
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
