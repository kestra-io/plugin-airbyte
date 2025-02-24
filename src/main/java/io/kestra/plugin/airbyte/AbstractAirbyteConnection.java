package io.kestra.plugin.airbyte;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.airbyte.connections.SyncAlreadyRunningException;
import io.micronaut.http.MediaType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAirbyteConnection extends Task {
    @Schema(title = "The URL of your Airbyte instance.")
    @NotNull
    private Property<String> url;

    @Schema(title = "Basic authentication username.")
    private Property<String> username;

    @Schema(title = "Basic authentication password.")
    private Property<String> password;

    @Schema(title = "API key.")
    private Property<String> token;

    @Schema(title = "HTTP connection timeout.")
    @Builder.Default
    private Property<Duration> httpTimeout = Property.of(Duration.ofSeconds(10));

    @Schema(title = "The HTTP client configuration.")
    protected HttpConfiguration options;

    protected <REQ, RES> HttpResponse<RES> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder, Class<RES> responseType)
        throws HttpClientException, IllegalVariableEvaluationException, SyncAlreadyRunningException {

        requestBuilder.addHeader("Content-Type", MediaType.APPLICATION_JSON);

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

        var request= requestBuilder.build();

        try (HttpClient client = new HttpClient(runContext, options)) {
            return client.request(request, responseType);
        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed", e);
        } catch (HttpClientResponseException e) {
            if (Objects.requireNonNull(e.getResponse()).getStatus().getCode() == 409) {
                throw new SyncAlreadyRunningException("A sync is already running");
            }
            throw new RuntimeException("Request failed with status: " + e.getResponse().getStatus().getCode(), e);
        }
    }
}
