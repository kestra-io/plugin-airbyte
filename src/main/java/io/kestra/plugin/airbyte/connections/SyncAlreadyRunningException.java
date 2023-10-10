package io.kestra.plugin.airbyte.connections;

public class SyncAlreadyRunningException extends Exception {
    public SyncAlreadyRunningException(String errorMessage) {
        super(errorMessage);
    }
}
