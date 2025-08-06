package io.kestra.plugin.airbyte.models;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.junit.annotations.KestraTest;

@KestraTest
class EventTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializeWithUnknownProperties() throws Exception {
        String json = """
            {
                "timestamp": 1234567890,
                "message": "Test message",
                "level": "INFO",
                "logSource": "test-source",
                "caller": {"class": "TestClass", "method": "testMethod"},
                "stackTrace": ["line1", "line2", "line3"],
                "someOtherUnknownField": "value"
            }
            """;

        Event event = objectMapper.readValue(json, Event.class);

        assertThat(event, is(notNullValue()));
        assertThat(event.getTimestamp(), is(1234567890L));
        assertThat(event.getMessage(), is("Test message"));
        assertThat(event.getLevel(), is("INFO"));
        assertThat(event.getLogSource(), is("test-source"));
        assertThat(event.getCaller(), is(notNullValue()));
        assertThat(event.getCaller().get("class"), is("TestClass"));
        assertThat(event.getCaller().get("method"), is("testMethod"));
    }
}