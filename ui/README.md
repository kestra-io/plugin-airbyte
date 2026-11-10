# Airbyte plugin UI

Vue 3 + Vite micro-frontend for the `io.kestra.plugin.airbyte` plugin. It adds a topology card and a details dialog to `connections.Sync`, `connections.CheckStatus`, `cloud.jobs.Sync` and `cloud.jobs.Reset`. It registers the topology slots and the `execution-artifact/details` slot side by side: Kestra 2.0.x shows the topology card, Kestra 2.1+ shows the details behind the Artifacts button and in the Artifacts tab of the task popup. Kestra 1.3.x ignores them.

The topology card and the details dialog only read the task run's outputs and metrics through the `fetchOutputs` and `fetchMetrics` props. They make no HTTP call.

The execution Artifacts tab (`execution-artifact/<id>` slots, Kestra 2.1+) adds views backed by the plugin endpoints, called through the optional `fetchEndpoint` prop on the sync's own task run:

| Artifact   | Endpoint   | Tasks                                         |
| ---------- | ---------- | --------------------------------------------- |
| `logs`     | `logs`     | `connections.Sync`, `connections.CheckStatus` |
| `attempts` | `attempts` | `connections.Sync`, `connections.CheckStatus` |
| `streams`  | `snapshot` | all four tasks                                |

Airbyte Cloud has no attempts file and no Airbyte logs (the `logs` endpoint would only repeat the Kestra logs tab), so only `streams` is exposed there. On a host without `fetchEndpoint` the views show an upgrade message.

## Development

```bash
npm install
npm run dev        # start Vite dev server
npm run build      # production build
npm run storybook  # component workbench on :6006
npm test           # render every story in jsdom
npm run lint       # oxlint
npm run format     # oxfmt --check
```

Gradle runs `npm install` and `npm run build -- --outDir ../src/main/resources/plugin-ui` before packaging (`./gradlew buildUI`). It needs Node.js 18 or later on the machine.

After changing a component, update `height` and `heightWithExecution` in `vite.config.ts` so they match the number of rows the card renders (about 56px header plus 31.5px per row; the post-run card shows 6 rows, so 246).