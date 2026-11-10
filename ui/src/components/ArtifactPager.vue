<script setup lang="ts">
import { computed } from "vue";
import { KsPagination } from "@kestra-io/design-system";
import { pagerTotal, totalLabel, type PageEnvelope } from "../composables/pagination";

const props = defineProps<{ data?: PageEnvelope | null; pageSize: number }>();
const page = defineModel<number>("page", { required: true });

const total = computed(() => pagerTotal(props.data));
const capped = computed(() => props.data?.totalCapped === true);
</script>

<template>
    <div class="airbyte-artifact__pager">
        <span v-if="capped" class="airbyte-artifact__muted airbyte-artifact__total">{{
            totalLabel(data)
        }}</span>
        <KsPagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            :layout="capped ? 'prev, pager, next' : 'total, prev, pager, next'"
            size="small"
            hide-on-single-page
        />
    </div>
</template>

<style scoped>
.airbyte-artifact__total {
    margin-right: 8px;
}
</style>