import type { Meta, StoryObj } from "@storybook/vue3";
import { setup } from "@storybook/vue3";
import { within, expect, waitFor, fireEvent } from "storybook/test";
import { createI18n } from "vue-i18n";
import KestraDesignSystem from "@kestra-io/design-system";
import AirbyteLogsArtifact from "../../../src/components/AirbyteLogsArtifact.vue";
import {
    airbyteLogs,
    endpointFetchers,
    execution,
    kestraLogs,
    succeededOutputs,
    task,
    type EndpointCall,
} from "../fixtures";

const i18n = createI18n({ legacy: false, locale: "en", messages: { en: {} } });
setup((app) => {
    app.use(i18n);
    app.use(KestraDesignSystem);
});

const meta: Meta<typeof AirbyteLogsArtifact> = {
    title: "Plugin UI / execution-artifact / AirbyteLogsArtifact",
    component: AirbyteLogsArtifact,
    tags: ["autodocs"],
};

export default meta;
type Story = StoryObj<typeof AirbyteLogsArtifact>;

const flow = { namespace: "company.team", flowId: "airbyte-sync" };

export const Airbyte: Story = {
    name: "Airbyte logs with source and attempt filters",
    args: {
        task,
        ...flow,
        ...endpointFetchers(succeededOutputs, { logs: airbyteLogs }),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() =>
            expect(canvas.getByText("Connection refused by the destination")).toBeInTheDocument(),
        );
        expect(canvas.getByText("ERROR")).toBeInTheDocument();
        expect(canvas.getByText("destination")).toBeInTheDocument();
        expect(canvas.getByText("Sources")).toBeInTheDocument();
        expect(canvas.queryByText(/Airbyte logs aren't available/)).toBeNull();
    },
};

export const KestraFallback: Story = {
    name: "Kestra logs hide the attempt and source filters",
    args: {
        task,
        ...flow,
        ...endpointFetchers(succeededOutputs, { logs: kestraLogs }),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Job 71 succeeded")).toBeInTheDocument());
        expect(canvas.getByText(/Airbyte logs aren't available/)).toBeInTheDocument();
        expect(canvas.queryByText("Sources")).toBeNull();
        expect(canvas.queryByText("All attempts")).toBeNull();
        expect(canvas.getByPlaceholderText("Search the messages")).toBeInTheDocument();
    },
};

const calls: EndpointCall[] = [];

export const SearchAndPagination: Story = {
    name: "Searching and paging call the logs endpoint on the sync task run",
    args: {
        task,
        ...flow,
        ...endpointFetchers(succeededOutputs, { logs: { ...airbyteLogs, total: 120 } }, calls),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Starting replication")).toBeInTheDocument());
        expect(calls[0]).toEqual({
            name: "logs",
            params: { level: "INFO", page: "1", size: "50" },
            // the parent sync task run, never an attempt-N sub task run
            query: { taskRunId: "tr-001" },
        });

        const search = canvas.getByPlaceholderText("Search the messages");
        // Typing is applied on change (Enter or blur), not on every keystroke.
        await fireEvent.input(search, { target: { value: "refused" } });
        await fireEvent.change(search);
        await waitFor(() => expect(calls.at(-1)?.params?.q).toBe("refused"));

        // A non-bubbling click: a real one reaches Monaco's body click handler (loaded by the design system),
        // which rejects the promise of the previous click and fails the whole test run in jsdom.
        canvasElement
            .querySelector("button.btn-next")
            ?.dispatchEvent(new MouseEvent("click", { bubbles: false }));
        await waitFor(() => expect(calls.at(-1)?.params?.page).toBe("2"));
        expect(calls.at(-1)?.params?.q).toBe("refused");
    },
};

export const Unsupported: Story = {
    name: "Older host without fetchEndpoint",
    args: { task, ...flow, execution } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() =>
            expect(
                canvas.getByText(/This Kestra version can't load this view/),
            ).toBeInTheDocument(),
        );
    },
};

export const Failed: Story = {
    name: "Endpoint error is reported",
    args: { task, ...flow, ...endpointFetchers(succeededOutputs, {}) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() =>
            expect(canvas.getByText("Could not load this view.")).toBeInTheDocument(),
        );
    },
};

export const NoExecution: Story = {
    name: "No execution",
    args: { task, ...flow, ...endpointFetchers(succeededOutputs, {}), execution: undefined } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText(/Run the flow/)).toBeInTheDocument());
    },
};