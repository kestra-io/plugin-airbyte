# Airbyte plugin UI

Vue 3 + Vite micro-frontend for the `io.kestra.plugin.airbyte` plugin. It adds a topology card and a details dialog to `connections.Sync`, `connections.CheckStatus`, `cloud.jobs.Sync` and `cloud.jobs.Reset`. Kestra 2.0+ shows them; Kestra 1.3.x ignores them.

The components only read the task run's outputs and metrics through the `fetchOutputs` and `fetchMetrics` props. They make no HTTP call, and file outputs (`attempts`, `connectionSnapshot`) are not displayed because only their URIs are available.

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

After changing a component, update `height` and `heightWithExecution` in `vite.config.ts` so they match the number of rows the card renders (about 56px plus 31px per row).