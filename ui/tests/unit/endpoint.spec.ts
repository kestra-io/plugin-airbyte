import { describe, expect, test } from "vitest";
import { toQuery } from "../../src/composables/useEndpoint";
import { attemptsSubtitle, streamsSubtitle } from "../../src/composables/subtitles";
import { execution, succeededOutputs, task } from "../storybook/fixtures";

describe("toQuery", () => {
    test("drops unset filters and comma-separates lists", () => {
        expect(
            toQuery({
                level: "WARN",
                q: "  ",
                attempt: undefined,
                source: ["source", "destination"],
                page: 2,
            }),
        ).toEqual({ level: "WARN", source: "source,destination", page: "2" });
    });

    test("keeps attempt 0", () => {
        expect(toQuery({ attempt: 0, source: [] })).toEqual({ attempt: "0" });
    });
});

describe("subtitles", () => {
    test("attempts: counts from the outputs of the sync task run", async () => {
        const calls: unknown[] = [];
        const outputs = { job: { attemptCount: 3, failedAttemptCount: 2 } };
        const fetchOutputs = async (query?: unknown) => {
            calls.push(query);
            return outputs;
        };
        expect(await attemptsSubtitle({ task, execution, fetchOutputs })).toBe(
            "3 attempts · 2 failed",
        );
        expect(calls).toEqual([{ taskRunId: "tr-001" }]);
        expect(
            await attemptsSubtitle({ task, execution, fetchOutputs: async () => succeededOutputs }),
        ).toBe("1 attempt · 0 failed");
    });

    test("streams: stream count of the connection", async () => {
        expect(
            await streamsSubtitle({ task, execution, fetchOutputs: async () => succeededOutputs }),
        ).toBe("3 streams");
    });

    test("empty when outputs are missing or the fetch fails", async () => {
        expect(await attemptsSubtitle({ task, execution })).toBeUndefined();
        expect(
            await streamsSubtitle({
                task,
                execution,
                fetchOutputs: async () => {
                    throw new Error("boom");
                },
            }),
        ).toBeUndefined();
    });
});