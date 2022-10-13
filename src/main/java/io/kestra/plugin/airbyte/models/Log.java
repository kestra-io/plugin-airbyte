package io.kestra.plugin.airbyte.models;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Value
@Jacksonized
@SuperBuilder
public class Log {
    List<String> logLines;
}
