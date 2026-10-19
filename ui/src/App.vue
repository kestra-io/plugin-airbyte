<script setup lang="ts">
import SyncTopologyDetails from "./components/SyncTopologyDetails.vue";
import { SLOTS } from "@kestra-io/artifact-sdk";
</script>

<template>
    <div style="padding: 1rem; font-family: sans-serif">
        <SyncTopologyDetails v-bind="SLOTS['topology-details'].defaultProps" />
    </div>
</template>