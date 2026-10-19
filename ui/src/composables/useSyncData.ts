import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref, watch } from "vue";

type SlotProps = KnownSlotProps["topology-details"];

export interface StreamRow {
    name: string;
    namespace?: string;
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

// Reads this task run's outputs through the host-provided lazy fetchers. Every field is optional:
// executions that ran before an output existed, and Airbyte Cloud (no per-stream data), simply lack it.
export function useSyncData(props: SlotProps) {
    const taskId = computed(() => props.task?.id as string | undefined);
    const hasExecution = computed(() => !!props.execution?.id);
    const executionId = computed(() => props.execution?.id as string | undefined);

    const taskRun = computed(() => {
        const list = props.execution?.taskRunList as any[] | undefined;
        return list?.filter((run: any) => run.taskId === taskId.value).at(-1);
    });

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

    watch(
        executionId,
        (id) => {
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

    const streams = computed<StreamRow[]>(() => {
        const tables = (metadata.value?.destination as MetadataTable[] | undefined) ?? [];
        return tables.map((table) => ({
            name: table.name ?? "",
            namespace: table.namespace,
            rows: table.rows,
            bytes: table.bytes,
            rejected: table.recordsRejected,
        }));
    });

    return {
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