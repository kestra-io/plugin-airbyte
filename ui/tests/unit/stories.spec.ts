import { describe, test } from "vitest";
import { composeStories } from "@storybook/vue3";

import * as topologyStories from "../storybook/components/SyncTopologyDetails.stories";
import * as detailsStories from "../storybook/components/SyncDetails.stories";

// composeStories turns each exported story into a portable, runnable component. Story.run() renders
// it and executes its `play` function in jsdom, so no browser is needed.
const suites = {
    SyncTopologyDetails: composeStories(topologyStories),
    SyncDetails: composeStories(detailsStories),
};

for (const [suite, stories] of Object.entries(suites)) {
    describe(`${suite} stories`, () => {
        for (const [storyName, Story] of Object.entries(stories)) {
            if (typeof Story.play !== "function") continue;
            test(storyName, async () => {
                await Story.run();
            });
        }
    });
}