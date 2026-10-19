import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";

// A customAction without taskProp makes the host open a dialog rendering the topology-task-modal
// component; with taskProp it would open the side drawer with a redundant editor block.
describe("vite.config.ts exposes", () => {
    const config = readFileSync(resolve(process.cwd(), "vite.config.ts"), "utf8");

    test("each of the four tasks opens the details dialog", () => {
        expect(config.match(/customAction: \{ label: "Airbyte details" \}/g)).toHaveLength(4);
        expect(config.match(/slotName: "topology-task-modal"/g)).toHaveLength(4);
        expect(config.match(/slotName: "topology-details"/g)).toHaveLength(4);
    });

    test("no taskProp and no drawer slot", () => {
        expect(config).not.toContain("taskProp");
        expect(config).not.toContain("topology-task-drawer");
    });
});