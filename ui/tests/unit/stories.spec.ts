import { describe, test } from "vitest";
import { composeStories } from "@storybook/vue3";

import * as topologyStories from "../storybook/components/SyncTopologyDetails.stories";
import * as detailsStories from "../storybook/components/SyncDetails.stories";
import * as logsStories from "../storybook/components/AirbyteLogsArtifact.stories";
import * as attemptsStories from "../storybook/components/AirbyteAttemptsArtifact.stories";
import * as streamsStories from "../storybook/components/AirbyteStreamsArtifact.stories";

// composeStories turns each exported story into a portable, runnable component. Story.run() renders
// it and executes its `play` function in jsdom, so no browser is needed.
const suites = {
    SyncTopologyDetails: composeStories(topologyStories),
    SyncDetails: composeStories(detailsStories),
    AirbyteLogsArtifact: composeStories(logsStories),
    AirbyteAttemptsArtifact: composeStories(attemptsStories),
    AirbyteStreamsArtifact: composeStories(streamsStories),
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