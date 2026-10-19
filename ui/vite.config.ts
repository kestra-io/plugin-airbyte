import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";

// Node base (56) + KsTopologyDetails rows (~31px each). Before a run the card shows one row on Cloud
// tasks (connection ID) and two on self-hosted ones; with an execution it shows six rows.
const HEIGHT_WITH_EXECUTION = 245;
const TASKS = [
    { type: "connections.Sync", height: 120 },
    { type: "connections.CheckStatus", height: 120 },
    { type: "cloud.jobs.Sync", height: 90 },
    { type: "cloud.jobs.Reset", height: 90 },
];

// A customAction with no property to show makes the host open a dialog rendering the topology-task-modal slot.
const exposes = Object.fromEntries(
    TASKS.map(({ type, height }) => [
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
        ],
    ]),
);

export default defaultViteConfig({
    plugin: "io.kestra.plugin.airbyte",
    exposes,
});