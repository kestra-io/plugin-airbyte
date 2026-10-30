<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { KsInput, KsOption, KsPagination, KsSelect, KsTag } from "@kestra-io/design-system";
import { formatDateTime } from "../composables/format";
import { useEndpoint } from "../composables/useEndpoint";
import { useSyncData } from "../composables/useSyncData";
import ArtifactFrame from "./ArtifactFrame.vue";

const PAGE_SIZE = 50;
const LEVELS = ["TRACE", "DEBUG", "INFO", "WARN", "ERROR"];
// The value is what the endpoint expects, the label what Kestra logs show.
const SOURCES = [
    { value: "platform", label: "platform" },
    { value: "source", label: "source" },
    { value: "destination", label: "destination" },
    { value: "orchestrator", label: "orchestrator" },
];

interface LogLine {
    timestamp?: string | number;
    level?: string;
    message?: string;
    attempt?: number;
    source?: string;
}

interface LogsPage {
    origin: "airbyte" | "kestra";
    page: number;
    size: number;
    total: number;
    results: LogLine[];
}

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            level: "Minimum level",
            sources: "Sources",
            attempt: "Attempt",
            allAttempts: "All attempts",
            search: "Search the messages",
            attemptValue: "Attempt {number}",
            kestraOrigin:
                "Airbyte logs aren't available for this run: these are the Kestra task logs.",
            empty: "No log line matches.",
        },
    },
});

const props = defineProps<KnownSlotProps["execution-artifact"]>();

const level = ref("INFO");
const sources = ref<string[]>([]);
const attempt = ref<number | "">("");
const query = ref("");
// The text is applied on Enter or blur, so typing does not call the endpoint on every keystroke.
const draft = ref("");
const page = ref(1);

// Attempt numbers are zero-based in Airbyte: the number of attempts bounds the filter.
const { job } = useSyncData(props);
const attemptOptions = computed(() =>
    Array.from({ length: Number(job.value?.attemptCount ?? 0) }, (_, number) => number),
);

const { data, status } = useEndpoint<LogsPage>(props, "logs", () => ({
    level: level.value,
    q: query.value,
    // The endpoint ignores attempt and source when it falls back to Kestra logs, where their filters are hidden.
    attempt: attempt.value === "" ? undefined : attempt.value,
    source: sources.value,
    page: page.value,
    size: PAGE_SIZE,
}));

const isKestra = computed(() => data.value?.origin === "kestra");
const lines = computed(() => data.value?.results ?? []);

// Changing a filter goes back to the first page; the endpoint call itself reacts to the refs.
const resetPage = () => {
    page.value = 1;
};

// Airbyte messages often start with a line break that would render as an empty first line.
const trimMessage = (message?: string) => message?.replace(/^\s*\n/, "");

const levelType = (value?: string) =>
    value === "ERROR" ? "danger" : value === "WARN" ? "warning" : "info";
</script>

<template>
    <ArtifactFrame :status="status">
        <div class="airbyte-artifact__filters">
            <KsSelect
                v-model="level"
                size="small"
                class="airbyte-logs__level"
                :aria-label="t('level')"
                @change="resetPage"
            >
                <KsOption v-for="value in LEVELS" :key="value" :label="value" :value="value" />
            </KsSelect>
            <KsSelect
                v-if="!isKestra"
                v-model="sources"
                multiple
                collapse-tags
                clearable
                size="small"
                class="airbyte-logs__sources"
                :placeholder="t('sources')"
                :aria-label="t('sources')"
                @change="resetPage"
            >
                <KsOption
                    v-for="source in SOURCES"
                    :key="source.value"
                    :label="source.label"
                    :value="source.value"
                />
            </KsSelect>
            <KsSelect
                v-if="!isKestra && attemptOptions.length"
                v-model="attempt"
                clearable
                size="small"
                class="airbyte-logs__attempt"
                :placeholder="t('allAttempts')"
                :aria-label="t('attempt')"
                @change="resetPage"
            >
                <KsOption
                    v-for="number in attemptOptions"
                    :key="number"
                    :label="t('attemptValue', { number: number + 1 })"
                    :value="number"
                />
            </KsSelect>
            <KsInput
                v-model="draft"
                clearable
                size="small"
                class="airbyte-logs__search"
                :placeholder="t('search')"
                @change="
                    query = draft;
                    resetPage();
                "
            />
        </div>

        <p v-if="isKestra" class="airbyte-artifact__note airbyte-logs__origin">
            {{ t("kestraOrigin") }}
        </p>

        <p v-if="!lines.length" class="airbyte-artifact__note">{{ t("empty") }}</p>
        <div v-else class="airbyte-artifact__scroll">
            <table class="airbyte-artifact__table airbyte-logs">
                <tbody>
                    <tr v-for="(line, index) in lines" :key="index">
                        <td class="airbyte-artifact__muted airbyte-logs__time">
                            {{ formatDateTime(line.timestamp) }}
                        </td>
                        <td>
                            <KsTag size="small" :type="levelType(line.level)">{{
                                line.level
                            }}</KsTag>
                        </td>
                        <td class="airbyte-artifact__muted airbyte-logs__source">
                            <template v-if="!isKestra">
                                <span v-if="line.attempt !== undefined"
                                    >#{{ line.attempt + 1 }}</span
                                >
                                {{ line.source }}
                            </template>
                        </td>
                        <td class="airbyte-logs__message">{{ trimMessage(line.message) }}</td>
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

<style scoped>
.airbyte-logs__level {
    width: 100px;
}

.airbyte-logs__sources {
    width: 180px;
}

.airbyte-logs__attempt {
    width: 140px;
}

.airbyte-logs__search {
    width: 220px;
}

.airbyte-logs__source {
    white-space: nowrap;
}

.airbyte-logs__time {
    white-space: nowrap;
}

/* Messages can span several lines (stack trace markers, multi-line errors). */
.airbyte-logs__message {
    font-family: var(--ks-font-family-monospace, monospace);
    white-space: pre-wrap;
    word-break: break-word;
}
</style>