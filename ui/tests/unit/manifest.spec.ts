import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";

describe("vite.config.ts exposes", () => {
    const config = readFileSync(resolve(process.cwd(), "vite.config.ts"), "utf8");

    test("registers the four tasks", () => {
        for (const type of [
            "connections.Sync",
            "connections.CheckStatus",
            "cloud.jobs.Sync",
            "cloud.jobs.Reset",
        ]) {
            expect(config).toContain(`"${type}"`);
        }
    });

    test("registers only the execution-artifact/details slot", () => {
        expect(config.match(/slotName: "/g)).toHaveLength(1);
        expect(config.match(/slotName: "execution-artifact\/details"/g)).toHaveLength(1);
        for (const slot of ["topology-details", "topology-task-modal", "topology-task-drawer"]) {
            expect(config).not.toContain(slot);
        }
        expect(config).not.toContain("customAction");
    });
});
