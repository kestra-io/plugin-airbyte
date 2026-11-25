package io.kestra.plugin.airbyte.cloud;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.airbyte.api.Airbyte;
import com.airbyte.api.models.shared.Security;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.airbyte.cloud.jobs.Sync;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
@WireMockTest
class CloudCheckpointTest {
    private static final String CONNECTION_ID = "e3b1ce92-547c-436f-b1e8-23b6936c12cd";
    private static final String CHECKPOINT_KEY = "airbyte.cloud.jobId";

    @Inject
    private RunContextFactory runContextFactory;

    private static Sync task(WireMockRuntimeInfo wiremock) throws Exception {
        var task = Mockito.spy(
            Sync.builder()
                .id(IdUtils.create())
                .type(Sync.class.getName())
                .connectionId(Property.ofValue(CONNECTION_ID))
                .wait(Property.ofValue(false))
                .build()
        );
        var client = Airbyte.builder().serverURL("%s/v1".formatted(wiremock.getHttpBaseUrl())).security(new Security().withBearerAuth("token")).build();
        var snapshotClient = Airbyte.builder()
            .client(new AbstractAirbyteCloud.ConnectionCompatHttpClient())
            .serverURL("%s/v1".formatted(wiremock.getHttpBaseUrl()))
            .security(new Security().withBearerAuth("token"))
            .build();
        Mockito.doReturn(client).when(task).client(Mockito.any());
        Mockito.doReturn(snapshotClient).when(task).snapshotClient(Mockito.any());
        return task;
    }

    private static String job(long id, String status, String jobType) {
        return """
            { "jobId": %d, "status": "%s", "jobType": "%s", "connectionId": "%s", "startTime": "2024-01-15T10:00:00Z" }
            """.formatted(id, status, jobType, CONNECTION_ID);
    }

    @Test
    void resumesTheCheckpointedJobInsteadOfCreatingOne(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        runContext.checkpoints().set(CHECKPOINT_KEY, "123");
        stubFor(get(urlPathEqualTo("/v1/jobs/123")).willReturn(okJson(job(123, "running", "sync"))));

        var output = task.run(runContext);

        assertThat(output.getJob().getJobId(), is(123L));
        verify(0, postRequestedFor(urlPathEqualTo("/v1/jobs")));
    }

    @Test
    void createsAndOverwritesTheCheckpointWhenTheJobWasCancelled(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        runContext.checkpoints().set(CHECKPOINT_KEY, "123");
        stubFor(get(urlPathEqualTo("/v1/jobs/123")).willReturn(okJson(job(123, "cancelled", "sync"))));
        stubFor(post(urlPathEqualTo("/v1/jobs")).willReturn(okJson(job(124, "pending", "sync"))));

        var output = task.run(runContext);

        assertThat(output.getJob().getJobId(), is(124L));
        assertThat(runContext.checkpoints().get(CHECKPOINT_KEY), is(Optional.of("124")));
    }

    @Test
    void ignoresACheckpointedJobOfAnotherType(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        runContext.checkpoints().set(CHECKPOINT_KEY, "123");
        stubFor(get(urlPathEqualTo("/v1/jobs/123")).willReturn(okJson(job(123, "running", "reset"))));
        stubFor(post(urlPathEqualTo("/v1/jobs")).willReturn(okJson(job(124, "pending", "sync"))));

        assertThat(task.run(runContext).getJob().getJobId(), is(124L));
    }

    @Test
    void createsWhenTheCheckpointedJobNoLongerExists(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        runContext.checkpoints().set(CHECKPOINT_KEY, "123");
        stubFor(get(urlPathEqualTo("/v1/jobs/123")).willReturn(aResponse().withStatus(404)));
        stubFor(post(urlPathEqualTo("/v1/jobs")).willReturn(okJson(job(124, "pending", "sync"))));

        assertThat(task.run(runContext).getJob().getJobId(), is(124L));
    }

    @Test
    void killCancelsTheJobOnce(WireMockRuntimeInfo wiremock) throws Exception {
        var task = task(wiremock);
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        stubFor(post(urlPathEqualTo("/v1/jobs")).willReturn(okJson(job(124, "pending", "sync"))));
        stubFor(delete(urlPathEqualTo("/v1/jobs/124")).willReturn(okJson(job(124, "cancelled", "sync"))));

        task.run(runContext);
        task.kill();
        task.kill();

        verify(1, deleteRequestedFor(urlPathEqualTo("/v1/jobs/124")));
    }
}
