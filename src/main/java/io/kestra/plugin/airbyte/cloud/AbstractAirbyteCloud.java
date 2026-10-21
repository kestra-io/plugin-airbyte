package io.kestra.plugin.airbyte.cloud;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSession;

import org.apache.commons.io.IOUtils;

import com.airbyte.api.Airbyte;
import com.airbyte.api.models.shared.SchemeBasicAuth;
import com.airbyte.api.models.shared.SchemeClientCredentials;
import com.airbyte.api.models.shared.Security;
import com.airbyte.api.utils.SpeakeasyHTTPClient;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.retrys.Exponential;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.RetryUtils;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAirbyteCloud extends Task {
    public static final String DEFAULT_TOKEN_URL = "https://api.airbyte.com/v1/applications/token";
    @Schema(
        title = "Bearer token",
        description = "Bearer token for the Airbyte Cloud API. If this is set, it is used before client credentials or basic auth"
    )
    @PluginProperty(secret = true, group = "connection")
    private Property<String> token;

    @Schema(
        title = "Client ID",
        description = "Airbyte application client ID used for client-credentials authentication"
    )
    @PluginProperty(group = "connection")
    private Property<String> clientId;

    @Schema(
        title = "Client secret",
        description = "Airbyte application client secret used for client-credentials authentication. Store this value in a Secret"
    )
    @PluginProperty(secret = true, group = "connection")
    private Property<String> clientSecret;

    @Schema(
        title = "Token URL",
        description = "Token endpoint used with client credentials. Defaults to `https://api.airbyte.com/v1/applications/token`. See: https://reference.airbyte.com/reference/createaccesstoken"
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<String> tokenURL = Property.ofValue(DEFAULT_TOKEN_URL);

    @Schema(
        title = "Basic auth username",
        description = "Username for Airbyte Cloud basic authentication. Basic auth is used only when no bearer token or client credentials are configured"
    )
    @PluginProperty(secret = true, group = "connection")
    private Property<String> username;

    @Schema(
        title = "Basic auth password",
        description = "Password for Airbyte Cloud basic authentication. Store this value in a Secret"
    )
    @PluginProperty(secret = true, group = "connection")
    private Property<String> password;

    protected Airbyte client(RunContext runContext) throws Exception {
        return client(runContext, new CustomHttpClient(runContext));
    }

    /**
     * Single-attempt client for best-effort calls that must not wait behind the 5-minute retry loop of {@link CustomHttpClient}.
     */
    protected Airbyte snapshotClient(RunContext runContext) throws Exception {
        return client(runContext, new ConnectionCompatHttpClient());
    }

    private Airbyte client(RunContext runContext, SpeakeasyHTTPClient httpClient) throws Exception {
        Security security = new Security();

        if (this.token != null) {
            security.withBearerAuth(runContext.render(this.token).as(String.class).orElseThrow());
        } else if (this.clientId != null && this.clientSecret != null) {
            security.withClientCredentials(
                new SchemeClientCredentials(
                    runContext.render(this.clientId).as(String.class).orElseThrow(),
                    runContext.render(this.clientSecret).as(String.class).orElseThrow(),
                    runContext.render(this.tokenURL).as(String.class).orElse(DEFAULT_TOKEN_URL)
                )
            );
        } else {
            security.withBasicAuth(
                new SchemeBasicAuth(
                    runContext.render(this.username).as(String.class).orElseThrow(),
                    runContext.render(this.password).as(String.class).orElseThrow()
                )
            );
        }

        return Airbyte.builder()
            .client(httpClient)
            .security(security)
            .build();
    }

    public static class CustomHttpClient extends SpeakeasyHTTPClient {
        private final RetryUtils.Instance<HttpResponse<InputStream>, Exception> retry;

        public CustomHttpClient(RunContext runContext) {
            retry = RetryUtils
                .of(
                    Exponential.builder()
                        .delayFactor(2.0)
                        .interval(Duration.ofSeconds(1))
                        .maxInterval(Duration.ofSeconds(15))
                        .maxAttempts(-1)
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
                        (httpResponse) -> httpResponse.statusCode() == 408
                            || httpResponse.statusCode() == 425
                            || httpResponse.statusCode() == 429
                            || (httpResponse.statusCode() >= 500 && httpResponse.statusCode() != 501),
                        () -> super.send(request)
                    );
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    /** com.airbyte:api 2.0.0 requires dataResidency, which Airbyte Cloud no longer returns: add a placeholder so the snapshot can be read. */
    public static class ConnectionCompatHttpClient extends SpeakeasyHTTPClient {
        private static final Pattern CONNECTION_PATH = Pattern.compile(".*/connections/[^/]+");

        @Override
        public HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException, URISyntaxException {
            var response = super.send(request);
            if (!"GET".equals(request.method()) || response.statusCode() >= 300 || !CONNECTION_PATH.matcher(request.uri().getPath()).matches()) {
                return response;
            }

            byte[] body;
            try (var in = response.body()) {
                body = in.readAllBytes();
            }
            try {
                if (JacksonMapper.ofJson().readTree(body) instanceof ObjectNode connection && !connection.hasNonNull("dataResidency")) {
                    body = JacksonMapper.ofJson().writeValueAsBytes(connection.put("dataResidency", "auto"));
                }
            } catch (IOException ignored) {
                // not JSON: leave the body untouched for the SDK to report
            }
            return new BufferedResponse(response, new ByteArrayInputStream(body));
        }

        private record BufferedResponse(HttpResponse<InputStream> delegate, InputStream body) implements HttpResponse<InputStream> {
            @Override
            public int statusCode() {
                return delegate.statusCode();
            }

            @Override
            public HttpRequest request() {
                return delegate.request();
            }

            @Override
            public Optional<HttpResponse<InputStream>> previousResponse() {
                return delegate.previousResponse();
            }

            @Override
            public HttpHeaders headers() {
                return delegate.headers();
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return delegate.sslSession();
            }

            @Override
            public URI uri() {
                return delegate.uri();
            }

            @Override
            public HttpClient.Version version() {
                return delegate.version();
            }
        }
    }

    protected void validate(HttpResponse<InputStream> response) throws Exception {
        if (response.statusCode() >= 400) {
            throw new Exception(
                "Failed request with status " + response.statusCode() + " and body " +
                    IOUtils.toString(response.body(), StandardCharsets.UTF_8)
            );
        }
    }
}
