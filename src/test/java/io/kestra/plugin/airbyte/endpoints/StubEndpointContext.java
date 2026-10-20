package io.kestra.plugin.airbyte.endpoints;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.event.Level;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.plugins.endpoint.PluginEndpointContext;
import io.kestra.core.plugins.endpoint.PluginEndpointResponse;
import io.kestra.core.plugins.endpoint.ScopedStorage;
import io.kestra.core.plugins.endpoint.TaskRunLogsFetcher;
import io.kestra.core.plugins.endpoint.TaskRunOutputsFetcher;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.FileAttributes;

final class StubEndpointContext implements PluginEndpointContext {
    private final Map<String, List<String>> parameters = new HashMap<>();
    private final Map<String, Object> outputs = new HashMap<>();
    private final Map<URI, byte[]> files = new HashMap<>();
    private List<LogEntry> logs = List.of();
    private Level requestedLevel;

    StubEndpointContext param(String name, String value) {
        parameters.put(name, List.of(value));
        return this;
    }

    StubEndpointContext output(String name, Object value) {
        outputs.put(name, value);
        return this;
    }

    StubEndpointContext file(String uri, byte[] content) {
        files.put(URI.create(uri), content);
        return this;
    }

    StubEndpointContext logs(List<LogEntry> logs) {
        this.logs = logs;
        return this;
    }

    Level requestedLevel() {
        return requestedLevel;
    }

    static JsonNode json(PluginEndpointResponse response) throws IOException {
        return JacksonMapper.ofJson().readTree(response.body());
    }

    @Override
    public Map<String, List<String>> parameters() {
        return parameters;
    }

    @Override
    public Map<String, Object> body() {
        return Map.of();
    }

    @Override
    public String executionId() {
        return "exec";
    }

    @Override
    public String taskRunId() {
        return "taskrun";
    }

    @Override
    public TaskRunLogsFetcher logs() {
        return minLevel ->
        {
            requestedLevel = minLevel;
            return logs.stream().filter(log -> log.getLevel().toInt() >= minLevel.toInt()).toList();
        };
    }

    @Override
    public TaskRunOutputsFetcher outputs() {
        return () -> outputs;
    }

    @Override
    public ScopedStorage storage() {
        return new ScopedStorage() {
            @Override
            public InputStream getFile(URI uri) throws IOException {
                var content = files.get(uri);
                if (content == null) {
                    throw new NoSuchFileException(uri.toString());
                }
                return new ByteArrayInputStream(content);
            }

            @Override
            public List<FileAttributes> list(URI uri) {
                return List.of();
            }

            @Override
            public boolean exists(URI uri) {
                return files.containsKey(uri);
            }

            @Override
            public URI putFile(URI uri, InputStream data) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean deleteFile(URI uri) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
