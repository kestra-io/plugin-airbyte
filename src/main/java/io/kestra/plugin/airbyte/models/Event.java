package io.kestra.plugin.airbyte.models;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

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
