import type { Meta, StoryObj } from "@storybook/vue3";
import { setup } from "@storybook/vue3";
import { within, expect, waitFor } from "storybook/test";
import { createI18n } from "vue-i18n";
import KestraDesignSystem from "@kestra-io/design-system";
import SyncTopologyDetails from "../../../src/components/SyncTopologyDetails.vue";
import {
    cloudOutputs,
    cloudTask,
    execution,
    failedOutputs,
    fetchers,
    succeededOutputs,
    task,
    warningOutputs,
} from "../fixtures";

const i18n = createI18n({ legacy: false, locale: "en", messages: { en: {} } });
setup((app) => {
    app.use(i18n);
    app.use(KestraDesignSystem);
});

const meta: Meta<typeof SyncTopologyDetails> = {
    title: "Plugin UI / topology-details / SyncTopologyDetails",
    component: SyncTopologyDetails,
    tags: ["autodocs"],
};

export default meta;
type Story = StoryObj<typeof SyncTopologyDetails>;

const flow = { namespace: "company.team", flowId: "airbyte-sync" };

export const NoExecution: Story = {
    name: "No execution",
    args: { task, ...flow } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("http://localhost:8080")).toBeInTheDocument());
        expect(canvas.getByText("e3b1ce92-547c-436f-b1e8-23b6936c12cd")).toBeInTheDocument();
        expect(canvas.queryByText("Rows synced")).toBeNull();
    },
};

export const Running: Story = {
    name: "Running",
    args: {
        task,
        ...flow,
        execution,
        fetchOutputs: async () => ({}),
        fetchMetrics: async () => ({ results: [] }),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Rows synced")).toBeInTheDocument());
        expect(canvas.queryByText("SUCCEEDED")).toBeNull();
    },
};

export const Succeeded: Story = {
    name: "Succeeded with 3 streams",
    args: { task, ...flow, ...fetchers(succeededOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Postgres → Snowflake")).toBeInTheDocument());
        expect(canvas.getByText("60,800")).toBeInTheDocument();
        expect(canvas.getByText("22.21 MB")).toBeInTheDocument();
        expect(canvas.getByText("SUCCEEDED")).toBeInTheDocument();
    },
};

export const Failed: Story = {
    name: "Failed with failure reasons",
    args: { task, ...flow, ...fetchers(failedOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("FAILED")).toBeInTheDocument());
        expect(canvas.getAllByText("3").length).toBe(2);
    },
};

export const PartialSuccessWarning: Story = {
    name: "onPartialSuccess WARNING",
    args: {
        task: { ...task, onPartialSuccess: "WARNING" },
        ...flow,
        ...fetchers(warningOutputs),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("12,500")).toBeInTheDocument());
        expect(canvas.getByText("FAILED")).toBeInTheDocument();
    },
};

export const Cloud: Story = {
    name: "Airbyte Cloud job",
    args: { task: cloudTask, ...flow, ...fetchers(cloudOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("20,000")).toBeInTheDocument());
        expect(canvas.getByText("SUCCEEDED")).toBeInTheDocument();
    },
};