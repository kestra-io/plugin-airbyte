<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { KsAlert, KsTag, KsTopologyDetails } from "@kestra-io/design-system";
import {
    MISSING,
    formatBytes,
    formatDateTime,
    formatDuration,
    formatNumber,
    formatStatus,
    safeHttpUrl,
} from "../composables/format";
import { isCloudTask, taskSettings } from "../composables/settings";
import { useSyncData, type Failure } from "../composables/useSyncData";

const MAX_STREAMS = 10;
const RUNNING_STATES = ["CREATED", "RUNNING", "QUEUED", "RESTARTED"];

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            settings: "Task settings",
            job: "Job",
            jobId: "Job ID",
            jobType: "Type",
            status: "Status",
            created: "Created",
            ended: "Ended",
            duration: "Duration",
            attempts: "Attempts",
            attemptsValue: "{count} ({failed} failed)",
            rowsSynced: "Rows synced",
            bytesSynced: "Bytes synced",
            rejected: "Records rejected",
            failures: "Failure reasons",
            streams: "Streams",
            stream: "Stream",
            emitted: "Emitted",
            committed: "Committed",
            bytes: "Bytes",
            total: "Total",
            showAll: "Show all {count}",
            showLess: "Show top {count}",
            cloudNoStreams: "Per-stream statistics aren't available from Airbyte Cloud.",
            connection: "Connection",
            name: "Name",
            schedule: "Schedule type",
            source: "Source",
            destination: "Destination",
            streamCount: "Selected streams",
            openJob: "Open the job in Airbyte",
            running: "Running",
            partialSuccess: "Partial success",
            adopted: "Adopted running sync",
            retryable: "Retryable",
            alertTitle: "Airbyte job {status} after {count} attempt(s)",
            alertTitleShort: "Airbyte job {status}",
            runningHint: "The job summary appears when the sync finishes.",
            empty: "Run the flow to see the job summary.",
        },
    },
});

const props = defineProps<KnownSlotProps["topology-task-drawer"]>();
const {
    taskRunState,
    hasExecution,
    taskOutputs,
    job,
    connection,
    failures,
    metadata,
    rowsSynced,
    bytesSynced,
    streams,
} = useSyncData(props as KnownSlotProps["topology-details"]);

const task = computed(() => props.task as Record<string, any> | undefined);
const isCloud = computed(() => isCloudTask(task.value));
const settings = computed(() => taskSettings(task.value));

const hasOutputs = computed(() => !!taskOutputs.value && !!job.value);
const running = computed(
    () =>
        hasExecution.value &&
        !hasOutputs.value &&
        RUNNING_STATES.includes(taskRunState.value ?? ""),
);

const jobStatus = computed(() => String(job.value?.status ?? "").toLowerCase());
const isFailure = computed(() => jobStatus.value === "failed" || jobStatus.value === "cancelled");
const statusType = computed(() => {
    if (jobStatus.value === "succeeded") return "success";
    if (jobStatus.value === "failed") return "danger";
    if (jobStatus.value === "cancelled") return "warning";
    return "info";
});
const adopted = computed(
    () => taskOutputs.value?.adopted === true || taskOutputs.value?.alreadyRunning === true,
);
const jobUrl = computed(() => safeHttpUrl(taskOutputs.value?.jobUrl));

const alertMessage = computed(() => failures.value[0]?.externalMessage);
const alertTitle = computed(() =>
    job.value?.attemptCount !== undefined
        ? t("alertTitle", {
              status: formatStatus(job.value?.status),
              count: job.value.attemptCount,
          })
        : t("alertTitleShort", { status: formatStatus(job.value?.status) }),
);

const jobRows = computed(() => {
    const value = job.value ?? {};
    const rows = [
        { label: t("jobId"), value: String(taskOutputs.value?.jobId ?? value.jobId ?? MISSING) },
        {
            label: t("jobType"),
            value: String(value.configType ?? value.jobType ?? MISSING).toLowerCase(),
        },
        { label: t("status"), value: formatStatus(value.status) },
        { label: t("created"), value: formatDateTime(value.createdAt ?? value.startTime) },
        { label: t("ended"), value: formatDateTime(value.endedAt ?? value.lastUpdatedAt) },
        { label: t("duration"), value: formatDuration(value.duration) },
    ];
    if (value.attemptCount !== undefined) {
        rows.push({
            label: t("attempts"),
            value: t("attemptsValue", {
                count: formatNumber(value.attemptCount),
                failed: formatNumber(value.failedAttemptCount ?? 0),
            }),
        });
    }
    rows.push(
        { label: t("rowsSynced"), value: formatNumber(rowsSynced.value) },
        { label: t("bytesSynced"), value: formatBytes(bytesSynced.value) },
    );
    if (typeof metadata.value?.recordsRejected === "number") {
        rows.push({ label: t("rejected"), value: formatNumber(metadata.value.recordsRejected) });
    }
    return rows;
});

