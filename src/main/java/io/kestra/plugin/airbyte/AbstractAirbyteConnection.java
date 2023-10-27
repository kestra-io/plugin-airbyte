package io.kestra.plugin.airbyte;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.airbyte.connections.SyncAlreadyRunningException;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.http.client.netty.NettyHttpClientFactory;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import javax.validation.constraints.NotNull;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAirbyteConnection extends Task {
    @Schema(
        title = "The URL of your Airbyte instance"
    )
    @PluginProperty(dynamic = true)
    @NotNull
    String url;

    @Schema(
        title = "Basic auth username"
    )
    @PluginProperty(dynamic = true)
    String username;

    @Schema(
        title = "Basic auth password"
    )
    @PluginProperty(dynamic = true)
    String password;

    @Schema(
        title = "API key"
    )
    @PluginProperty(dynamic = true)
    String token;

    @Schema(
            title = "HTTP connection timeout"
    )
    @PluginProperty
    @Builder.Default
    Duration httpTimeout = Duration.ofSeconds(10);

    private static final NettyHttpClientFactory FACTORY = new NettyHttpClientFactory();

    protected HttpClient client(RunContext runContext) throws IllegalVariableEvaluationException, MalformedURLException, URISyntaxException {
        MediaTypeCodecRegistry mediaTypeCodecRegistry = runContext.getApplicationContext().getBean(MediaTypeCodecRegistry.class);

        var httpConfig = new DefaultHttpClientConfiguration();
        httpConfig.setMaxContentLength(Integer.MAX_VALUE);
        httpConfig.setReadTimeout(httpTimeout);
        DefaultHttpClient client = (DefaultHttpClient) FACTORY.createClient(URI.create(runContext.render(this.url)).toURL(), httpConfig);
        client.setMediaTypeCodecRegistry(mediaTypeCodecRegistry);

        return client;
    }

    protected <REQ, RES> HttpResponse<RES> request(RunContext runContext, MutableHttpRequest<REQ> request, Argument<RES> argument) throws HttpClientResponseException, SyncAlreadyRunningException {
        try {
            request = request
                .contentType(MediaType.APPLICATION_JSON);

            if (this.token != null) {
                request = request.bearerAuth(runContext.render(this.token));
            }

            if (this.username != null && this.password != null) {
                request = request.basicAuth(runContext.render(this.username), runContext.render(this.password));
            }

            try (HttpClient client = this.client(runContext)) {
                return client.toBlocking().exchange(request, argument);
            }
        } catch (HttpClientResponseException e) {
            if (e.getStatus().getCode() == 409 && e.getResponse().getBody(String.class).isPresent()){
                if (e.getResponse().getBody(String.class).orElse("null").contains("A sync is already running")) {
                    throw new SyncAlreadyRunningException("A sync is already running");
                }
            }

            throw new HttpClientResponseException(
                "Request failed '" + e.getStatus().getCode() + "' and body '" + e.getResponse().getBody(String.class).orElse("null") + "'",
                e,
                e.getResponse()
            );
        } catch (IllegalVariableEvaluationException | MalformedURLException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
