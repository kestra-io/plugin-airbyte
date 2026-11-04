import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";

const TASKS = [
    "connections.Sync",
    "connections.CheckStatus",
    "cloud.jobs.Sync",
    "cloud.jobs.Reset",
];

// Only the Artifacts tab is registered: it replaces the topology cards and the details modal.
const exposes = Object.fromEntries(
    TASKS.map((type) => [
        type,
        [
            {
                slotName: "execution-artifact/details",
                path: "./src/components/SyncDetails.vue",
                additionalProperties: { title: "Airbyte details" },
            },
        ],
    ]),
);

export default defaultViteConfig({
    plugin: "io.kestra.plugin.airbyte",
    exposes,
});