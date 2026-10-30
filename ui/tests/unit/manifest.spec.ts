import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";

// A customAction without taskProp makes the host open a dialog rendering the topology-task-modal
// component; with taskProp it would open the side drawer with a redundant editor block.
describe("vite.config.ts exposes", () => {
    const config = readFileSync(resolve(process.cwd(), "vite.config.ts"), "utf8");

    test("builds the same dialog wiring for each of the four tasks", () => {
        for (const type of [
            "connections.Sync",
            "connections.CheckStatus",
            "cloud.jobs.Sync",
            "cloud.jobs.Reset",
        ]) {
            expect(config).toContain(`type: "${type}"`);
        }
        expect(config.match(/customAction: \{ label: "Show Details" \}/g)).toHaveLength(1);
        expect(config.match(/slotName: "topology-task-modal"/g)).toHaveLength(1);
        expect(config.match(/slotName: "topology-details"/g)).toHaveLength(1);
    });

    test("self-hosted tasks get logs, attempts and streams; Cloud tasks only streams", () => {
        expect(config).toContain('const SELF_HOSTED = ["logs", "attempts", "streams"]');
        expect(config.match(/artifacts: SELF_HOSTED/g)).toHaveLength(2);
        expect(config.match(/artifacts: \["streams"\]/g)).toHaveLength(2);
        expect(config).toContain("slotName: `execution-artifact/${id}`");
        for (const component of ["Logs", "Attempts", "Streams"]) {
            expect(config).toContain(`./src/components/Airbyte${component}Artifact.vue`);
        }
    });

    test("no taskProp and no drawer slot", () => {
        expect(config).not.toContain("taskProp");
        expect(config).not.toContain("topology-task-drawer");
    });
});