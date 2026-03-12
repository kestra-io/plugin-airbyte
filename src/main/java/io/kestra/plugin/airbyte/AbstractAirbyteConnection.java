package io.kestra.plugin.airbyte;

import java.io.IOException;
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
import io.kestra.core.runners.RunContext;
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
    private Property<String> url;

    @Schema(
        title = "Basic auth username",
        description = "Username for Airbyte basic authentication. If both basic auth and a bearer token are configured, basic auth is sent last and takes precedence"
    )
    private Property<String> username;

    @Schema(
        title = "Basic auth password",
        description = "Password for Airbyte basic authentication. Store this value in a Secret"
    )
    private Property<String> password;

    @Schema(
        title = "Bearer token",
        description = "Bearer token used for Airbyte API requests. This value is rendered from the current run context"
    )
    private Property<String> token;

    @Schema(
        title = "HTTP timeout",
        description = "HTTP timeout value for requests to Airbyte. Defaults to 10 seconds"
    )
    @Builder.Default
    private Property<Duration> httpTimeout = Property.ofValue(Duration.ofSeconds(10));

    @Schema(
        title = "HTTP client options",
        description = "HTTP client configuration applied to Airbyte API requests"
    )
    protected HttpConfiguration options;

    @Schema(
        title = "Application credentials",
        description = "Client credentials used to request an Airbyte application access token from `/api/v1/applications/token`. Use this instead of a static token when Airbyte application authentication is enabled"
    )
    @PluginProperty
    private ApplicationCredentials applicationCredentials;

    protected <REQ, RES> HttpResponse<RES> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder, Class<RES> responseType)
        throws HttpClientException, IllegalVariableEvaluationException, SyncAlreadyRunningException {

        requestBuilder.addHeader("Content-Type", "application/json");

        retrieveApplicationCredentialsToken(runContext);

        if (this.token != null) {
            requestBuilder.addHeader("Authorization", "Bearer " + runContext.render(this.token).as(String.class).orElseThrow());
        }

        if (this.username != null && this.password != null) {
            String basicAuthValue = "Basic " + java.util.Base64.getEncoder().encodeToString(
                (runContext.render(this.username).as(String.class).orElseThrow() + ":" +
                    runContext.render(this.password).as(String.class).orElseThrow()).getBytes()
            );
            requestBuilder.addHeader("Authorization", basicAuthValue);
        }

        var request = requestBuilder.build();

        try (HttpClient client = new HttpClient(runContext, options)) {
            return client.request(request, responseType);
        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed", e);
        } catch (HttpClientResponseException e) {
            if (this.isAlreadyRunningError(e)) {
                throw new SyncAlreadyRunningException("A sync is already running");
            }
            throw new RuntimeException("Request failed with status: " + e.getResponse().getStatus().getCode(), e);
        }
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
            final String clientId = runContext.render(this.applicationCredentials.getClientId()).as(String.class).orElseThrow();
            final String clientSecret = runContext.render(this.applicationCredentials.getClientSecret()).as(String.class).orElseThrow();

            HttpRequest.HttpRequestBuilder applicationTokenRequestBuilder = HttpRequest.builder()
                .uri(URI.create(getUrl() + "/api/v1/applications/token"))
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

            HttpRequest tokenRequest = applicationTokenRequestBuilder.build();

            String applicationToken;
            try (HttpClient client = new HttpClient(runContext, options)) {
                applicationToken = (String) client.request(tokenRequest, Map.class).getBody().getOrDefault("access_token", null);
            } catch (HttpClientException | IOException e) {
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
        private Property<String> clientId;

        @Schema(
            title = "Client secret",
            description = "Airbyte application client secret. Store this value in a Secret"
        )
        @NotNull
        private Property<String> clientSecret;
    }
}
