<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { KsTopologyDetails } from "@kestra-io/design-system";
import {
    MISSING,
    formatBytes,
    formatDuration,
    formatNumber,
    formatStatus,
    safeHttpUrl,
} from "../composables/format";
import { useSyncData, type Failure } from "../composables/useSyncData";

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            jobSummary: "Job",
            status: "Status",
            duration: "Duration",
            attempts: "Attempts",
            failedAttempts: "Failed attempts",
            partialSuccess: "Partial success",
            rowsSynced: "Rows synced",
            bytesSynced: "Bytes synced",
            failures: "Failure reasons",
            streams: "Streams",
            stream: "Stream",
            rows: "Rows",
            bytes: "Bytes",
            rejected: "Rejected",
            connection: "Connection",
            name: "Name",
            source: "Source",
            destination: "Destination",
            openJob: "Open the job in Airbyte",
            empty: "Run the flow to see the job summary.",
            yes: "Yes",
            no: "No",
        },
    },
});

const props = defineProps<KnownSlotProps["topology-task-drawer"]>();
const { hasExecution, taskOutputs, job, connection, failures, rowsSynced, bytesSynced, streams } =
    useSyncData(props as KnownSlotProps["topology-details"]);

const jobRows = computed(() => {
    const rows = [
        { label: t("status"), value: formatStatus(job.value?.status) },
        { label: t("duration"), value: formatDuration(job.value?.duration) },
        { label: t("rowsSynced"), value: formatNumber(rowsSynced.value) },
        { label: t("bytesSynced"), value: formatBytes(bytesSynced.value) },
    ];
    if (job.value?.attemptCount !== undefined) {
        rows.push(
            { label: t("attempts"), value: formatNumber(job.value.attemptCount) },
            { label: t("failedAttempts"), value: formatNumber(job.value.failedAttemptCount) },
            { label: t("partialSuccess"), value: job.value.partialSuccess ? t("yes") : t("no") },
        );
    }
    return rows;
});

const connectionRows = computed(() => [
    { label: t("name"), value: connection.value?.name ?? MISSING },
    {
        label: t("source"),
        value:
            [connection.value?.sourceName, connection.value?.sourceType]
                .filter(Boolean)
                .join(" · ") || MISSING,
    },
    {
        label: t("destination"),
        value:
            [connection.value?.destinationName, connection.value?.destinationType]
                .filter(Boolean)
                .join(" · ") || MISSING,
    },
]);

const jobUrl = computed(() => safeHttpUrl(taskOutputs.value?.jobUrl));
const maxRows = computed(() => Math.max(1, ...streams.value.map((stream) => stream.rows ?? 0)));
const barWidth = (rows?: number) => `${Math.round(((rows ?? 0) / maxRows.value) * 100)}%`;
const streamLabel = (stream: { name: string; namespace?: string }) =>
    stream.namespace ? `${stream.namespace}.${stream.name}` : stream.name;
// Airbyte retries repeat the same failure on every attempt: show each distinct one once, with a count.
const groupedFailures = computed(() => {
    const groups = new Map<string, { failure: Failure; count: number }>();
    for (const failure of failures.value) {
        const key = JSON.stringify([failure.origin, failure.type, failure.externalMessage]);
        const group = groups.get(key);
        if (group) group.count++;
        else groups.set(key, { failure, count: 1 });
    }
    return [...groups.values()];
});
const failureLabel = (failure: { origin?: string; type?: string }) =>
    [failure.origin, failure.type].filter(Boolean).join(" / ");
</script>

<template>
    <div class="airbyte-drawer">
        <p v-if="!hasExecution" class="airbyte-section airbyte-empty">{{ t("empty") }}</p>
        <template v-else-if="taskOutputs">
            <section class="airbyte-section">
                <h4 class="airbyte-section__title">{{ t("jobSummary") }}</h4>
                <KsTopologyDetails :rows="jobRows" />
            </section>

            <section v-if="groupedFailures.length" class="airbyte-section">
                <h4 class="airbyte-section__title">{{ t("failures") }}</h4>
                <ul class="airbyte-failures">
                    <li v-for="(group, index) in groupedFailures" :key="index">
                        <strong>{{ failureLabel(group.failure) }}</strong>
                        <span v-if="group.count > 1"> ×{{ group.count }}</span>
                        <span v-if="group.failure.stream"> ({{ group.failure.stream }})</span>
                        <div>{{ group.failure.externalMessage }}</div>
                    </li>
                </ul>
            </section>

            <section v-if="streams.length" class="airbyte-section">
                <h4 class="airbyte-section__title">{{ t("streams") }}</h4>
                <table class="airbyte-streams">
                    <thead>
                        <tr>
                            <th>{{ t("stream") }}</th>
                            <th>{{ t("rows") }}</th>
                            <th>{{ t("bytes") }}</th>
                            <th>{{ t("rejected") }}</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr v-for="stream in streams" :key="streamLabel(stream)">
                            <td>
                                {{ streamLabel(stream) }}
                                <div class="airbyte-bar">
                                    <div
                                        class="airbyte-bar__fill"
                                        :style="{ width: barWidth(stream.rows) }"
                                    />
                                </div>
                            </td>
                            <td>{{ formatNumber(stream.rows) }}</td>
                            <td>{{ formatBytes(stream.bytes) }}</td>
                            <td>{{ formatNumber(stream.rejected) }}</td>
                        </tr>
                    </tbody>
                </table>
            </section>

            <section v-if="connection" class="airbyte-section">
                <h4 class="airbyte-section__title">{{ t("connection") }}</h4>
                <KsTopologyDetails :rows="connectionRows" />
            </section>

            <section v-if="jobUrl" class="airbyte-section">
                <a :href="jobUrl" target="_blank" rel="noopener noreferrer">{{ t("openJob") }}</a>
            </section>
        </template>
    </div>
</template>

<style scoped>
.airbyte-section {
    margin: var(--ks-spacing-3) var(--ks-spacing-3) 0;
}

.airbyte-section__title {
    margin: 0 0 var(--ks-spacing-2);
    font-size: var(--ks-font-size-xs);
    font-weight: 600;
    color: var(--ks-text-secondary);
}

.airbyte-empty {
    font-size: var(--ks-font-size-xs);
    color: var(--ks-text-secondary);
}

.airbyte-failures {
    margin: 0;
    padding-left: var(--ks-spacing-4);
    font-size: var(--ks-font-size-xs);
}

.airbyte-streams {
    width: 100%;
    border-collapse: collapse;
    font-size: var(--ks-font-size-xs);
}

.airbyte-streams th,
.airbyte-streams td {
    padding: var(--ks-spacing-1) var(--ks-spacing-2);
    text-align: left;
    border-bottom: 1px solid var(--ks-border-primary);
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
</style>