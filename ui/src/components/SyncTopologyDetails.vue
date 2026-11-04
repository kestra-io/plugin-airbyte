<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { KsTopologyDetails } from "@kestra-io/design-system";
import { MISSING, formatBytes, formatNumber, formatStatus } from "../composables/format";
import { headlineSettings } from "../composables/settings";
import { useSyncData } from "../composables/useSyncData";

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            route: "Source → Destination",
            streams: "Streams",
            rowsSynced: "Rows synced",
            bytesSynced: "Bytes synced",
            attempts: "Attempts",
            status: "Status",
        },
    },
});

const props = defineProps<KnownSlotProps["topology-details"]>();
const { hasExecution, job, connection, rowsSynced, bytesSynced } = useSyncData(props);

// props.task holds the unrendered task definition, so expressions are shown as written.
const task = computed(() => props.task as Record<string, any> | undefined);

const route = computed(() => {
    const source = connection.value?.sourceType;
    const destination = connection.value?.destinationType;
    return source && destination ? `${source} → ${destination}` : MISSING;
});

const rows = computed(() => {
    if (!hasExecution.value) {
        return headlineSettings(task.value).map(({ label, value }) => ({ label, value }));
    }

    return [
        { label: t("route"), value: route.value },
        { label: t("streams"), value: formatNumber(connection.value?.streamCount) },
        { label: t("rowsSynced"), value: formatNumber(rowsSynced.value) },
        { label: t("bytesSynced"), value: formatBytes(bytesSynced.value) },
        { label: t("attempts"), value: formatNumber(job.value?.attemptCount) },
        { label: t("status"), value: formatStatus(job.value?.status) },
    ];
});
</script>

<template>
    <KsTopologyDetails :rows="rows" />
</template>