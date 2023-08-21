package io.kestra.plugin.airbyte.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import java.time.Instant;

@Value
@Jacksonized
@SuperBuilder
@JsonIgnoreProperties(ignoreUnknown = true)
public class Job {
    Long id;
    JobConfigType configType;
    String configId;
    Instant createdAt;
    Instant updatedAt;
    JobStatus status;
    ResetConfig resetConfig;
}
