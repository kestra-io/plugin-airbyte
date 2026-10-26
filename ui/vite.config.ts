import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";

// Node base (56) + KsTopologyDetails rows (~31px each). Before a run the card shows one row on Cloud
// tasks (connection ID) and two on self-hosted ones; with an execution it shows six rows.
const HEIGHT_WITH_EXECUTION = 245;
const SELF_HOSTED = ["logs", "attempts", "streams"];
const TASKS = [
    { type: "connections.Sync", height: 120, artifacts: SELF_HOSTED },
    { type: "connections.CheckStatus", height: 120, artifacts: SELF_HOSTED },
    // Airbyte Cloud has no attempts file and no Airbyte logs (the logs endpoint would only repeat the Kestra
    // logs tab), but it still records the connection snapshot behind the streams view.
    { type: "cloud.jobs.Sync", height: 90, artifacts: ["streams"] },
    { type: "cloud.jobs.Reset", height: 90, artifacts: ["streams"] },
];

// One entry per view in the execution Artifacts tab; each reads one of the plugin endpoints.
const ARTIFACTS: Record<string, { path: string; title: string }> = {
    logs: { path: "./src/components/AirbyteLogsArtifact.vue", title: "Airbyte logs" },
    attempts: { path: "./src/components/AirbyteAttemptsArtifact.vue", title: "Attempts" },
    streams: { path: "./src/components/AirbyteStreamsArtifact.vue", title: "Streams" },
};

// A customAction with no property to show makes the host open a dialog rendering the topology-task-modal slot.
const exposes = Object.fromEntries(
    TASKS.map(({ type, height, artifacts }) => [
        type,
        [
            {
                slotName: "topology-details",
                path: "./src/components/SyncTopologyDetails.vue",
                additionalProperties: {
                    height,
                    heightWithExecution: HEIGHT_WITH_EXECUTION,
                    customAction: { label: "Airbyte details" },
                },
            },
            {
                slotName: "topology-task-modal",
                path: "./src/components/SyncDetails.vue",
            },
            ...artifacts.map((id) => ({
                slotName: `execution-artifact/${id}`,
                path: ARTIFACTS[id].path,
                additionalProperties: { title: ARTIFACTS[id].title },
            })),
        ],
    ]),
);

export default defaultViteConfig({
    plugin: "io.kestra.plugin.airbyte",
    exposes,
});