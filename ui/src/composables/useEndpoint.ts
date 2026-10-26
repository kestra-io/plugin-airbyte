import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref, shallowRef, watch } from "vue";
import { useTaskRun } from "./useSyncData";

type SlotProps = KnownSlotProps["execution-artifact"];

export type EndpointStatus =
    | "unsupported"
    | "noExecution"
    | "notRun"
    | "error"
    | "loading"
    | "ready";

export type EndpointParams = Record<string, string | number | string[] | undefined>;

// fetchEndpoint only takes string values: unset filters are dropped and lists are comma-separated,
// which the endpoints accept as well as repeated parameters.
export function toQuery(params: EndpointParams): Record<string, string> {
    const query: Record<string, string> = {};
    for (const [key, value] of Object.entries(params)) {
        const text = Array.isArray(value) ? value.join(",") : value;
        if (text !== undefined && String(text).trim() !== "") query[key] = String(text);
    }
    return query;
}

// Calls one of this plugin's endpoints on the sync's own task run (the `attempt-N` sub task runs have no
// outputs). fetchEndpoint is optional because older hosts don't provide it, and failures are best-effort.
export function useEndpoint<T>(
    props: SlotProps,
    name: string,
    params: () => EndpointParams = () => ({}),
) {
    const data = shallowRef<T | null>(null);
    const loading = ref(false);
    const failed = ref(false);
    let requestId = 0;

    const supported = computed(() => typeof props.fetchEndpoint === "function");
    const hasExecution = computed(() => !!props.execution?.id);
    const { taskRunId, taskRunState, isTerminal } = useTaskRun(props);

    async function load() {
        // Drop a response that resolves after a newer request, e.g. after a quick filter change.
        const current = ++requestId;
        loading.value = true;
        failed.value = false;
        try {
            const response = await props.fetchEndpoint?.(name, toQuery(params()), {
                taskRunId: taskRunId.value,
            });
            if (current === requestId) data.value = (response ?? null) as T | null;
        } catch {
            if (current === requestId) failed.value = true;
        } finally {
            if (current === requestId) loading.value = false;
        }
    }

    // Files such as the attempts only exist once the task run ends: reload then, and whenever a filter changes.
    watch(
        [taskRunId, taskRunState, isTerminal, () => JSON.stringify(toQuery(params()))],
        () => {
            if (supported.value && taskRunId.value) load();
        },
        { immediate: true },
    );

    // Why there is nothing to show yet, or "ready" once a response arrived (kept while a filter reloads).
    const status = computed<EndpointStatus>(() => {
        if (!supported.value) return "unsupported";
        if (!hasExecution.value) return "noExecution";
        if (!taskRunId.value) return "notRun";
        if (data.value) return "ready";
        return failed.value ? "error" : "loading";
    });

    return { data, loading, failed, status, taskRunId, reload: load };
}