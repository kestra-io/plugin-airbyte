package io.kestra.plugin.airbyte.connections;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.event.Level;

import io.kestra.core.models.executions.TaskRun;
import io.kestra.core.models.executions.TaskRunAttempt;
import io.kestra.core.models.flows.State;
import io.kestra.core.runners.DynamicTaskRunLog;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.WorkerTaskResult;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.airbyte.StreamAssets;
import io.kestra.plugin.airbyte.models.Attempt;
import io.kestra.plugin.airbyte.models.AttemptFailureSummary;
import io.kestra.plugin.airbyte.models.AttemptInfo;
import io.kestra.plugin.airbyte.models.AttemptRow;
import io.kestra.plugin.airbyte.models.AttemptStatus;
import io.kestra.plugin.airbyte.models.AttemptStreamStats;
import io.kestra.plugin.airbyte.models.FailureSummary;
import io.kestra.plugin.airbyte.models.JobInfo;
import io.kestra.plugin.airbyte.models.JobStatus;
import io.kestra.plugin.airbyte.models.JobSummary;
import io.kestra.plugin.airbyte.models.StreamDescriptor;

final class JobReports {
    private JobReports() {
    }

    static List<AttemptInfo> attempts(JobInfo jobInfo) {
        return Optional.ofNullable(jobInfo.getAttempts()).orElseGet(List::of);
    }

    static List<Attempt> attemptList(JobInfo jobInfo) {
        return attempts(jobInfo).stream().map(AttemptInfo::getAttempt).filter(Objects::nonNull).toList();
    }

    static List<FailureSummary> failures(Attempt attempt) {
        return Optional.ofNullable(attempt.getFailureSummary())
            .map(AttemptFailureSummary::getFailures)
            .orElseGet(List::of)
            .stream()
            .filter(Objects::nonNull)
            .map(FailureSummary::from)
            .toList();
    }

    static JobSummary summary(JobInfo jobInfo) {
        var job = jobInfo.getJob();
        var attempts = attemptList(jobInfo);
        var endedAt = attempts.isEmpty() ? null : attempts.getLast().getEndedAt();
        var startedAt = job.getStartedAt() != null ? job.getStartedAt() : job.getCreatedAt();

        return JobSummary.builder()
            .configType(job.getConfigType())
            .status(job.getStatus())
            .createdAt(job.getCreatedAt())
            .startedAt(job.getStartedAt())
            .endedAt(endedAt)
            .duration(duration(startedAt, endedAt))
            .attemptCount(attempts.size())
            .failedAttemptCount((int) attempts.stream().filter(attempt -> attempt.getStatus() == AttemptStatus.FAILED).count())
            .partialSuccess(isPartialSuccess(jobInfo))
            .failures(attempts.stream().flatMap(attempt -> failures(attempt).stream()).toList())
            .build();
    }

    static boolean isPartialSuccess(JobInfo jobInfo) {
        return attemptList(jobInfo).stream()
            .map(Attempt::getFailureSummary)
            .filter(Objects::nonNull)
            .anyMatch(summary -> Boolean.TRUE.equals(summary.getPartialSuccess()));
    }

    static List<AttemptRow> rows(JobInfo jobInfo) {
        var rows = new ArrayList<AttemptRow>();
        var attempts = attemptList(jobInfo);
        for (var index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            rows.add(
                AttemptRow.builder()
                    .attemptNumber(index)
                    .status(attempt.getStatus())
                    .createdAt(attempt.getCreatedAt())
                    .endedAt(attempt.getEndedAt())
                    .bytesSynced(attempt.getBytesSynced())
                    .recordsSynced(attempt.getRecordsSynced())
                    .partialSuccess(attempt.getFailureSummary() == null ? null : attempt.getFailureSummary().getPartialSuccess())
                    .failures(failures(attempt))
                    .streams(
                        Optional.ofNullable(attempt.getStreamStats()).orElseGet(List::of).stream()
                            .filter(Objects::nonNull)
                            .map(JobReports::streamRow)
                            .toList()
                    )
                    .build()
            );
        }
        return rows;
    }

    static URI storeAttempts(RunContext runContext, List<AttemptRow> rows) throws IOException {
        var file = runContext.workingDir().createTempFile(".ion").toFile();
        try (var output = Files.newOutputStream(file.toPath())) {
            for (var row : rows) {
                FileSerde.write(output, row);
            }
        }
        return runContext.storage().putFile(file);
    }

