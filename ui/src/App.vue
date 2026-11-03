<script setup lang="ts">
import SyncDetails from "./components/SyncDetails.vue";
import { SLOTS } from "@kestra-io/artifact-sdk";
</script>

<template>
    <div style="padding: 1rem; font-family: sans-serif">
        <SyncDetails v-bind="SLOTS['execution-artifact'].defaultProps" />
    </div>
</template>