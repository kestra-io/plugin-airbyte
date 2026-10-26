<script lang="ts">
import { streamsSubtitle } from "../composables/subtitles";

// The line under the title in the Artifacts tab, read from the connection summary output.
export const getSubtitle = streamsSubtitle;
</script>

<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { KsInput, KsTag } from "@kestra-io/design-system";
import { MISSING, formatBytes, formatNumber } from "../composables/format";
import { isCloudTask } from "../composables/settings";
import { useEndpoint } from "../composables/useEndpoint";
import { useSyncData } from "../composables/useSyncData";
import ArtifactFrame from "./ArtifactFrame.vue";

const MAX_STREAMS = 50;

interface SnapshotStream {
    stream?: { name?: string; namespace?: string };
    config?: {
        selected?: boolean;
        syncMode?: string;
        destinationSyncMode?: string;
        cursorField?: string[];
        primaryKey?: string[][];
        destinationObjectName?: string;
    };
}

interface Snapshot {
    total: number;
    streams: SnapshotStream[];
}

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            search: "Filter by stream name",
            stream: "Stream",
            syncMode: "Sync mode",
            cursor: "Cursor",
            primaryKey: "Primary key",
            committed: "Committed",
            bytes: "Bytes",
            rejected: "Rejected",
            notSelected: "Not selected",
            showAll: "Show all {count}",
            showLess: "Show top {count}",
            empty: "No stream matches.",
        },
    },
});

const props = defineProps<KnownSlotProps["execution-artifact"]>();

const name = ref("");
// The text is applied on Enter or blur, so typing does not call the endpoint on every keystroke.
const draft = ref("");
const showAll = ref(false);

const { data, status } = useEndpoint<Snapshot>(props, "snapshot", () => ({ name: name.value }));
// Statistics of this run, by "namespace.name": Airbyte Cloud reports none, so its rows have no figures.
const { streams: stats } = useSyncData(props);
const statsByStream = computed(
    () =>
        new Map(stats.value.map((stream) => [`${stream.namespace ?? ""}.${stream.name}`, stream])),
);
const showStats = computed(() => !isCloudTask(props.task) && stats.value.length > 0);

const rows = computed(() =>
    (data.value?.streams ?? []).map((entry) => {
        const stream = entry.stream ?? {};
        const config = entry.config ?? {};
        return {
            label: stream.namespace
                ? `${stream.namespace}.${stream.name}`
                : (stream.name ?? MISSING),
            selected: config.selected !== false,
            syncMode: [config.syncMode, config.destinationSyncMode].filter(Boolean).join(" → "),
            cursor: config.cursorField?.join(".") ?? "",
            primaryKey: (config.primaryKey ?? []).map((path) => path.join(".")).join(", "),
            stats: statsByStream.value.get(`${stream.namespace ?? ""}.${stream.name}`),
        };
    }),
);
const visibleRows = computed(() => (showAll.value ? rows.value : rows.value.slice(0, MAX_STREAMS)));
</script>

<template>
    <ArtifactFrame :status="status">
        <div class="airbyte-artifact__filters">
            <KsInput
                v-model="draft"
                clearable
                size="small"
                class="airbyte-streams__search"
                :placeholder="t('search')"
                @change="name = draft"
            />
        </div>

        <p v-if="!rows.length" class="airbyte-artifact__note">{{ t("empty") }}</p>
        <div v-else class="airbyte-artifact__scroll">
            <table class="airbyte-artifact__table">
                <thead>
                    <tr>
                        <th>{{ t("stream") }}</th>
                        <th>{{ t("syncMode") }}</th>
                        <th>{{ t("cursor") }}</th>
                        <th>{{ t("primaryKey") }}</th>
                        <template v-if="showStats">
                            <th>{{ t("committed") }}</th>
                            <th>{{ t("bytes") }}</th>
                            <th>{{ t("rejected") }}</th>
                        </template>
                    </tr>
                </thead>
                <tbody>
                    <tr v-for="row in visibleRows" :key="row.label">
                        <td>
                            {{ row.label }}
                            <KsTag v-if="!row.selected" size="small" type="info">{{
                                t("notSelected")
                            }}</KsTag>
                        </td>
                        <td>{{ row.syncMode || MISSING }}</td>
                        <td>{{ row.cursor || MISSING }}</td>
                        <td>{{ row.primaryKey || MISSING }}</td>
                        <template v-if="showStats">
                            <td>{{ formatNumber(row.stats?.rows) }}</td>
                            <td>{{ formatBytes(row.stats?.bytes) }}</td>
                            <td>{{ formatNumber(row.stats?.rejected) }}</td>
                        </template>
                    </tr>
                </tbody>
            </table>
        </div>
        <button
            v-if="rows.length > MAX_STREAMS"
            type="button"
            class="airbyte-streams__toggle"
            @click="showAll = !showAll"
        >
            {{
                showAll
                    ? t("showLess", { count: MAX_STREAMS })
                    : t("showAll", { count: rows.length })
            }}
        </button>
    </ArtifactFrame>
</template>

<style scoped>
.airbyte-streams__search {
    width: 240px;
}

.airbyte-streams__toggle {
    margin-top: var(--ks-spacing-2);
    padding: 0;
    color: var(--ks-text-secondary);
    text-decoration: underline;
    background: none;
    border: none;
    cursor: pointer;
}
</style>