import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref, watch } from "vue";
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

// Reads this task run's outputs through the host-provided lazy fetchers. Every field is optional:
// executions that ran before an output existed, and Airbyte Cloud (no per-stream data), simply lack it.
export function useSyncData(props: SlotProps) {
    const taskId = computed(() => props.task?.id as string | undefined);
    const hasExecution = computed(() => !!props.execution?.id);
    const executionId = computed(() => props.execution?.id as string | undefined);

    const taskRun = computed(() => latestTaskRun(props.execution, taskId.value));

    const outputs = ref<Record<string, any> | null>(null);
    const metrics = ref<{ name: string; value: number }[]>([]);

    async function load() {
        try {
            outputs.value = (await props.fetchOutputs?.({ taskRunId: taskRun.value?.id })) ?? null;
        } catch {
            /* best-effort */
        }
        try {
            const response = await props.fetchMetrics?.({ taskRunId: taskRun.value?.id });
            metrics.value = (response?.results as { name: string; value: number }[]) ?? [];
        } catch {
            /* best-effort */
        }
    }

    // A card opened while the sync is still running has no outputs yet: reload once the task run ends.
    const taskRunState = computed(() => taskRun.value?.state?.current as string | undefined);
    const isTerminal = computed(() => TERMINAL_STATES.includes(taskRunState.value ?? ""));

    watch(
        [executionId, isTerminal],
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