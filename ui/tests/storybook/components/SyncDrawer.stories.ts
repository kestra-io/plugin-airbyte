import type { Meta, StoryObj } from "@storybook/vue3";
import { setup } from "@storybook/vue3";
import { within, expect, waitFor } from "storybook/test";
import { createI18n } from "vue-i18n";
import KestraDesignSystem from "@kestra-io/design-system";
import SyncDrawer from "../../../src/components/SyncDrawer.vue";
import {
    cloudOutputs,
    cloudTask,
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

const meta: Meta<typeof SyncDrawer> = {
    title: "Plugin UI / topology-task-drawer / SyncDrawer",
    component: SyncDrawer,
    tags: ["autodocs"],
};

export default meta;
type Story = StoryObj<typeof SyncDrawer>;

const flow = { namespace: "company.team", flowId: "airbyte-sync", displayMode: "full" };

export const Succeeded: Story = {
    name: "Succeeded with 3 streams",
    args: { task, ...flow, ...fetchers(succeededOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("public.orders")).toBeInTheDocument());
        expect(canvas.getByText("public.customers")).toBeInTheDocument();
        expect(canvas.getByText("public.refunds")).toBeInTheDocument();
        expect(canvas.getByText("3 min 15 s")).toBeInTheDocument();
        expect(canvas.getByText("Open the job in Airbyte").getAttribute("href")).toContain(
            "jobId=71",
        );
        expect(canvas.queryByText("Failure reasons")).toBeNull();
    },
};

export const Failed: Story = {
    name: "Failed with failure reasons",
    args: { task, ...flow, ...fetchers(failedOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Failure reasons")).toBeInTheDocument());
        expect(
            canvas.getByText("Could not connect to the source: password authentication failed"),
        ).toBeInTheDocument();
        expect(canvas.getByText("SOURCE / CONFIG_ERROR")).toBeInTheDocument();
        expect(canvas.queryByText("Streams")).toBeNull();
    },
};

export const PartialSuccessWarning: Story = {
    name: "onPartialSuccess WARNING",
    args: { task, ...flow, ...fetchers(warningOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Partial success")).toBeInTheDocument());
        expect(canvas.getByText("Yes")).toBeInTheDocument();
        expect(
            canvas.getByText("Destination timed out after committing 12,500 records"),
        ).toBeInTheDocument();
    },
};

export const Cloud: Story = {
    name: "Airbyte Cloud job totals",
    args: { task: cloudTask, ...flow, ...fetchers(cloudOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("20,000")).toBeInTheDocument());
        expect(canvas.queryByText("Streams")).toBeNull();
        expect(canvas.queryByText("Attempts")).toBeNull();
    },
};

export const UnsafeJobLink: Story = {
    name: "Non-http job link is not rendered",
    args: {
        task,
        ...flow,
        ...fetchers({ ...succeededOutputs, jobUrl: "javascript:alert(1)" }),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("public.orders")).toBeInTheDocument());
        expect(canvas.queryByText("Open the job in Airbyte")).toBeNull();
    },
};