const settingRows = computed(() =>
    settings.value.map((setting) => ({ label: setting.label, value: setting.value })),
);

const connectionRows = computed(() => {
    const pair = (name?: string, type?: string) =>
        [name, type].filter(Boolean).join(" · ") || MISSING;
    return [
        { label: t("name"), value: connection.value?.name ?? MISSING },
        { label: t("status"), value: connection.value?.status ?? MISSING },
        { label: t("schedule"), value: connection.value?.scheduleType ?? MISSING },
        {
            label: t("source"),
            value: pair(connection.value?.sourceName, connection.value?.sourceType),
        },
        {
            label: t("destination"),
            value: pair(connection.value?.destinationName, connection.value?.destinationType),
        },
        { label: t("streamCount"), value: formatNumber(connection.value?.streamCount) },
    ];
});

const showAll = ref(false);
const visibleStreams = computed(() =>
    showAll.value ? streams.value : streams.value.slice(0, MAX_STREAMS),
);
const sum = (pick: (stream: (typeof streams.value)[number]) => number | undefined) =>
    streams.value.some((stream) => typeof pick(stream) === "number")
        ? streams.value.reduce((total, stream) => total + (pick(stream) ?? 0), 0)
        : undefined;
const totals = computed(() => ({
    emitted: sum((stream) => stream.emitted),
    rows: sum((stream) => stream.rows),
    bytes: sum((stream) => stream.bytes),
    rejected: sum((stream) => stream.rejected),
}));
const maxRows = computed(() => Math.max(1, ...streams.value.map((stream) => stream.rows ?? 0)));
const barWidth = (rows?: number) => `${Math.round(((rows ?? 0) / maxRows.value) * 100)}%`;
const streamLabel = (stream: { name: string; namespace?: string }) =>
    stream.namespace ? `${stream.namespace}.${stream.name}` : stream.name;

// Airbyte retries repeat the same failure on every attempt: show each distinct one once, with a count.
const groupedFailures = computed(() => {
    const groups = new Map<string, { failure: Failure & Record<string, any>; count: number }>();
    for (const failure of failures.value as (Failure & Record<string, any>)[]) {
        const key = JSON.stringify([failure.origin, failure.type, failure.externalMessage]);
        const group = groups.get(key);
        if (group) group.count++;
        else groups.set(key, { failure, count: 1 });
    }
    return [...groups.values()];
});
</script>

<template>
    <div class="airbyte-drawer">
        <div v-if="hasOutputs || running" class="airbyte-header">
            <KsTag v-if="running" size="small" type="info">{{ t("running") }}</KsTag>
            <KsTag v-else size="small" :type="statusType">{{ formatStatus(job?.status) }}</KsTag>
            <KsTag v-if="job?.partialSuccess" size="small" type="warning">
                {{ t("partialSuccess") }}
            </KsTag>
            <KsTag v-if="adopted" size="small" type="info">{{ t("adopted") }}</KsTag>
            <a v-if="jobUrl" :href="jobUrl" target="_blank" rel="noopener noreferrer">{{
                t("openJob")
            }}</a>
        </div>

        <KsAlert
            v-if="hasOutputs && isFailure"
            type="error"
            :title="alertTitle"
            :description="alertMessage"
            :closable="false"
            show-icon
            class="airbyte-alert"
        />

        <details v-if="settingRows.length" class="airbyte-section" :open="!hasOutputs">
            <summary class="airbyte-section__title">{{ t("settings") }}</summary>
            <KsTopologyDetails :rows="settingRows" />
        </details>

        <p v-if="!hasExecution" class="airbyte-note">{{ t("empty") }}</p>
        <p v-else-if="running" class="airbyte-note">{{ t("runningHint") }}</p>

        <template v-if="hasOutputs">
            <details class="airbyte-section" open>
                <summary class="airbyte-section__title">{{ t("job") }}</summary>
                <KsTopologyDetails :rows="jobRows" />
            </details>

            <details v-if="groupedFailures.length" class="airbyte-section" open>
                <summary class="airbyte-section__title">
                    {{ t("failures") }} ({{ groupedFailures.length }})
                </summary>
                <ul class="airbyte-failures">
                    <li v-for="(group, index) in groupedFailures" :key="index">
                        <div class="airbyte-tags">
                            <KsTag v-if="group.failure.origin" size="small" type="info">{{
                                group.failure.origin
                            }}</KsTag>
                            <KsTag v-if="group.failure.type" size="small" type="danger">{{
                                group.failure.type
                            }}</KsTag>
                            <KsTag v-if="group.failure.retryable" size="small" type="warning">{{
                                t("retryable")
                            }}</KsTag>
                            <span v-if="group.count > 1" class="airbyte-count"
                                >×{{ group.count }}</span
                            >
                            <span v-if="group.failure.stream" class="airbyte-muted">{{
                                group.failure.stream
                            }}</span>
                        </div>
                        <div>{{ group.failure.externalMessage }}</div>
                    </li>
                </ul>
            </details>

            <details v-if="streams.length" class="airbyte-section" open>
                <summary class="airbyte-section__title">
                    {{ t("streams") }} ({{ streams.length }})
                </summary>
                <table class="airbyte-streams">
                    <thead>
                        <tr>
                            <th>{{ t("stream") }}</th>
                            <th>{{ t("emitted") }}</th>
                            <th>{{ t("committed") }}</th>
                            <th>{{ t("bytes") }}</th>
                            <th>{{ t("rejected") }}</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr v-for="stream in visibleStreams" :key="streamLabel(stream)">
                            <td>
                                {{ streamLabel(stream) }}
                                <div class="airbyte-bar">
                                    <div
                                        class="airbyte-bar__fill"
                                        :style="{ width: barWidth(stream.rows) }"
                                    />
                                </div>
                            </td>
                            <td>{{ formatNumber(stream.emitted) }}</td>
                            <td>{{ formatNumber(stream.rows) }}</td>
                            <td>{{ formatBytes(stream.bytes) }}</td>
                            <td>{{ formatNumber(stream.rejected) }}</td>
                        </tr>
                    </tbody>
                    <tfoot>
                        <tr>
                            <th>{{ t("total") }}</th>
                            <th>{{ formatNumber(totals.emitted) }}</th>
                            <th>{{ formatNumber(totals.rows) }}</th>
                            <th>{{ formatBytes(totals.bytes) }}</th>
                            <th>{{ formatNumber(totals.rejected) }}</th>
                        </tr>
                    </tfoot>
                </table>
                <button
                    v-if="streams.length > MAX_STREAMS"
                    type="button"
                    class="airbyte-toggle"
                    @click="showAll = !showAll"
                >
                    {{
                        showAll
                            ? t("showLess", { count: MAX_STREAMS })
                            : t("showAll", { count: streams.length })
                    }}
                </button>
            </details>
            <p v-else-if="isCloud" class="airbyte-note">{{ t("cloudNoStreams") }}</p>

            <details v-if="connection" class="airbyte-section">
                <summary class="airbyte-section__title">{{ t("connection") }}</summary>
                <KsTopologyDetails :rows="connectionRows" />
            </details>
        </template>
    </div>
