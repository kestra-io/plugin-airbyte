import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { defineComponent, h } from "vue";
import { mount } from "@vue/test-utils";
import { useEndpoint } from "../../src/composables/useEndpoint";
import { useSyncData } from "../../src/composables/useSyncData";
import { succeededOutputs, task } from "../storybook/fixtures";

const execution = () => ({
    id: "exec-abc123",
    taskRunList: [{ id: "tr-001", taskId: "sync", state: { current: "RUNNING" } }],
});

// A view like the logs or streams ones: it reads the outputs and calls an endpoint, with the same props.
function mountView(fetchOutputs: any, fetchEndpoint: any = async () => ({ results: [] })) {
    return mount(
        defineComponent({
            props: ["execution", "task", "fetchOutputs", "fetchEndpoint"],
            setup(props) {
                useSyncData(props as any);
                useEndpoint(props as any, "logs");
                return () => h("div");
            },
        }),
        { props: { execution: execution(), task, fetchOutputs, fetchEndpoint } },
    );
}

const setVisibility = (state: "visible" | "hidden") => {
    Object.defineProperty(document, "visibilityState", { configurable: true, value: state });
    document.dispatchEvent(new Event("visibilitychange"));
};

// fetchOutputs calls made by the detection only, i.e. after the initial load of the views.
const detectionCalls = (fetchOutputs: ReturnType<typeof vi.fn>) => fetchOutputs.mock.calls.length;

describe("end-of-run detection", () => {
    beforeEach(() => {
        vi.useFakeTimers();
        setVisibility("visible");
    });
    afterEach(() => {
        vi.useRealTimers();
        setVisibility("visible");
    });

    test("one poller per view: a single fetchOutputs call per tick", async () => {
        const fetchOutputs = vi.fn(async () => ({}));
        mountView(fetchOutputs);
        await vi.advanceTimersByTimeAsync(0);
        // the load of useSyncData for the running task
        const initial = detectionCalls(fetchOutputs);

        await vi.advanceTimersByTimeAsync(4000);
        expect(detectionCalls(fetchOutputs) - initial).toBe(1);
    });

    test("the interval grows up to 30 s", async () => {
        const fetchOutputs = vi.fn(async () => ({}));
        mountView(fetchOutputs);
        await vi.advanceTimersByTimeAsync(0);
        const initial = detectionCalls(fetchOutputs);

        // 4 s, then 6 s, 9 s, 13.5 s, 20.25 s, 30 s, 30 s...
        const at: number[] = [];
        let last = initial;
        for (let second = 1; second <= 200; second++) {
            await vi.advanceTimersByTimeAsync(1000);
            if (detectionCalls(fetchOutputs) > last) {
                at.push(second);
                last = detectionCalls(fetchOutputs);
            }
        }
        const gaps = at.slice(1).map((value, index) => value - at[index]);
        expect(at[0]).toBe(4);
        expect(gaps.slice(0, 4)).toEqual([6, 9, 14, 20]);
        expect(Math.max(...gaps)).toBeLessThanOrEqual(30);
        expect(gaps.at(-1)).toBe(30);
    });

    test("nothing is asked while the tab is hidden, and it resumes when visible", async () => {
        const fetchOutputs = vi.fn(async () => ({}));
        mountView(fetchOutputs);
        await vi.advanceTimersByTimeAsync(0);
        setVisibility("hidden");
        const before = detectionCalls(fetchOutputs);

        await vi.advanceTimersByTimeAsync(120_000);
        expect(detectionCalls(fetchOutputs)).toBe(before);

        setVisibility("visible");
        await vi.advanceTimersByTimeAsync(0);
        expect(detectionCalls(fetchOutputs)).toBe(before + 1);
    });

    test("stops at the cap when the outputs never come", async () => {
        const fetchOutputs = vi.fn(async () => ({}));
        mountView(fetchOutputs);
        await vi.advanceTimersByTimeAsync(15 * 60 * 1000 + 60_000);
        const atCap = detectionCalls(fetchOutputs);

        await vi.advanceTimersByTimeAsync(10 * 60 * 1000);
        expect(detectionCalls(fetchOutputs)).toBe(atCap);
        expect(atCap).toBeLessThan(60);
    });

    test("stops on completion", async () => {
        let done = false;
        const fetchOutputs = vi.fn(async () => (done ? succeededOutputs : {}));
        const fetchEndpoint = vi.fn(async () => ({ results: [] }));
        mountView(fetchOutputs, fetchEndpoint);
        await vi.advanceTimersByTimeAsync(5000);
        done = true;
        await vi.advanceTimersByTimeAsync(10_000);
        const atDone = detectionCalls(fetchOutputs);
        expect(fetchEndpoint).toHaveBeenCalledTimes(2);

        await vi.advanceTimersByTimeAsync(10 * 60 * 1000);
        // only the reload of useSyncData after the run ended
        expect(detectionCalls(fetchOutputs)).toBe(atDone);
    });

    test("stops on unmount", async () => {
        const fetchOutputs = vi.fn(async () => ({}));
        const wrapper = mountView(fetchOutputs);
        await vi.advanceTimersByTimeAsync(5000);
        wrapper.unmount();
        const before = detectionCalls(fetchOutputs);

        await vi.advanceTimersByTimeAsync(10 * 60 * 1000);
        expect(detectionCalls(fetchOutputs)).toBe(before);
    });
});