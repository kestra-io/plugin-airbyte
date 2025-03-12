package io.kestra.plugin.airbyte.connections;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.plugin.airbyte.models.Attempt;
import io.kestra.plugin.airbyte.models.AttemptInfo;
import io.kestra.plugin.airbyte.models.JobInfo;
import io.kestra.plugin.airbyte.models.JobStatus;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static io.kestra.core.utils.Rethrow.throwSupplier;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Check job status of a running sync connection."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: airbyte_check_status
                namespace: company.team

                tasks:
                  - id: "check_status"
                    type: "io.kestra.plugin.airbyte.connections.CheckStatus"
                    url: http://localhost:8080
                    jobId: 970
                """
        )
    }
)
public class CheckStatus extends AbstractAirbyteConnection implements RunnableTask<CheckStatus.Output> {
    private static final List<JobStatus> ENDED_JOB_STATUS = List.of(
        JobStatus.FAILED,
        JobStatus.CANCELLED,
        JobStatus.SUCCEEDED
    );

    @Schema(title = "The job ID to check status for.")
    private Property<String> jobId;

    @Schema(title = "The maximum total wait duration.")
    @Builder.Default
    Property<Duration> maxDuration = Property.of(Duration.ofMinutes(60));

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private transient Map<Integer, Integer> loggedLine = new HashMap<>();

    @Schema(title = "Specify how often the task should poll for the sync status.")
    @Builder.Default
    Property<Duration> pollFrequency = Property.of(Duration.ofSeconds(1));

    @Override
    public CheckStatus.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        // Init with 1 as when triggering sync, an attempt is automatically generated
        AtomicInteger attemptCounter = new AtomicInteger(1);

        // Check rendered jobId provided is a long
        Long jobIdRendered = Long.parseLong(runContext.render(this.jobId).as(String.class).orElse(null));

        // wait for end
        JobInfo finalJobStatus = Await.until(
            throwSupplier(() -> {
                HttpRequest.HttpRequestBuilder fetchJobRequest = HttpRequest.builder()
                    .uri(URI.create(getUrl()+ "/api/v1/jobs/get/"))
                    .method("POST")
                    .body(HttpRequest.JsonRequestBody.builder()
                        .content(Map.of("id", jobIdRendered))
                        .build());

                HttpResponse<JobInfo> response = this.request(runContext, fetchJobRequest, JobInfo.class);

                if (response.getBody() != null) {
                    JobInfo jobStatus = response.getBody();
                    sendLog(logger, jobStatus);

                        // ended
                        if (ENDED_JOB_STATUS.contains(jobStatus.getJob().getStatus())) {
                            return jobStatus;
                        }

                        // Handle case of failed attempt, Airbyte started a new attempt
                        if (jobStatus.getAttempts().size() > attemptCounter.get()) {
                            logger.warn("Previous attempt failed, creating a new sync attempt ...");
                            attemptCounter.getAndIncrement();
                        }
                    }
                    return null;
                }),
                runContext.render(this.pollFrequency).as(Duration.class).orElseThrow(),
                runContext.render(this.maxDuration).as(Duration.class).orElseThrow()
        );

        // failure message
        finalJobStatus.getAttempts()
            .stream()
            .map(AttemptInfo::getAttempt)
            .map(Attempt::getFailureSummary)
            .filter(Objects::nonNull)
            .forEach(attemptFailureSummary -> logger.warn("Failure with reason {}", attemptFailureSummary));

        // handle failed attempt
        if (!finalJobStatus.getJob().getStatus().equals(JobStatus.SUCCEEDED)) {
            int attemptCount = finalJobStatus.getAttempts().size();
            throw new Exception("Failed run with status '" + finalJobStatus.getJob().getStatus() +
                    "' after " +  attemptCount + " attempt(s) : " + finalJobStatus
            );
        }

        // metrics
        runContext.metric(Counter.of("attempts.count", finalJobStatus.getAttempts().size()));

        finalJobStatus.getAttempts()
            .stream()
            .map(AttemptInfo::getAttempt)
            .filter(attempt -> attempt.getStreamStats() != null)
            .flatMap(attempt -> attempt.getStreamStats().stream())
            .forEach(o -> {
                if (o.getStats().getRecordsCommitted() != null) {
                    runContext.metric(Counter.of("records.committed", o.getStats().getRecordsCommitted(), "stream", o.getStreamName()));
                }
                if (o.getStats().getRecordsEmitted() != null) {
                    runContext.metric(Counter.of("records.emitted", o.getStats().getRecordsEmitted(), "stream", o.getStreamName()));
                }
                if (o.getStats().getBytesEmitted() != null) {
                    runContext.metric(Counter.of("bytes.emitted", o.getStats().getBytesEmitted(), "stream", o.getStreamName()));
                }
                if (o.getStats().getStateMessagesEmitted() != null) {
                    runContext.metric(Counter.of("state.emitted", o.getStats().getStateMessagesEmitted(), "stream", o.getStreamName()));
                }
            });

        return Output.builder()
                .finalJobStatus(finalJobStatus.getJob().getStatus().toString())
                .build();
    }

    private void sendLog(Logger logger, JobInfo job) {
        int index = 0;

        for (AttemptInfo attempt : job.getAttempts()) {
            if (!loggedLine.containsKey(index) || attempt.getLogs().getLogLines().size() > loggedLine.get(index)) {
                attempt.getLogs()
                        .getLogLines()
                        .subList(!loggedLine.containsKey(index) ? 0 : loggedLine.get(index) + 1, attempt.getLogs().getLogLines().size())
                        .forEach(msg -> {
                            if (msg.contains("ERROR[")) {
                                logger.error(msg);
                            } else if (msg.contains("DEBUG[")) {
                                logger.debug(msg);
                            } else if (msg.contains("TRACE[")) {
                                logger.trace(msg);
                            } else {
                                logger.info(msg);
                            }
                        });

                loggedLine.put(index, attempt.getLogs().getLogLines().size());
            }
            index++;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The final job status.")
        private final String finalJobStatus;
    }
}
