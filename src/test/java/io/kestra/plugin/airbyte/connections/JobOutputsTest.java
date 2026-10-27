package io.kestra.plugin.airbyte.connections;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTaskException;
import io.kestra.core.queues.DispatchQueueInterface;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.airbyte.models.AttemptRow;
import io.kestra.plugin.airbyte.models.AttemptStatus;
import io.kestra.plugin.airbyte.models.JobStatus;
import io.kestra.plugin.airbyte.models.OnPartialSuccess;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class JobOutputsTest {
    private static final String CONNECTION_ID = "e3b1ce92-547c-436f-b1e8-23b6936c12cd";

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private DispatchQueueInterface<LogEntry> logQueue;

    private static String jobsGet(long id, String jobStatus, String firstAttemptStatus, String lastAttemptStatus, boolean partialSuccess) {
        return """
            {
              "job": { "id": %d, "configType": "sync", "configId": "%s", "status": "%s", "createdAt": 1700000000, "startedAt": 1700000005 },
              "attempts": [
                {
                  "attempt": {
                    "id": 0,
                    "status": "%s",
                    "createdAt": 1700000010,
                    "endedAt": 1700000070,
                    "bytesSynced": 10,
                    "recordsSynced": 5,
                    "streamStats": [
                      { "streamName": "customers", "streamNamespace": "public",
                        "stats": { "recordsEmitted": 5, "bytesEmitted": 100, "recordsCommitted": 5, "bytesCommitted": 90, "recordsRejected": 1 } }
                    ],
                    "failureSummary": {
                      "partialSuccess": %s,
                      "failures": [
                        { "failureOrigin": "source", "failureType": "system_error", "retryable": true,
                          "externalMessage": "Connection reset by source",
                          "internalMessage": "SECRET-INTERNAL-MESSAGE",
                          "stacktrace": "SECRET-STACKTRACE",
                          "streamDescriptor": { "name": "customers", "namespace": "public" } }
                      ]
                    }
                  },
                  "logs": { "logLines": ["attempt 0 log"] }
                },
                {
                  "attempt": {
                    "id": 1,
                    "status": "%s",
                    "createdAt": 1700000080,
                    "endedAt": 1700000200,
                    "bytesSynced": 300,
                    "recordsSynced": 30,
                    "streamStats": [
                      { "streamName": "customers", "streamNamespace": "public",
                        "stats": { "recordsEmitted": 12, "bytesEmitted": 200, "recordsCommitted": 12, "bytesCommitted": 180, "recordsRejected": 2 } },
                      { "streamName": "orders", "streamNamespace": "public",
                        "stats": { "recordsEmitted": 10, "bytesEmitted": 500, "recordsCommitted": 10, "bytesCommitted": 450 } },
                      { "streamName": "empty", "streamNamespace": "public",
                        "stats": { "recordsEmitted": 0, "recordsCommitted": 0 } }
                    ]
                  },
                  "logs": { "logLines": ["attempt 1 log"] }
                }
              ]
            }
            """.formatted(id, CONNECTION_ID, jobStatus, firstAttemptStatus, partialSuccess, lastAttemptStatus);
    }

    private void stubJob(long id, String body) {
        stubFor(post(urlPathMatching("/api/v1/jobs/get/?")).withRequestBody(matchingJsonPath("$.id", equalTo(String.valueOf(id)))).willReturn(okJson(body)));
    }

    private static CheckStatus.CheckStatusBuilder<?, ?> checkStatus(WireMockRuntimeInfo wiremock, long jobId) {
        return CheckStatus.builder()
            .id(IdUtils.create())
            .type(CheckStatus.class.getName())
            .url(Property.ofValue(wiremock.getHttpBaseUrl()))
            .token(Property.ofValue("token"))
            .jobId(Property.ofValue(String.valueOf(jobId)));
    }

    private RunContext runContext(CheckStatus task) {
        return TestsUtils.mockRunContext(runContextFactory, task, Map.of());
    }

    @Test
    void checkStatus_exposes_job_attempts_metadata_and_attempt_task_runs(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(71, jobsGet(71, "succeeded", "failed", "succeeded", false));

        var task = checkStatus(wiremock, 71).build();
        var runContext = runContext(task);
        var out = task.run(runContext);

        var job = out.getJob();
        assertThat(job.getStatus(), is(JobStatus.SUCCEEDED));
        assertThat(job.getAttemptCount(), is(2));
        assertThat(job.getFailedAttemptCount(), is(1));
        assertThat(job.getPartialSuccess(), is(false));
        assertThat(job.getDuration().toSeconds(), is(195L));
        assertThat(job.getFailures().size(), is(1));
        assertThat(job.getFailures().getFirst().getExternalMessage(), is("Connection reset by source"));
        assertThat(job.getFailures().getFirst().getStream(), is("public.customers"));
        assertThat(out.finalState().isPresent(), is(false));

        var metadata = out.getMetadata();
        assertThat(metadata.getRowsSynced(), is(22L));
        assertThat(metadata.getBytesSynced(), is(630L));
        assertThat(metadata.getRecordsRejected(), is(2L));
        var customers = metadata.getDestination().stream().filter(table -> "customers".equals(table.getName())).findFirst().orElseThrow();
        assertThat(customers.getBytes(), is(180L));
        assertThat(customers.getRecordsRejected(), is(2L));
        var sourceCustomers = metadata.getSource().stream().filter(table -> "customers".equals(table.getName())).findFirst().orElseThrow();
        assertThat(sourceCustomers.getBytes(), is(200L));

        List<AttemptRow> rows;
        try (var stream = runContext.storage().getFile(out.getAttempts())) {
            rows = FileSerde.readAll(stream, AttemptRow.class).collectList().block();
        }
        assertThat(rows.size(), is(2));
        assertThat(rows.getFirst().getStatus(), is(AttemptStatus.FAILED));
        assertThat(rows.getFirst().getFailures().getFirst().getRetryable(), is(true));
        assertThat(rows.get(1).getStreams().size(), is(3));
        assertThat(rows.get(1).getStreams().getFirst().getBytesCommitted(), is(180L));

        var taskRuns = runContext.dynamicWorkerResults().stream().map(result -> result.getTaskRun()).toList();
        assertThat(taskRuns.stream().map(taskRun -> taskRun.getTaskId()).toList(), contains("attempt-0", "attempt-1"));
        assertThat(taskRuns.stream().allMatch(taskRun -> Long.valueOf(71L).equals(taskRun.getOutputs().get("jobId"))), is(true));
        assertThat(taskRuns.stream().map(taskRun -> taskRun.getState().getCurrent()).toList(), contains(State.Type.FAILED, State.Type.SUCCESS));

        var metricNames = runContext.metrics().stream().map(metric -> metric.getName()).toList();
        assertThat(metricNames, hasItems("attempts.count", "attempts.failed", "duration", "records.rejected", "records.committed"));
        var failedAttempts = runContext.metrics().stream().filter(metric -> metric.getName().equals("attempts.failed")).findFirst().orElseThrow();
        assertThat(((Counter) failedAttempts).getValue(), is(1.0));
    }

    @Test
    void bytes_fall_back_to_emitted_bytes_when_bytes_committed_is_absent(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(
            79, """
                {
                  "job": { "id": 79, "configType": "sync", "configId": "%s", "status": "succeeded", "createdAt": 1700000000 },
                  "attempts": [
                    { "attempt": { "id": 0, "status": "succeeded", "bytesSynced": 87383, "recordsSynced": 15,
                        "totalStats": { "bytesEmitted": 87383 },
                        "streamStats": [
                          { "streamName": "users", "streamNamespace": "public", "stats": { "recordsEmitted": 10, "recordsCommitted": 10, "bytesEmitted": 60000 } },
                          { "streamName": "purchases", "streamNamespace": "public", "stats": { "recordsEmitted": 5, "recordsCommitted": 5, "bytesEmitted": 27383 } }
                        ] },
                      "logs": { "logLines": [] } }
                  ]
                }
                """.formatted(CONNECTION_ID)
        );

        var task = checkStatus(wiremock, 79).build();
        var metadata = task.run(runContext(task)).getMetadata();

        assertThat(metadata.getBytesSynced(), is(87383L));
        assertThat(metadata.getDestination().stream().map(table -> table.getBytes()).toList(), contains(60000L, 27383L));
    }

    @Test
    void total_bytes_fall_back_to_total_stats_without_attempt_bytes(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(
            80, """
                {
                  "job": { "id": 80, "configType": "sync", "status": "succeeded" },
                  "attempts": [
                    { "attempt": { "id": 0, "status": "succeeded", "totalStats": { "bytesEmitted": 321 } }, "logs": { "logLines": [] } }
                  ]
                }
                """
        );

        var task = checkStatus(wiremock, 80).build();
        assertThat(task.run(runContext(task)).getMetadata().getBytesSynced(), is(321L));
    }

    @Test
    void checkStatus_fails_with_outputs_kept_when_job_failed(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(72, jobsGet(72, "failed", "failed", "failed", false));

        var task = checkStatus(wiremock, 72).build();
        var thrown = assertThrows(RunnableTaskException.class, () -> task.run(runContext(task)));

        assertThat(thrown.getMessage(), containsString("Airbyte job 72 ended failed after 2 attempt(s): Connection reset by source"));
        assertThat(thrown.getMessage(), containsString("'job.failures'"));
        var output = (CheckStatus.Output) thrown.getOutput();
        assertThat(output.getJob().getStatus(), is(JobStatus.FAILED));
        assertThat(output.getAttempts(), notNullValue());
        assertThat(output.getMetadata(), notNullValue());
        assertThat(output.finalState().isPresent(), is(false));
        assertThat(output.toMap().containsKey("state"), is(false));
        assertThat(output.toMap().containsKey("finalState"), is(false));
    }

    @Test
    void checkStatus_ends_in_warning_on_partial_success_when_configured(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(73, jobsGet(73, "failed", "failed", "failed", true));

        var task = checkStatus(wiremock, 73).onPartialSuccess(Property.ofValue(OnPartialSuccess.WARNING)).build();
        var out = task.run(runContext(task));

        assertThat(out.finalState().orElseThrow(), is(State.Type.WARNING));
        assertThat(out.getJob().getPartialSuccess(), is(true));
        assertThat(out.getJob().getStatus(), is(JobStatus.FAILED));
        assertThat(out.toMap().containsKey("state"), is(false));
    }

    @Test
    void checkStatus_still_fails_on_partial_success_by_default(WireMockRuntimeInfo wiremock) {
        stubJob(74, jobsGet(74, "failed", "failed", "failed", true));

        var task = checkStatus(wiremock, 74).build();
        assertThrows(RunnableTaskException.class, () -> task.run(runContext(task)));
    }

    @Test
    void checkStatus_never_exposes_internal_messages_or_stacktraces(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(75, jobsGet(75, "failed", "failed", "failed", false));

        var logs = new CopyOnWriteArrayList<LogEntry>();
        logQueue.addListener(logs::add);

        var task = checkStatus(wiremock, 75).build();
        var runContext = runContext(task);
        var thrown = assertThrows(RunnableTaskException.class, () -> task.run(runContext));

        var output = (CheckStatus.Output) thrown.getOutput();
        var outputs = output.toMap().toString();
        assertThat(thrown.getMessage(), not(containsString("SECRET-")));
        assertThat(outputs, not(containsString("SECRET-")));

        try (var stream = runContext.storage().getFile(output.getAttempts())) {
            var file = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(file, not(containsString("SECRET-")));
        }

        TestsUtils.awaitLogs(logs, log -> log.getMessage() != null && log.getMessage().contains("Connection reset by source"), 1);
        assertThat(logs.stream().filter(log -> log.getMessage() != null && log.getMessage().contains("SECRET-")).count(), is(0L));
        assertThat(
            runContext.dynamicWorkerResults().stream().map(result -> result.getTaskRun().getTaskId()).toList(),
            contains("attempt-0", "attempt-1")
        );
    }

    @Test
    void failure_messages_are_truncated(WireMockRuntimeInfo wiremock) {
        var longMessage = "x".repeat(5000);
        stubJob(76, jobsGet(76, "failed", "failed", "failed", false).replace("Connection reset by source", longMessage));

        var task = checkStatus(wiremock, 76).build();
        var thrown = assertThrows(RunnableTaskException.class, () -> task.run(runContext(task)));
        var failure = ((CheckStatus.Output) thrown.getOutput()).getJob().getFailures().getFirst();

        assertThat(failure.getExternalMessage().length() < 2100, is(true));
        assertThat(failure.getExternalMessage(), endsWith("[truncated]"));
    }

    @Test
    void checkStatus_cancelled_job_without_attempts_fails_with_empty_attempts(WireMockRuntimeInfo wiremock) throws Exception {
        stubJob(77, """
            { "job": { "id": 77, "configType": "sync", "configId": "%s", "status": "cancelled", "createdAt": 1700000000 }, "attempts": [] }
            """.formatted(CONNECTION_ID));

        var task = checkStatus(wiremock, 77).build();
        var runContext = runContext(task);
        var thrown = assertThrows(RunnableTaskException.class, () -> task.run(runContext));

        var output = (CheckStatus.Output) thrown.getOutput();
        assertThat(thrown.getMessage(), containsString("ended cancelled after 0 attempt(s)"));
        assertThat(output.getJob().getAttemptCount(), is(0));
        assertThat(output.getJob().getDuration(), nullValue());
        assertThat(runContext.dynamicWorkerResults(), empty());
        try (var stream = runContext.storage().getFile(output.getAttempts())) {
            assertThat(stream.readAllBytes().length, is(0));
        }
    }

    @Test
    void sync_rebuilds_its_output_when_the_job_fails(WireMockRuntimeInfo wiremock) {
        stubFor(post(urlPathMatching("/api/v1/jobs/list/?")).willReturn(okJson("{\"jobs\":[],\"totalJobCount\":0}")));
        stubFor(
            post(urlPathMatching("/api/v1/connections/sync/?"))
                .willReturn(okJson("{\"job\":{\"id\":78,\"status\":\"running\"},\"attempts\":[]}"))
        );
        stubJob(78, jobsGet(78, "failed", "failed", "failed", false));

        var task = Sync.builder()
            .id(IdUtils.create())
            .type(Sync.class.getName())
            .url(Property.ofValue(wiremock.getHttpBaseUrl()))
            .token(Property.ofValue("token"))
            .connectionId(Property.ofValue(CONNECTION_ID))
            .build();

        var thrown = assertThrows(RunnableTaskException.class, () -> task.run(TestsUtils.mockRunContext(runContextFactory, task, Map.of())));

        var output = (Sync.Output) thrown.getOutput();
        assertThat(output.getJobId(), is(78L));
        assertThat(output.getAdopted(), is(false));
        assertThat(output.getJob().getStatus(), is(JobStatus.FAILED));
        assertThat(output.getAttempts(), notNullValue());
        assertThat(output.getMetadata(), notNullValue());
        assertThat(thrown.getMessage(), containsString("Airbyte job 78 ended failed"));
    }
}
