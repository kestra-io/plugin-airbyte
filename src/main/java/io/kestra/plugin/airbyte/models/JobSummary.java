package io.kestra.plugin.airbyte.models;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
@Schema(title = "Airbyte job summary", description = "Final state of the Airbyte job. The job ID is exposed by the `jobId` output of `Sync`")
public class JobSummary {
    @Schema(title = "Config type", description = "Kind of job, for example `sync`")
    JobConfigType configType;

    @Schema(title = "Status", description = "Terminal job status")
    JobStatus status;

    @Schema(title = "Created at")
    Instant createdAt;

    @Schema(title = "Started at")
    Instant startedAt;

    @Schema(title = "Ended at", description = "End time of the last attempt")
    Instant endedAt;

    @Schema(title = "Duration", description = "Time between the job start and the end of its last attempt. Templates render it as a number of seconds, for example `31.0`")
    Duration duration;

    @Schema(title = "Attempt count")
    Integer attemptCount;

    @Schema(title = "Failed attempt count")
    Integer failedAttemptCount;

    @Schema(title = "Partial success", description = "`true` when at least one attempt committed records before failing")
    Boolean partialSuccess;

    @Schema(title = "Failures", description = "Sanitized failure reasons of every attempt")
    List<FailureSummary> failures;
}
