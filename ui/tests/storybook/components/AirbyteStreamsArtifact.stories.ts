import type { Meta, StoryObj } from "@storybook/vue3";
import { setup } from "@storybook/vue3";
import { within, expect, waitFor, fireEvent } from "storybook/test";
import { createI18n } from "vue-i18n";
import KestraDesignSystem from "@kestra-io/design-system";
import AirbyteStreamsArtifact from "../../../src/components/AirbyteStreamsArtifact.vue";
import {
    cloudOutputs,
    cloudTask,
    endpointFetchers,
    snapshotStreams,
    succeededOutputs,
    task,
    type EndpointCall,
} from "../fixtures";

const i18n = createI18n({ legacy: false, locale: "en", messages: { en: {} } });
setup((app) => {
    app.use(i18n);
    app.use(KestraDesignSystem);
});

const meta: Meta<typeof AirbyteStreamsArtifact> = {
    title: "Plugin UI / execution-artifact / AirbyteStreamsArtifact",
    component: AirbyteStreamsArtifact,
    tags: ["autodocs"],
};

export default meta;
type Story = StoryObj<typeof AirbyteStreamsArtifact>;

const flow = { namespace: "company.team", flowId: "airbyte-sync" };
const calls: EndpointCall[] = [];

export const Streams: Story = {
    name: "Configuration merged with the run statistics, filtered by name",
    args: {
        task,
        ...flow,
        ...endpointFetchers(succeededOutputs, { snapshot: snapshotStreams }, calls),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("public.orders")).toBeInTheDocument());
        expect(canvas.getByText("incremental → append_dedup")).toBeInTheDocument();
        expect(canvas.getByText("updated_at")).toBeInTheDocument();
        expect(canvas.getByText("Not selected")).toBeInTheDocument();
        // committed rows of the run, from the job metadata
        expect(canvas.getByText("48,000")).toBeInTheDocument();
        expect(calls[0]).toEqual({ name: "snapshot", params: {}, query: { taskRunId: "tr-001" } });

        const filter = canvas.getByPlaceholderText("Filter by stream name");
        await fireEvent.input(filter, { target: { value: "ord" } });
        await fireEvent.change(filter);
        await waitFor(() => expect(calls.at(-1)?.params).toEqual({ name: "ord" }));
    },
};

export const Cloud: Story = {
    name: "Airbyte Cloud shows the configuration only",
    args: {
        task: cloudTask,
        ...flow,
        ...endpointFetchers(cloudOutputs, { snapshot: snapshotStreams }),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("public.orders")).toBeInTheDocument());
        expect(canvas.queryByText("Committed")).toBeNull();
    },
};

export const NoMatch: Story = {
    name: "No streams without a filter",
    args: {
        task,
        ...flow,
        ...endpointFetchers(succeededOutputs, { snapshot: { total: 0, streams: [] } }),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("No streams.")).toBeInTheDocument());
    },
};