</template>

<style scoped>
.airbyte-drawer {
    font-size: var(--ks-font-size-xs);
}

.airbyte-header {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--ks-spacing-2);
    margin: var(--ks-spacing-3) var(--ks-spacing-3) 0;
}

.airbyte-header a {
    margin-left: auto;
}

.airbyte-alert {
    margin: var(--ks-spacing-3) var(--ks-spacing-3) 0;
    width: auto;
}

.airbyte-section {
    margin: var(--ks-spacing-3) var(--ks-spacing-3) 0;
    padding-top: var(--ks-spacing-2);
    border-top: 1px solid var(--ks-border-primary);
}

.airbyte-section__title {
    display: flex;
    align-items: center;
    gap: var(--ks-spacing-1);
    margin-bottom: var(--ks-spacing-2);
    font-weight: 600;
    color: var(--ks-text-primary, inherit);
    cursor: pointer;
    list-style: none;
    user-select: none;
}

.airbyte-section__title::-webkit-details-marker {
    display: none;
}

.airbyte-section__title::before {
    content: "▶";
    font-size: 0.5rem;
    transition: transform 0.15s;
}

details[open] > .airbyte-section__title::before {
    transform: rotate(90deg);
}

.airbyte-note {
    margin: var(--ks-spacing-3);
    color: var(--ks-text-secondary);
}

.airbyte-tags {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--ks-spacing-1);
}

.airbyte-count {
    font-weight: 600;
}

.airbyte-muted {
    color: var(--ks-text-secondary);
}

.airbyte-failures {
    display: flex;
    flex-direction: column;
    gap: var(--ks-spacing-2);
    margin: 0;
    padding: 0;
    list-style: none;
}

.airbyte-streams {
    width: 100%;
    border-collapse: collapse;
}

.airbyte-streams th,
.airbyte-streams td {
    padding: var(--ks-spacing-1) var(--ks-spacing-2);
    text-align: left;
    border-bottom: 1px solid var(--ks-border-primary);
}

.airbyte-streams tfoot th {
    border-top: 1px solid var(--ks-border-primary);
    border-bottom: none;
}

.airbyte-bar {
    height: 4px;
    margin-top: 2px;
    background: var(--ks-border-primary);
    border-radius: 2px;
}

.airbyte-bar__fill {
    height: 100%;
    background: var(--ks-text-secondary);
    border-radius: 2px;
}

.airbyte-toggle {
    margin-top: var(--ks-spacing-2);
    padding: 0;
    color: var(--ks-text-secondary);
    text-decoration: underline;
    background: none;
    border: none;
    cursor: pointer;
}
</style>