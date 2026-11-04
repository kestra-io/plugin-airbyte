import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";

const TASKS = [
    { type: "connections.Sync", height: 120 },
    { type: "connections.CheckStatus", height: 120 },
    { type: "cloud.jobs.Sync", height: 90 },
    { type: "cloud.jobs.Reset", height: 90 },
];

// With an execution the host draws a ~30px badge row (artifact count and duration) under the node,
// so the details card needs that much more room than before a run.
const EXECUTION_BADGE_ROW = 30;

// A customAction with no property to show makes the host open a dialog rendering the topology-task-modal slot.
// Kestra 2.0.x renders the topology slots; 2.1+ ignores them and renders execution-artifact/details.
const exposes = Object.fromEntries(
    TASKS.map(({ type, height }) => [
        type,
        [
            {
                slotName: "topology-details",
                path: "./src/components/SyncTopologyDetails.vue",
                additionalProperties: {
                    height,
                    heightWithExecution: height + EXECUTION_BADGE_ROW,
                    customAction: { label: "Airbyte details" },
                },
            },
            {
                slotName: "execution-artifact/details",
                path: "./src/components/SyncDetails.vue",
                additionalProperties: { title: "Airbyte details" },
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
