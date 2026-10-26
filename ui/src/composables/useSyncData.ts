import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, onScopeDispose, ref, watch } from "vue";
import { TERMINAL_STATES } from "./states";

// The Artifacts slot has the same props minus `progress`, which is unused here, so both slots fit.
type SlotProps = KnownSlotProps["execution-artifact"];

export interface StreamRow {
    name: string;
    namespace?: string;
    emitted?: number;
    rows?: number;
    bytes?: number;
    rejected?: number;
}

interface MetadataTable {
    name?: string;
    namespace?: string;
    rows?: number;
    bytes?: number;
    recordsRejected?: number;
}

export interface Failure {
    origin?: string;
    type?: string;
    externalMessage?: string;
    stream?: string;
}

// The sync's own task run: `attempt-N` sub task runs carry a different taskId and have no outputs.
export function latestTaskRun(execution: unknown, taskId: unknown) {
    const list = (execution as { taskRunList?: any[] } | undefined)?.taskRunList;
    return list?.filter((run: any) => run.taskId === taskId).at(-1);
}

const POLL_MS = 2000;
// The host hands the plugin a snapshot of the execution taken when the view mounted, so a view opened on a
// running sync never sees it end. Its outputs only exist once the task finishes: ask for them until they do.
const DETECT_MS = 4000;
const DETECT_MAX_MS = 15 * 60 * 1000;
// Pauses before asking again when a finished run answers with something that is not its outputs.
const RETRY_MS = [300, 700];

export type LoadState = "idle" | "loading" | "loaded" | "unsupported" | "unusable" | "error";

const OUTPUT_KEYS = ["job", "metadata", "connection", "jobId", "jobUrl", "alreadyRunning"];

// A task run's outputs are an object keyed by output name. The host also serves, one level up, the list of
// task runs that have outputs (`[{ taskId, taskRunId, inline }]`): that must never be read as outputs.
export function isSyncOutputs(value: unknown): value is Record<string, any> {
    return (
        !!value &&
        typeof value === "object" &&
        !Array.isArray(value) &&
        OUTPUT_KEYS.some((key) => key in value)
    );
}

// The sync's task run, kept fresh while it has not ended. A host can update the execution in place, or from
// its own Vue runtime, without our computeds noticing: while the run is not terminal, a timer re-reads the
// props and bumps `tick`, so the state change still reaches every watcher.
export function useTaskRun(props: SlotProps) {
    const tick = ref(0);
    const read = () => {
        void tick.value;
        return latestTaskRun(props.execution, props.task?.id);
    };
    // Each one re-reads the task run itself: a computed that returned the same (mutated) object would be cached.
    const taskRun = computed(read);
    const taskRunId = computed(() => read()?.id as string | undefined);
    const taskRunState = computed(() => read()?.state?.current as string | undefined);
    // Set when the outputs show up although the (possibly frozen) props still say the run is going.
    const finished = ref(false);
    const isTerminal = computed(
        () => finished.value || TERMINAL_STATES.includes(taskRunState.value ?? ""),
    );

    const timer = setInterval(() => {
        if (isTerminal.value) return;
        tick.value++;
    }, POLL_MS);

    const startedAt = Date.now();
    let asking = false;
    const detector = setInterval(async () => {
        if (isTerminal.value || Date.now() - startedAt > DETECT_MAX_MS) {
            clearInterval(detector);
            return;
        }
        const runId = read()?.id as string | undefined;
        if (asking || !runId || typeof props.fetchOutputs !== "function") return;
        asking = true;
        try {
            if (isSyncOutputs(await props.fetchOutputs({ taskRunId: runId })))
                finished.value = true;
        } catch {
            /* best-effort: try again on the next tick */
        } finally {
            asking = false;
        }
    }, DETECT_MS);

    onScopeDispose(() => {
        clearInterval(timer);
        clearInterval(detector);
    });

    return { taskRun, taskRunId, taskRunState, isTerminal };
}

