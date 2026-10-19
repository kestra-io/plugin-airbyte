import type { Meta, StoryObj } from "@storybook/vue3";
import { setup } from "@storybook/vue3";
import { within, expect, waitFor, userEvent } from "storybook/test";
import { createI18n } from "vue-i18n";
import KestraDesignSystem from "@kestra-io/design-system";
import SyncDrawer from "../../../src/components/SyncDrawer.vue";
import {
    adoptedOutputs,
    cloudOutputs,
    cloudTask,
    configuredTask,
    failedOutputs,
    fetchers,
    manyStreamsOutputs,
    repeatedFailureOutputs,
    runningExecution,
    secretTask,
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

export const NoExecution: Story = {
    name: "No execution shows settings and an empty state",
    args: { task: configuredTask, ...flow } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Task settings")).toBeInTheDocument());
        expect(canvas.getByText("http://localhost:8080")).toBeInTheDocument();
        expect(canvas.getByText("On partial success")).toBeInTheDocument();
        expect(canvas.getByText("Connection ID")).toBeInTheDocument();
        expect(canvas.getByText("Poll frequency")).toBeInTheDocument();
        expect(canvas.getByText("5 s")).toBeInTheDocument();
        expect(canvas.getByText("1 h")).toBeInTheDocument();
        expect(canvas.getByText("WARNING")).toBeInTheDocument();
        expect(canvas.getByText("Automatic assets")).toBeInTheDocument();
        expect(canvas.getByText("Run the flow to see the job summary.")).toBeInTheDocument();
        expect(canvas.queryByText("Job")).toBeNull();
    },
};

export const Running: Story = {
    name: "Running",
    args: {
        task,
        ...flow,
        execution: runningExecution,
        fetchOutputs: async () => ({}),
        fetchMetrics: async () => ({ results: [] }),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Running")).toBeInTheDocument());
        expect(
            canvas.getByText("The job summary appears when the sync finishes."),
        ).toBeInTheDocument();
        expect(canvas.getByText("Task settings")).toBeInTheDocument();
        expect(canvas.queryByText("Streams (3)")).toBeNull();
    },
};

export const Succeeded: Story = {
    name: "Succeeded with 3 streams",
    args: { task, ...flow, ...fetchers(succeededOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Streams (3)")).toBeInTheDocument());
        expect(canvas.getAllByText("SUCCEEDED").length).toBe(1);
        expect(canvas.getByText("public.orders")).toBeInTheDocument();
        expect(canvas.getByText("3 min 15 s")).toBeInTheDocument();
        expect(canvas.getByText("Open the job in Airbyte").getAttribute("href")).toContain(
            "jobId=71",
        );
        // sorted by committed rows, with a totals row (emitted = committed + rejected)
        const rows = canvas.getAllByRole("row");
        expect(rows[1].textContent).toContain("public.orders");
        expect(canvas.getByText("Total")).toBeInTheDocument();
        expect(canvas.getByText("60,812")).toBeInTheDocument();
        expect(canvas.queryByText(/Failure reasons/)).toBeNull();
        expect(canvas.queryByText(/^Airbyte job/)).toBeNull();
        expect(canvas.getByText("Connection")).toBeInTheDocument();
    },
};

export const Failed: Story = {
    name: "Failed with failure reasons",
    args: { task, ...flow, ...fetchers(failedOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Failure reasons (1)")).toBeInTheDocument());
        expect(canvas.getByText("Airbyte job FAILED after 3 attempt(s)")).toBeInTheDocument();
        expect(
            canvas.getAllByText("Could not connect to the source: password authentication failed")
                .length,
        ).toBeGreaterThan(0);
        expect(canvas.getByText("SOURCE")).toBeInTheDocument();
        expect(canvas.getByText("CONFIG_ERROR")).toBeInTheDocument();
        expect(canvas.queryByText(/^Streams/)).toBeNull();
        expect(
            canvas.getByText("No per-stream statistics: no attempt succeeded."),
        ).toBeInTheDocument();
    },
};

export const RepeatedFailures: Story = {
    name: "Repeated failures are grouped",
    args: { task, ...flow, ...fetchers(repeatedFailureOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Failure reasons (2)")).toBeInTheDocument());
        expect(canvas.getByText("×4")).toBeInTheDocument();
        expect(canvas.getByText("Bad credentials")).toBeInTheDocument();
    },
};

export const PartialSuccessWarning: Story = {
    name: "Partial success",
    args: { task, ...flow, ...fetchers(warningOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Partial success")).toBeInTheDocument());
        expect(
            canvas.getAllByText("Destination timed out after committing 12,500 records").length,
        ).toBeGreaterThan(0);
        expect(canvas.getByText("Streams (1)")).toBeInTheDocument();
    },
};

export const Adopted: Story = {
    name: "Adopted running sync",
    args: { task, ...flow, ...fetchers(adoptedOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Adopted running sync")).toBeInTheDocument());
    },
};

export const Cloud: Story = {
    name: "Airbyte Cloud job (no streams)",
    args: { task: cloudTask, ...flow, ...fetchers(cloudOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("20,000")).toBeInTheDocument());
        expect(
            canvas.getByText("Per-stream statistics aren't available from Airbyte Cloud."),
        ).toBeInTheDocument();
        expect(canvas.queryByText("Attempts")).toBeNull();
        expect(canvas.queryByText("On active sync")).toBeNull();
    },
};

export const ManyStreams: Story = {
    name: "More than 10 streams",
    args: { task, ...flow, ...fetchers(manyStreamsOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Streams (12)")).toBeInTheDocument());
        expect(canvas.queryByText("public.table_1")).toBeNull();
        expect(canvas.getByText("public.table_12")).toBeInTheDocument();
        await userEvent.click(canvas.getByText("Show all 12"));
        await waitFor(() => expect(canvas.getByText("public.table_1")).toBeInTheDocument());
        expect(canvas.getByText("Show top 10")).toBeInTheDocument();
    },
};

export const CheckStatusJobId: Story = {
    name: "CheckStatus falls back to the task's job ID",
    args: {
        task: { ...task, type: "io.kestra.plugin.airbyte.connections.CheckStatus", jobId: "2" },
        ...flow,
        ...fetchers({ ...succeededOutputs, jobId: undefined }),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Streams (3)")).toBeInTheDocument());
        const outputsWithoutJobId = canvas.getAllByText("Job ID").at(-1)?.nextElementSibling;
        expect(outputsWithoutJobId?.textContent).toBe("2");
    },
};

export const NoCredentialValues: Story = {
    name: "Credential values never render",
    args: { task: secretTask, ...flow } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Task settings")).toBeInTheDocument());
        expect(canvas.getByText("Application credentials")).toBeInTheDocument();
        expect(canvasElement.textContent).not.toMatch(/SECRET|CLIENT-ID|APP-ID/);
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
        await waitFor(() => expect(canvas.getByText("Streams (3)")).toBeInTheDocument());
        expect(canvas.queryByText("Open the job in Airbyte")).toBeNull();
    },
};