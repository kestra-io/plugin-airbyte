package io.kestra.plugin.airbyte.models;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@Value
@Jacksonized
@SuperBuilder
public class Event {
    Long timestamp;
    String message;
    String level;
    String logSource;
    Map<String, Object> caller;
}
