package io.kestra.plugin.airbyte.cloud;

import com.airbyte.api.Airbyte;
import com.airbyte.api.models.shared.SchemeBasicAuth;
import com.airbyte.api.models.shared.SchemeClientCredentials;
import com.airbyte.api.models.shared.Security;
import com.airbyte.api.utils.SpeakeasyHTTPClient;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.retrys.Exponential;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.RetryUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
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
    public static final String DEFAULT_TOKEN_URL = "https://api.airbyte.com/v1/applications/token";
    @Schema(
        title = "API key."
    )
    private Property<String> token;

    @Schema(
        title = "Application Client ID."
    )
    private Property<String> clientId;

    @Schema(
        title = "Application Client Secret."
    )
    private Property<String> clientSecret;

    @Schema(
        title = "Token URL to get an access token from.",
        description = "See: https://reference.airbyte.com/reference/createaccesstoken"
    )
    @Builder.Default
    private Property<String> tokenURL = Property.of(DEFAULT_TOKEN_URL);

    @Schema(
        title = "BasicAuth authentication username."
    )
    private Property<String> username;

    @Schema(
        title = "BasicAuth authentication password."
    )
    private Property<String> password;

    protected Airbyte client(RunContext runContext) throws Exception {
        Security security = new Security();

        if (this.token != null) {
            security.withBearerAuth(runContext.render(this.token).as(String.class).orElseThrow());
        } else if (this.clientId != null && this.clientSecret != null) {
            security.withClientCredentials(new SchemeClientCredentials(
                runContext.render(this.clientId).as(String.class).orElseThrow(),
                runContext.render(this.clientSecret).as(String.class).orElseThrow(),
                runContext.render(this.tokenURL).as(String.class).orElse(DEFAULT_TOKEN_URL)
            ));
        } else {
            security.withBasicAuth(new SchemeBasicAuth(
                runContext.render(this.username).as(String.class).orElseThrow(),
                runContext.render(this.password).as(String.class).orElseThrow()
            ));
        }

        return Airbyte.builder()
            .client(new CustomHttpClient(runContext))
            .security(security)
            .build();
    }

    public static class CustomHttpClient extends SpeakeasyHTTPClient {
        private final RetryUtils.Instance<HttpResponse<InputStream>, Exception> retry;

        public CustomHttpClient(RunContext runContext) {
            retry = new RetryUtils()
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
        }

        @Override
        public HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException, URISyntaxException {
            try {
                return retry
                    .run(
                        (httpResponse) -> httpResponse.statusCode() == 408,
                        () -> super.send(request)
                    );
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    protected void validate(HttpResponse<InputStream> response) throws Exception {
        if (response.statusCode() >= 400) {
            throw new Exception("Failed request with status " + response.statusCode() + " and body " +
                IOUtils.toString(response.body(), StandardCharsets.UTF_8)
            );
        }
    }
}
