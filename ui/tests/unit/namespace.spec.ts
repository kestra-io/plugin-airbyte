import { describe, expect, test } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createI18n } from "vue-i18n";
import SyncDrawer from "../../src/components/SyncDrawer.vue";
import { failedOutputs, fetchers, task } from "../storybook/fixtures";

// The host mounts the drawer without installing the design system in the plugin, so Element Plus uses
// its default "el" namespace unless the component sets "kel", which is the one the design-system CSS targets.
describe("SyncDrawer in the host", () => {
    test("renders tags and alert with the design-system namespace", async () => {
        const wrapper = mount(SyncDrawer, {
            props: { task, namespace: "ns", flowId: "flow", ...fetchers(failedOutputs) } as any,
            global: {
                plugins: [createI18n({ legacy: false, locale: "en", messages: { en: {} } })],
            },
        });
        await flushPromises();

        expect(wrapper.find(".kel-tag").exists()).toBe(true);
        expect(wrapper.find(".kel-alert").exists()).toBe(true);
        expect(wrapper.find(".el-tag").exists()).toBe(false);
        expect(wrapper.find(".el-alert").exists()).toBe(false);
    });
});