package io.kestra.plugin.airbyte.models;

import java.util.List;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@Jacksonized
@SuperBuilder
public class Log {
    List<String> logLines;
    List<Event> events;
    String version;
}