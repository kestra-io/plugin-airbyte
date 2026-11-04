import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";

const TASKS = [
    { type: "connections.Sync", height: 120 },
    { type: "connections.CheckStatus", height: 120 },
    { type: "cloud.jobs.Sync", height: 90 },
    { type: "cloud.jobs.Reset", height: 90 },
];

// After a run the details card shows 6 rows (about 36px each at 11px text with 8px row padding), plus the ~30px
// badge row the host draws under the node, so heightWithExecution no longer follows the pre-run `height`.
const POST_EXECUTION_CONTENT_HEIGHT = 6 * 36;
const EXECUTION_BADGE_ROW = 30;
const HEIGHT_WITH_EXECUTION = POST_EXECUTION_CONTENT_HEIGHT + EXECUTION_BADGE_ROW;

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