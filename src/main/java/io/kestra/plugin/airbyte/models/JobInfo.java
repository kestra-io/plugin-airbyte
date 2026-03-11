package io.kestra.plugin.airbyte.models;

import java.util.List;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class JobInfo {
    Job job;
    List<AttemptInfo> attempts;
}
