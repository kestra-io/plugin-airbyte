import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";

const TASKS = [
    { type: "connections.Sync", height: 120 },
    { type: "connections.CheckStatus", height: 120 },
    { type: "cloud.jobs.Sync", height: 90 },
    { type: "cloud.jobs.Reset", height: 90 },
];

// Card height = ~56px header + ~31.5px per row. Pre-run `height`: 2 rows (self-hosted), 1 row (Cloud). After a run: 6 rows.
const HEIGHT_WITH_EXECUTION = 246;

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
                    heightWithExecution: HEIGHT_WITH_EXECUTION,
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