// Reads this task run's outputs through the host-provided lazy fetchers. Every field is optional:
// executions that ran before an output existed, and Airbyte Cloud (no per-stream data), simply lack it.
export function useSyncData(props: SlotProps) {
    const hasExecution = computed(() => !!props.execution?.id);
    const executionId = computed(() => props.execution?.id as string | undefined);

    const { taskRun, taskRunId, taskRunState, isTerminal } = useTaskRun(props);

    const outputs = ref<Record<string, any> | null>(null);
    const metrics = ref<{ name: string; value: number }[]>([]);

    // Why there are no outputs: the host has no fetchOutputs, it answered with something that is not this task
    // run's outputs (the execution-level list of task runs, an empty object), or the call failed.
    const loadState = ref<LoadState>("idle");

    // The reload at the end of the run can overlap the first load: only the latest request may write.
    let requestId = 0;
    async function load(attempt = 0) {
        const current = ++requestId;
        const runId = taskRun.value?.id;
        // Without a task run the host would have to guess which one to read: wait until the execution has it.
        if (!runId) return;
        if (typeof props.fetchOutputs !== "function") {
            loadState.value = "unsupported";
            return;
        }

        loadState.value = "loading";
        try {
            const response = await props.fetchOutputs({ taskRunId: runId });
            if (current !== requestId) return;
            if (isSyncOutputs(response)) {
                outputs.value = response;
                loadState.value = "loaded";
            } else if (isTerminal.value && attempt < RETRY_MS.length) {
                setTimeout(() => current === requestId && load(attempt + 1), RETRY_MS[attempt]);
                return;
            } else if (!isTerminal.value) {
                // A running sync has no outputs yet: this is the normal answer, reloaded when it ends.
                loadState.value = "idle";
            } else {
                loadState.value = "unusable";
                console.warn("[plugin-airbyte] Unusable task run outputs", {
                    taskRunId: runId,
                    response,
                });
            }
        } catch (error) {
            if (current === requestId) loadState.value = "error";
            console.warn("[plugin-airbyte] Could not load the task run outputs", error);
        }
        try {
            const response = await props.fetchMetrics?.({ taskRunId: runId });
            if (current === requestId)
                metrics.value = (response?.results as { name: string; value: number }[]) ?? [];
        } catch {
            /* best-effort */
        }
    }

    // A card opened while the sync is still running has no outputs yet: reload once the task run ends.

    watch(
        [executionId, taskRunId, isTerminal],
        ([id]) => {
            if (id) load();
        },
        { immediate: true },
    );

    const taskOutputs = computed(() => outputs.value ?? taskRun.value?.outputs ?? null);
    const job = computed(() => taskOutputs.value?.job as Record<string, any> | undefined);
    const metadata = computed(() => taskOutputs.value?.metadata as Record<string, any> | undefined);
    const connection = computed(
        () => taskOutputs.value?.connection as Record<string, any> | undefined,
    );
    const failures = computed(() => (job.value?.failures as Failure[] | undefined) ?? []);

    // Self-hosted reports per-stream rows in metadata; Airbyte Cloud only has job totals.
    const rowsSynced = computed<number | undefined>(
        () => metadata.value?.rowsSynced ?? job.value?.rowsSynced,
    );
    const bytesSynced = computed<number | undefined>(
        () => metadata.value?.bytesSynced ?? job.value?.bytesSynced,
    );

    // Destination tables carry the committed rows; the matching source table carries the emitted rows.
    const streams = computed<StreamRow[]>(() => {
        const tables = (metadata.value?.destination as MetadataTable[] | undefined) ?? [];
        const sources = (metadata.value?.source as MetadataTable[] | undefined) ?? [];
        const emitted = new Map(sources.map((s) => [`${s.namespace ?? ""}.${s.name}`, s.rows]));
        return tables
            .map((table) => ({
                name: table.name ?? "",
                namespace: table.namespace,
                emitted: emitted.get(`${table.namespace ?? ""}.${table.name}`),
                rows: table.rows,
                bytes: table.bytes,
                rejected: table.recordsRejected,
            }))
            .sort((a, b) => (b.rows ?? -1) - (a.rows ?? -1));
    });

    return {
        taskRunState,
        isTerminal,
        loadState,
        hasExecution,
        taskOutputs,
        metrics,
        job,
        metadata,
        connection,
        failures,
        rowsSynced,
        bytesSynced,
        streams,
    };
}