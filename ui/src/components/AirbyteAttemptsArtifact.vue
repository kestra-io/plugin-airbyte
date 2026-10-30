<script lang="ts">
import { attemptsSubtitle } from "../composables/subtitles";

// The line under the title in the Artifacts tab, read from the job summary outputs.
export const getSubtitle = attemptsSubtitle;
</script>

<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { KsPagination, KsTag } from "@kestra-io/design-system";
import { formatBytes, formatDateTime, formatDuration, formatNumber } from "../composables/format";
import { useEndpoint } from "../composables/useEndpoint";
import ArtifactFrame from "./ArtifactFrame.vue";

const PAGE_SIZE = 50;

interface AttemptRow {
    attemptNumber?: number;
    status?: string;
    createdAt?: string | number;
    endedAt?: string | number;
    bytesSynced?: number;
    recordsSynced?: number;
    partialSuccess?: boolean;
    failures?: { origin?: string; type?: string; externalMessage?: string; stream?: string }[];
}

interface AttemptsPage {
    page: number;
    size: number;
    total: number;
    results: AttemptRow[];
}

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            attempt: "Attempt",
            status: "Status",
            started: "Started",
            duration: "Duration",
            records: "Records",
            bytes: "Bytes",
            failures: "Failures",
            partialSuccess: "Partial success",
            empty: "No attempt was recorded for this run.",
        },
    },
});

const props = defineProps<KnownSlotProps["execution-artifact"]>();

const page = ref(1);
const { data, status } = useEndpoint<AttemptsPage>(props, "attempts", () => ({
    page: page.value,
    size: PAGE_SIZE,
}));
const attempts = computed(() => data.value?.results ?? []);

const statusType = (value?: string) =>
    value === "succeeded" ? "success" : value === "failed" ? "danger" : "info";

// Instants may arrive as ISO strings or epoch numbers, as in formatDateTime.
const seconds = (value?: string | number) =>
    typeof value === "number"
        ? value < 1e11
            ? value
            : value / 1000
        : Date.parse(value ?? "") / 1000;
const durationOf = (attempt: AttemptRow) => {
    if (attempt.createdAt === undefined || attempt.endedAt === undefined) return undefined;
    const elapsed = seconds(attempt.endedAt) - seconds(attempt.createdAt);
    return Number.isFinite(elapsed) && elapsed >= 0 ? elapsed : undefined;
};
</script>

<template>
    <ArtifactFrame :status="status">
        <p v-if="!attempts.length" class="airbyte-artifact__note">{{ t("empty") }}</p>
        <div v-else class="airbyte-artifact__scroll">
            <table class="airbyte-artifact__table">
                <thead>
                    <tr>
                        <th>{{ t("attempt") }}</th>
                        <th>{{ t("status") }}</th>
                        <th>{{ t("started") }}</th>
                        <th>{{ t("duration") }}</th>
                        <th>{{ t("records") }}</th>
                        <th>{{ t("bytes") }}</th>
                        <th>{{ t("failures") }}</th>
                    </tr>
                </thead>
                <tbody>
                    <tr v-for="(attempt, index) in attempts" :key="attempt.attemptNumber ?? index">
                        <td>{{ (attempt.attemptNumber ?? index) + 1 }}</td>
                        <td>
                            <KsTag size="small" :type="statusType(attempt.status)">{{
                                (attempt.status ?? "").toUpperCase()
                            }}</KsTag>
                            <KsTag v-if="attempt.partialSuccess" size="small" type="warning">{{
                                t("partialSuccess")
                            }}</KsTag>
                        </td>
                        <td>{{ formatDateTime(attempt.createdAt) }}</td>
                        <td>{{ formatDuration(durationOf(attempt)) }}</td>
                        <td>{{ formatNumber(attempt.recordsSynced) }}</td>
                        <td>{{ formatBytes(attempt.bytesSynced) }}</td>
                        <td>
                            <div v-for="(failure, number) in attempt.failures" :key="number">
                                <span v-if="failure.origin" class="airbyte-artifact__muted"
                                    >{{ failure.origin }}
                                </span>
                                {{ failure.externalMessage }}
                            </div>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>

        <div class="airbyte-artifact__pager">
            <KsPagination
                v-model:current-page="page"
                :page-size="PAGE_SIZE"
                :total="data?.total ?? 0"
                layout="total, prev, pager, next"
                size="small"
                hide-on-single-page
            />
        </div>
    </ArtifactFrame>
</template>