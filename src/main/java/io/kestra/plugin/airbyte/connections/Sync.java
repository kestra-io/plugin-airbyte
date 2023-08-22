package io.kestra.plugin.airbyte.connections;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.utils.Await;
import io.kestra.plugin.airbyte.AbstractAirbyteConnection;
import io.kestra.plugin.airbyte.models.Attempt;
import io.kestra.plugin.airbyte.models.AttemptInfo;
import io.kestra.plugin.airbyte.models.JobInfo;
import io.kestra.plugin.airbyte.models.JobStatus;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.uri.UriTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static io.kestra.core.utils.Rethrow.throwSupplier;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a sync on a connection"
)
@Plugin(
    examples = {
        @Example(
            code = {
                "url: http://localhost:8080",
                "connectionId: e3b1ce92-547c-436f-b1e8-23b6936c12cd",
            }
        )
    }
)
public class Sync extends AbstractAirbyteConnection implements RunnableTask<Sync.Output> {
    private static final List<JobStatus> ENDED_JOB_STATUS = List.of(
        JobStatus.FAILED,
        JobStatus.CANCELLED,
        JobStatus.SUCCEEDED
    );

    @Schema(
        title = "The connection id to sync"
    )
    @PluginProperty(dynamic = true)
    private String connectionId;

    @Schema(
        title = "Wait for the end of the job.",
        description = "Allowing to capture job status & logs"
    )
    @PluginProperty
    @Builder.Default
    private Boolean wait = true;

    @Schema(
        title = "The max total wait duration"
    )
    @PluginProperty
    @Builder.Default
    Duration maxDuration = Duration.ofMinutes(60);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private transient Map<Integer, Integer> loggedLine = new HashMap<>();

    @Override
    public Sync.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        // Init with 1 as when triggering sync, an attempt is automatically generated
        AtomicInteger attemptCounter = new AtomicInteger(1);

        // create sync
        HttpResponse<JobInfo> syncResponse = this.request(
            runContext,
            HttpRequest
                .create(
                    HttpMethod.POST,
                    UriTemplate
                        .of("/api/v1/connections/sync/")
                        .toString()
                )
                .body(Map.of("connectionId", runContext.render(this.connectionId))),
            Argument.of(JobInfo.class)
        );

        JobInfo jobInfoRead = syncResponse.getBody().orElseThrow(() -> new IllegalStateException("Missing body on trigger"));

        logger.info("Job status {} with response: {}", syncResponse.getStatus(), jobInfoRead);
        Long jobId = jobInfoRead.getJob().getId();

        if (!this.wait) {
            return Output.builder()
                .jobId(jobId)
                .build();
        }

        // wait for end
        JobInfo finalJobStatus = Await.until(
            throwSupplier(() -> {
                HttpResponse<JobInfo> fetchJobRequest = this.request(
                    runContext,
                    HttpRequest
                        .create(
                            HttpMethod.POST,
                            UriTemplate
                                .of("/api/v1/jobs/get")
                                .toString()
                        )
                        .body(Map.of("id", jobId)),
                    Argument.of(JobInfo.class)
                );

                if (fetchJobRequest.getBody().isPresent()) {
                    JobInfo jobStatus = fetchJobRequest.getBody().get();
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
            Duration.ofSeconds(1),
            this.maxDuration
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
                    runContext.metric(Counter.of("records.commited", o.getStats().getRecordsCommitted(), "stream", o.getStreamName()));
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
            .jobId(jobId)
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
        @Schema(
            title = "The jobId created"
        )
        private final Long jobId;
    }
}
