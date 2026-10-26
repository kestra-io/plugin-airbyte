import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { defineComponent, h, nextTick, reactive } from "vue";
import { mount } from "@vue/test-utils";
import { useEndpoint } from "../../src/composables/useEndpoint";
import { useSyncData } from "../../src/composables/useSyncData";
import { task } from "../storybook/fixtures";

const run = (state: string) => ({ id: "tr-001", taskId: "sync", state: { current: state } });
const executionWith = (state: string) =>
    ({ id: "exec-abc123", taskRunList: [run(state)] }) as Record<string, any>;

function mountEndpoint(execution: Record<string, any>, fetchEndpoint: any) {
    return mount(
        defineComponent({
            props: ["execution", "task", "fetchEndpoint"],
            setup(props) {
                useEndpoint(props as any, "logs");
                return () => h("div");
            },
        }),
        { props: { execution, task, fetchEndpoint } },
    );
}

describe("reload when the task run ends", () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    test("a new execution object", async () => {
        const fetchEndpoint = vi.fn(async () => ({ origin: "kestra", results: [] }));
        const wrapper = mountEndpoint(executionWith("RUNNING"), fetchEndpoint);
        await vi.advanceTimersByTimeAsync(0);
        expect(fetchEndpoint).toHaveBeenCalledTimes(1);

        await wrapper.setProps({ execution: executionWith("SUCCESS") });
        await vi.advanceTimersByTimeAsync(0);
        expect(fetchEndpoint).toHaveBeenCalledTimes(2);
        expect(fetchEndpoint).toHaveBeenLastCalledWith("logs", {}, { taskRunId: "tr-001" });
    });

    test("a reactive execution mutated in place", async () => {
        const fetchEndpoint = vi.fn(async () => ({ origin: "kestra", results: [] }));
        const execution = reactive(executionWith("RUNNING"));
        mountEndpoint(execution, fetchEndpoint);
        await vi.advanceTimersByTimeAsync(0);
        expect(fetchEndpoint).toHaveBeenCalledTimes(1);

        execution.taskRunList[0].state.current = "SUCCESS";
        await vi.advanceTimersByTimeAsync(2500);
        expect(fetchEndpoint).toHaveBeenCalledTimes(2);
    });

    test("a plain execution mutated in place, which nothing tracks", async () => {
        const fetchEndpoint = vi.fn(async () => ({ origin: "kestra", results: [] }));
        const execution = executionWith("RUNNING");
        mountEndpoint(execution, fetchEndpoint);
        await vi.advanceTimersByTimeAsync(0);
        expect(fetchEndpoint).toHaveBeenCalledTimes(1);

        execution.taskRunList[0].state.current = "SUCCESS";
        await vi.advanceTimersByTimeAsync(2500);
        await nextTick();
        expect(fetchEndpoint).toHaveBeenCalledTimes(2);
    });

    test("the sync data reloads too, and a stale empty answer cannot win", async () => {
        const answers = [
            { delay: 100, outputs: {} },
            { delay: 10, outputs: { job: { status: "SUCCEEDED" } } },
        ];
        const fetchOutputs = vi.fn(() => {
            const { delay, outputs } = answers.shift()!;
            return new Promise((resolve) => setTimeout(() => resolve(outputs), delay));
        });
        const execution = executionWith("RUNNING");
        let data: ReturnType<typeof useSyncData> | undefined;
        mount(
            defineComponent({
                props: ["execution", "task", "fetchOutputs"],
                setup(props) {
                    data = useSyncData(props as any);
                    return () => h("div");
                },
            }),
            { props: { execution, task, fetchOutputs } },
        );
        await vi.advanceTimersByTimeAsync(0);
        execution.taskRunList[0].state.current = "SUCCESS";
        await vi.advanceTimersByTimeAsync(2500);
        await vi.advanceTimersByTimeAsync(200);
        expect(fetchOutputs).toHaveBeenCalledTimes(2);
        expect(data!.job.value?.status).toBe("SUCCEEDED");
    });
});