    static Map<StreamDescriptor, StreamAssets.Committed> committed(JobInfo jobInfo) {
        var committed = new HashMap<StreamDescriptor, StreamAssets.Committed>();
        attemptList(jobInfo).stream()
            .flatMap(attempt -> Optional.ofNullable(attempt.getStreamStats()).orElseGet(List::of).stream())
            .filter(stats -> stats != null && stats.getStats() != null)
            .forEach(stats ->
            {
                var key = StreamDescriptor.builder().name(stats.getStreamName()).namespace(stats.getStreamNamespace()).build();
                committed.merge(
                    key,
                    new StreamAssets.Committed(stats.getStats().getRecordsCommitted(), stats.getStats().getBytesCommitted()),
                    (left, right) -> new StreamAssets.Committed(sum(left.records(), right.records()), sum(left.bytes(), right.bytes()))
                );
            });
        return committed;
    }

    static void emitAttemptTaskRuns(RunContext runContext, JobInfo jobInfo, List<AttemptRow> rows) {
        var parentTaskRunId = variable(runContext, "taskrun", "id");
        if (parentTaskRunId == null) {
            runContext.logger().debug("Not running inside a task run, skipping the per-attempt task runs");
            return;
        }

        var jobStatus = jobInfo.getJob().getStatus();
        for (var row : rows) {
            var state = state(row.getStatus(), jobStatus);
            var histories = row.getCreatedAt() != null && row.getEndedAt() != null
                ? List.of(new State.History(State.Type.RUNNING, row.getCreatedAt()), new State.History(state, row.getEndedAt()))
                : List.of(new State.History(state, Instant.now()));
            var attemptState = State.of(state, histories);

            var taskRun = TaskRun.builder()
                .id(IdUtils.create())
                .namespace(variable(runContext, "flow", "namespace"))
                .flowId(variable(runContext, "flow", "id"))
                .taskId("attempt-" + row.getAttemptNumber())
                .executionId(variable(runContext, "execution", "id"))
                .parentTaskRunId(parentTaskRunId)
                .state(attemptState)
                .attempts(List.of(TaskRunAttempt.builder().state(attemptState).build()))
                .build();

            runContext.dynamicWorkerResult(WorkerTaskResult.builder().taskRun(taskRun).build(), attemptLogs(row));
        }
    }

    static String failureMessage(JobInfo jobInfo, JobSummary summary, String jobUrl) {
        var firstMessage = summary.getFailures().stream()
            .map(failure -> failure.getExternalMessage() != null && !failure.getExternalMessage().isBlank()
                ? failure.getExternalMessage()
                : "[" + failure.getOrigin() + "/" + failure.getType() + "]")
            .findFirst();

        return "Airbyte job " + jobInfo.getJob().getId() + " ended " + summary.getStatus() + " after " + summary.getAttemptCount() + " attempt(s)"
            + firstMessage.map(message -> ": " + message).orElse("")
            + ". See the 'job.failures' output" + (jobUrl == null ? "" : " or " + jobUrl);
    }

    private static List<DynamicTaskRunLog> attemptLogs(AttemptRow row) {
        if (row.getFailures().isEmpty()) {
            return List.of();
        }
        return row.getFailures().stream()
            .map(failure -> new DynamicTaskRunLog(
                Level.ERROR,
                "[" + failure.getOrigin() + "/" + failure.getType() + "] "
                    + Optional.ofNullable(failure.getExternalMessage()).orElse("No failure message reported by Airbyte")
            ))
            .toList();
    }

    private static State.Type state(AttemptStatus attemptStatus, JobStatus jobStatus) {
        if (attemptStatus == AttemptStatus.SUCCEEDED) {
            return State.Type.SUCCESS;
        }
        if (attemptStatus == AttemptStatus.RUNNING && jobStatus == JobStatus.CANCELLED) {
            return State.Type.KILLED;
        }
        return State.Type.FAILED;
    }

    private static AttemptRow.Stream streamRow(AttemptStreamStats stats) {
        var values = stats.getStats();
        return AttemptRow.Stream.builder()
            .name(stats.getStreamName())
            .namespace(stats.getStreamNamespace())
            .recordsEmitted(values == null ? null : values.getRecordsEmitted())
            .recordsCommitted(values == null ? null : values.getRecordsCommitted())
            .recordsRejected(values == null ? null : values.getRecordsRejected())
            .bytesEmitted(values == null ? null : values.getBytesEmitted())
            .bytesCommitted(values == null ? null : values.getBytesCommitted())
            .build();
    }

    private static Duration duration(Instant start, Instant end) {
        return start == null || end == null || end.isBefore(start) ? null : Duration.between(start, end);
    }

    static Long sum(Long left, Long right) {
        if (left == null || right == null) {
            return left == null ? right : left;
        }
        return left + right;
    }

    private static String variable(RunContext runContext, String scope, String key) {
        return runContext.getVariables().get(scope) instanceof Map<?, ?> values && values.get(key) != null ? values.get(key).toString() : null;
    }
}
