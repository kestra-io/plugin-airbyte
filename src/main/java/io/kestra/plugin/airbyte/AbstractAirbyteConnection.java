package io.kestra.plugin.airbyte;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.retrys.Exponential;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.RetryUtils;
import io.kestra.plugin.airbyte.connections.SyncAlreadyRunningException;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAirbyteConnection extends Task {
    @Schema(
        title = "Airbyte API URL",
        description = "Base URL of the Airbyte instance to call. This value is rendered from the current run context"
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> url;

    @Schema(
        title = "Basic auth username",
        description = "Username for Airbyte basic authentication. If both basic auth and a bearer token are configured, basic auth is sent last and takes precedence"
    )
    @PluginProperty(secret = true, group = "connection")
    private Property<String> username;

    @Schema(
        title = "Basic auth password",
        description = "Password for Airbyte basic authentication. Store this value in a Secret"
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> password;

    @Schema(
        title = "Bearer token",
        description = "Bearer token used for Airbyte API requests. This value is rendered from the current run context"
    )
    @PluginProperty(secret = true, group = "connection")
    private Property<String> token;

    @Deprecated
    @Schema(
        title = "HTTP timeout",
        description = "Deprecated – use `options` to configure timeouts. This field has no effect."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> httpTimeout = Property.ofValue(Duration.ofSeconds(10));

    @Schema(
        title = "HTTP client options",
        description = "HTTP client configuration applied to Airbyte API requests"
    )
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    @Schema(
        title = "Application credentials",
        description = "Client credentials used to request an Airbyte application access token from `/api/v1/applications/token`. Use this instead of a static token when Airbyte application authentication is enabled"
    )
    @PluginProperty(group = "connection")
    private ApplicationCredentials applicationCredentials;

    protected <REQ, RES> HttpResponse<RES> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder, Class<RES> responseType)
        throws HttpClientException, IllegalVariableEvaluationException, SyncAlreadyRunningException {

        requestBuilder.addHeader("Content-Type", "application/json");

        retrieveApplicationCredentialsToken(runContext);

        if (this.token != null) {
            requestBuilder.addHeader("Authorization", "Bearer " + runContext.render(this.token).as(String.class).orElseThrow());
        }

        if (this.username != null && this.password != null) {
            var basicAuthValue = "Basic " + java.util.Base64.getEncoder().encodeToString(
                (runContext.render(this.username).as(String.class).orElseThrow() + ":" +
                    runContext.render(this.password).as(String.class).orElseThrow()).getBytes()
            );
            requestBuilder.addHeader("Authorization", basicAuthValue);
        }

        var request = requestBuilder.build();

        try {
            return this.<HttpResponse<RES>> buildRetry(runContext).runRetryIf(
                this::isRetryableException,
                () ->
                {
                    try (var client = new HttpClient(runContext, options)) {
                        return client.request(request, responseType);
                    } catch (HttpClientResponseException e) {
                        if (this.isAlreadyRunningError(e)) {
                            throw new AlreadyRunningWrapper();
                        }
                        throw e;
                    }
                }
            );
        } catch (AlreadyRunningWrapper e) {
            throw new SyncAlreadyRunningException("A sync is already running");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("HTTP request failed", e);
        }
    }

    private static final class AlreadyRunningWrapper extends RuntimeException {
        AlreadyRunningWrapper() {
            super(null, null, true, false);
        }
    }

    private boolean isRetryableException(Throwable t) {
        if (t instanceof SyncAlreadyRunningException || t instanceof AlreadyRunningWrapper) {
            return false;
        }
        if (t instanceof SocketTimeoutException) {
            return true;
        }
        if (t instanceof HttpClientResponseException e) {
            var code = e.getResponse().getStatus().getCode();
            return code == 408 || code == 425 || code == 429 || (code >= 500 && code != 501);
        }
        if (t instanceof IOException) {
            return !(t instanceof HttpClientResponseException) &&
                (t.getCause() instanceof SocketTimeoutException || !(t instanceof HttpClientException));
        }
        return false;
    }

    private <T> RetryUtils.Instance<T, Exception> buildRetry(RunContext runContext) {
        return RetryUtils.of(
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

    private boolean isAlreadyRunningError(HttpClientResponseException exception) {
        if (Objects.requireNonNull(exception.getResponse()).getStatus().getCode() == 409) {
            return true;
        }

        var lowerCaseMessage = Objects.toString(exception.getMessage(), "").toLowerCase(Locale.ROOT);
        if (lowerCaseMessage.contains("already running")) {
            return true;
        }

        var responseBody = exception.getResponse().getBody();
        if (responseBody instanceof byte[] rawBody) {
            return new String(rawBody, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("already running");
        }

        return Objects.toString(responseBody, "").toLowerCase(Locale.ROOT).contains("already running");
    }

    private void retrieveApplicationCredentialsToken(RunContext runContext) throws IllegalVariableEvaluationException {
        if (applicationCredentials != null) {
            final var clientId = runContext.render(this.applicationCredentials.getClientId()).as(String.class).orElseThrow();
            final var clientSecret = runContext.render(this.applicationCredentials.getClientSecret()).as(String.class).orElseThrow();

            var applicationTokenRequestBuilder = HttpRequest.builder()
                .uri(URI.create(runContext.render(this.url).as(String.class).orElseThrow() + "/api/v1/applications/token"))
                .method("POST")
                .body(
                    HttpRequest.JsonRequestBody.builder()
                        .content(
                            Map.of(
                                "client_id", clientId,
                                "client_secret", clientSecret,
                                "grant-type", "client_credentials"
                            )
                        )
                        .build()
                );
            applicationTokenRequestBuilder.addHeader("Accept", "application/json");
            applicationTokenRequestBuilder.addHeader("Content-Type", "application/json");

            var tokenRequest = applicationTokenRequestBuilder.build();

            String applicationToken;
            try {
                applicationToken = this.<String> buildRetry(runContext).runRetryIf(
                    this::isRetryableException,
                    () ->
                    {
                        try (var client = new HttpClient(runContext, options)) {
                            return (String) client.request(tokenRequest, Map.class).getBody().getOrDefault("access_token", null);
                        }
                    }
                );
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }

            if (applicationToken != null) {
                this.token = Property.ofValue(applicationToken);
            }
        }
    }

    @Builder
    @Getter
    public static class ApplicationCredentials {
        @Schema(
            title = "Client ID",
            description = "Airbyte application client ID"
        )
        @NotNull
        @PluginProperty(group = "main")
        private Property<String> clientId;

        @Schema(
            title = "Client secret",
            description = "Airbyte application client secret. Store this value in a Secret"
        )
        @NotNull
        @PluginProperty(secret = true, group = "main")
        private Property<String> clientSecret;
    }
}
