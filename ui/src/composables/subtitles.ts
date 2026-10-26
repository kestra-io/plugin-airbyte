import { latestTaskRun } from "./useSyncData";

export interface ArtifactContext {
    task?: Record<string, any>;
    execution?: unknown;
    fetchOutputs?: (query?: { taskRunId?: string }) => Promise<Record<string, any>>;
}

const plural = (count: number, one: string, many: string) => `${count} ${count === 1 ? one : many}`;

// The Artifacts tab calls getSubtitle with the host props: any failure just leaves the line empty.
async function outputsOf({ task, execution, fetchOutputs }: ArtifactContext) {
    try {
        const taskRunId = latestTaskRun(execution, task?.id)?.id;
        return (await fetchOutputs?.({ taskRunId })) ?? undefined;
    } catch {
        return undefined;
    }
}

export async function attemptsSubtitle(context: ArtifactContext): Promise<string | undefined> {
    const job = (await outputsOf(context))?.job;
    if (typeof job?.attemptCount !== "number") return undefined;
    return `${plural(job.attemptCount, "attempt", "attempts")} · ${job.failedAttemptCount ?? 0} failed`;
}

export async function streamsSubtitle(context: ArtifactContext): Promise<string | undefined> {
    const count = (await outputsOf(context))?.connection?.streamCount;
    return typeof count === "number" ? plural(count, "stream", "streams") : undefined;
}