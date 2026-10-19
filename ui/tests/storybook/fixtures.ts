export const task = {
    id: "sync",
    type: "io.kestra.plugin.airbyte.connections.Sync",
    url: "http://localhost:8080",
    connectionId: "e3b1ce92-547c-436f-b1e8-23b6936c12cd",
};

export const cloudTask = {
    id: "sync",
    type: "io.kestra.plugin.airbyte.cloud.jobs.Sync",
    connectionId: "e3b1ce92-547c-436f-b1e8-23b6936c12cd",
};

export const execution = {
    id: "exec-abc123",
    namespace: "company.team",
    flowId: "airbyte-sync",
    state: { current: "SUCCESS", startDate: "2024-01-15T10:00:00Z" } as any,
    taskRunList: [{ id: "tr-001", taskId: "sync", executionId: "exec-abc123" }],
} as any;

const connection = {
    connectionId: "e3b1ce92-547c-436f-b1e8-23b6936c12cd",
    name: "Postgres to Snowflake",
    status: "active",
    workspaceId: "ws-1",
    sourceName: "production db",
    sourceType: "Postgres",
    destinationName: "warehouse",
    destinationType: "Snowflake",
    streamCount: 3,
};

const destination = [
    { name: "customers", namespace: "public", rows: 12_500, bytes: 4_200_000, recordsRejected: 0 },
    { name: "orders", namespace: "public", rows: 48_000, bytes: 19_000_000, recordsRejected: 12 },
    { name: "refunds", namespace: "public", rows: 300, bytes: 90_000, recordsRejected: 0 },
];

const succeededJob = {
    configType: "SYNC",
    status: "SUCCEEDED",
    duration: "PT3M15S",
    attemptCount: 1,
    failedAttemptCount: 0,
    partialSuccess: false,
    failures: [],
};

export const succeededOutputs = {
    jobId: 71,
    job: succeededJob,
    metadata: { rowsSynced: 60_800, bytesSynced: 23_290_000, recordsRejected: 12, destination },
    connection,
    jobUrl: "https://airbyte.example.com/workspaces/ws-1/connections/c/timeline?openLogs=true&jobId=71",
};

export const failedOutputs = {
    jobId: 72,
    job: {
        ...succeededJob,
        status: "FAILED",
        attemptCount: 3,
        failedAttemptCount: 3,
        failures: [
            {
                origin: "SOURCE",
                type: "CONFIG_ERROR",
                externalMessage: "Could not connect to the source: password authentication failed",
                stream: "public.orders",
            },
        ],
    },
    metadata: { rowsSynced: 0, destination: [] },
    connection,
};

export const repeatedFailureOutputs = {
    ...failedOutputs,
    job: {
        ...failedOutputs.job,
        attemptCount: 3,
        failures: [
            ...Array.from({ length: 3 }, () => ({
                origin: "SOURCE",
                type: "SYSTEM_ERROR",
                externalMessage: "Something went wrong in the connector.",
            })),
            { origin: "SOURCE", type: "CONFIG_ERROR", externalMessage: "Bad credentials" },
            {
                origin: "SOURCE",
                type: "SYSTEM_ERROR",
                externalMessage: "Something went wrong in the connector.",
            },
        ],
    },
};

export const warningOutputs = {
    jobId: 73,
    job: {
        ...succeededJob,
        status: "FAILED",
        attemptCount: 2,
        failedAttemptCount: 2,
        partialSuccess: true,
        failures: [
            {
                origin: "DESTINATION",
                type: "TRANSIENT_ERROR",
                externalMessage: "Destination timed out after committing 12,500 records",
            },
        ],
    },
    metadata: { rowsSynced: 12_500, bytesSynced: 4_200_000, destination: destination.slice(0, 1) },
    connection,
};

export const cloudOutputs = {
    job: {
        jobId: 99,
        status: "succeeded",
        duration: "PT1M2S",
        bytesSynced: 5_000_000,
        rowsSynced: 20_000,
    },
    connection: { ...connection, streamCount: 2 },
    jobUrl: "https://cloud.airbyte.com/workspaces/ws-1/connections/c/timeline?openLogs=true&jobId=99",
};

export const fetchers = (outputs: Record<string, any>) => ({
    execution,
    fetchOutputs: async () => outputs,
    fetchMetrics: async () => ({ results: [], total: 0 }),
});