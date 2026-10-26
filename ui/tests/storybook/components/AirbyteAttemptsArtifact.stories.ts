import type { Meta, StoryObj } from "@storybook/vue3";
import { setup } from "@storybook/vue3";
import { within, expect, waitFor } from "storybook/test";
import { createI18n } from "vue-i18n";
import KestraDesignSystem from "@kestra-io/design-system";
import AirbyteAttemptsArtifact from "../../../src/components/AirbyteAttemptsArtifact.vue";
import {
    attemptsPage,
    endpointFetchers,
    succeededOutputs,
    task,
    type EndpointCall,
} from "../fixtures";

const i18n = createI18n({ legacy: false, locale: "en", messages: { en: {} } });
setup((app) => {
    app.use(i18n);
    app.use(KestraDesignSystem);
});

const meta: Meta<typeof AirbyteAttemptsArtifact> = {
    title: "Plugin UI / execution-artifact / AirbyteAttemptsArtifact",
    component: AirbyteAttemptsArtifact,
    tags: ["autodocs"],
};

export default meta;
type Story = StoryObj<typeof AirbyteAttemptsArtifact>;

const flow = { namespace: "company.team", flowId: "airbyte-sync" };
const calls: EndpointCall[] = [];

export const Attempts: Story = {
    name: "Failed then succeeded attempt",
    args: {
        task,
        ...flow,
        ...endpointFetchers(succeededOutputs, { attempts: attemptsPage }, calls),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("SUCCEEDED")).toBeInTheDocument());
        expect(canvas.getByText("FAILED")).toBeInTheDocument();
        expect(canvas.getByText("Destination timed out")).toBeInTheDocument();
        expect(canvas.getByText("1 min 30 s")).toBeInTheDocument();
        expect(canvas.getByText("60,800")).toBeInTheDocument();
        expect(calls[0]).toEqual({
            name: "attempts",
            params: { page: "1", size: "50" },
            query: { taskRunId: "tr-001" },
        });
    },
};

export const Empty: Story = {
    name: "No attempt recorded",
    args: {
        task,
        ...flow,
        ...endpointFetchers(succeededOutputs, {
            attempts: { page: 1, size: 50, total: 0, results: [] },
        }),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() =>
            expect(canvas.getByText("No attempt was recorded for this run.")).toBeInTheDocument(),
        );
    },
};