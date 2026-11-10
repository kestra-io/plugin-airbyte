package io.kestra.plugin.airbyte;

import java.util.Map;
import java.util.function.Predicate;

import io.kestra.core.models.executions.LogEntry;
import io.kestra.core.runners.RunContext;

/** The log queue listeners of a test stay registered for the whole application context, so assertions must only see the logs of their own run. */
public final class TestLogs {
    private TestLogs() {
    }

    public static Predicate<LogEntry> ownedBy(RunContext runContext) {
        var taskRunId = ((Map<?, ?>) runContext.getVariables().get("taskrun")).get("id");
        return log -> taskRunId.equals(log.getTaskRunId());
    }
}
