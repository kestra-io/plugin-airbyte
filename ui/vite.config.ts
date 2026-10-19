import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";

export default defaultViteConfig({
    plugin: "io.kestra.plugin.airbyte",

    exposes: {
        "connections.Sync": [
            {
                slotName: "topology-details",
                path: "./src/components/SyncTopologyDetails.vue",
                additionalProperties: {
                    // node base (56) + KsTopologyDetails rows (~31px each)
                    // no execution: connection ID + URL (2 rows)
                    height: 120,
                    // with execution: route, streams, rows, bytes, attempts, status (6 rows)
                    heightWithExecution: 245,
                    customAction: { label: "Airbyte details" },
                },
            },
            {
                slotName: "topology-task-modal",
                path: "./src/components/SyncDetails.vue",
            },
        ],
        "connections.CheckStatus": [
            {
                slotName: "topology-details",
                path: "./src/components/SyncTopologyDetails.vue",
                additionalProperties: {
                    // node base (56) + KsTopologyDetails rows (~31px each)
                    // no execution: connection ID + URL (2 rows)
                    height: 120,
                    // with execution: route, streams, rows, bytes, attempts, status (6 rows)
                    heightWithExecution: 245,
                    customAction: { label: "Airbyte details" },
                },
            },
            {
                slotName: "topology-task-modal",
                path: "./src/components/SyncDetails.vue",
            },
        ],
        "cloud.jobs.Sync": [
            {
                slotName: "topology-details",
                path: "./src/components/SyncTopologyDetails.vue",
                additionalProperties: {
                    // node base (56) + KsTopologyDetails rows (~31px each)
                    // no execution: connection ID + URL (2 rows)
                    height: 120,
                    // with execution: route, streams, rows, bytes, attempts, status (6 rows)
                    heightWithExecution: 245,
                    customAction: { label: "Airbyte details" },
                },
            },
            {
                slotName: "topology-task-modal",
                path: "./src/components/SyncDetails.vue",
            },
        ],
        "cloud.jobs.Reset": [
            {
                slotName: "topology-details",
                path: "./src/components/SyncTopologyDetails.vue",
                additionalProperties: {
                    // node base (56) + KsTopologyDetails rows (~31px each)
                    // no execution: connection ID + URL (2 rows)
                    height: 120,
                    // with execution: route, streams, rows, bytes, attempts, status (6 rows)
                    heightWithExecution: 245,
                    customAction: { label: "Airbyte details" },
                },
            },
            {
                slotName: "topology-task-modal",
                path: "./src/components/SyncDetails.vue",
            },
        ],
    },
});