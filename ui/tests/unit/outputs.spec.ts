import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { defineComponent, h } from "vue";
import { mount } from "@vue/test-utils";
import { isSyncOutputs, useSyncData } from "../../src/composables/useSyncData";
import { succeededOutputs, task } from "../storybook/fixtures";

const run = (state: string) => ({ id: "tr-001", taskId: "sync", state: { current: state } });

function mountData(execution: Record<string, any>, extra: Record<string, unknown>) {
    let data!: ReturnType<typeof useSyncData>;
    mount(
        defineComponent({
            props: ["execution", "task", "fetchOutputs", "fetchMetrics"],
            setup(props) {
                data = useSyncData(props as any);
                return () => h("div");
            },
        }),
        { props: { execution, task, ...extra } },
    );
    return () => data;
}

// What the host's `/outputs/tasks/{execution}` endpoint answers: the task runs that have outputs.
const executionLevelList = [{ taskId: "sync", taskRunId: "tr-001", inline: true }];

describe("isSyncOutputs", () => {
    test("accepts the outputs of a sync and rejects anything else", () => {
        expect(isSyncOutputs(succeededOutputs)).toBe(true);
        expect(isSyncOutputs({ jobId: 1 })).toBe(true);
        expect(isSyncOutputs(executionLevelList)).toBe(false);
        expect(isSyncOutputs({})).toBe(false);
        expect(isSyncOutputs({ unrelated: 1 })).toBe(false);
        expect(isSyncOutputs(null)).toBe(false);
    });
});

describe("useSyncData with an unreliable host", () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    test("a wrong-shape answer is ignored and the next one is used", async () => {
        const answers: unknown[] = [executionLevelList, succeededOutputs];
        const fetchOutputs = vi.fn(async () => answers.shift());
        const data = mountData(
            { id: "exec-abc123", taskRunList: [run("SUCCESS")] },
            { fetchOutputs },
        );
        await vi.advanceTimersByTimeAsync(0);
        expect(data().job.value).toBeUndefined();

        await vi.advanceTimersByTimeAsync(400);
        expect(fetchOutputs).toHaveBeenCalledTimes(2);
        expect(data().job.value?.status).toBe("SUCCEEDED");
        expect(data().loadState.value).toBe("loaded");
    });

    test("a host that keeps answering with the list ends in a visible state", async () => {
        const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
        const fetchOutputs = vi.fn(async () => executionLevelList);
        const data = mountData(
            { id: "exec-abc123", taskRunList: [run("SUCCESS")] },
            { fetchOutputs },
        );
        await vi.advanceTimersByTimeAsync(2000);
        expect(fetchOutputs).toHaveBeenCalledTimes(3);
        expect(data().taskOutputs.value).toBeNull();
        expect(data().loadState.value).toBe("unusable");
        expect(warn).toHaveBeenCalled();
        warn.mockRestore();
    });

    test("a missing fetchOutputs is reported, not silent", async () => {
        const data = mountData({ id: "exec-abc123", taskRunList: [run("SUCCESS")] }, {});
        await vi.advanceTimersByTimeAsync(0);
        expect(data().loadState.value).toBe("unsupported");
    });

    test("never asks for outputs before the task run exists", async () => {
        const fetchOutputs = vi.fn(async () => succeededOutputs);
        const execution = { id: "exec-abc123", taskRunList: [] as any[] };
        const data = mountData(execution, { fetchOutputs });
        await vi.advanceTimersByTimeAsync(0);
        expect(fetchOutputs).not.toHaveBeenCalled();

        execution.taskRunList.push(run("SUCCESS"));
        await vi.advanceTimersByTimeAsync(2500);
        expect(fetchOutputs).toHaveBeenCalledTimes(1);
        expect(fetchOutputs).toHaveBeenCalledWith({ taskRunId: "tr-001" });
        expect(data().job.value?.status).toBe("SUCCEEDED");
    });
});