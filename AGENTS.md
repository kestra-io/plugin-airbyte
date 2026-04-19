# Kestra Airbyte Plugin

## What

- Provides plugin components under `io.kestra.plugin.airbyte`.
- Includes classes such as `JobStatus`, `AttemptFailureSummary`, `AttemptFailureReason`, `AttemptStatus`.

## Why

- What user problem does this solve? Teams need to trigger and monitor Airbyte sync and reset jobs in self-hosted Airbyte and Airbyte Cloud from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Airbyte steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Airbyte.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `airbyte`

Infrastructure dependencies (Docker Compose services):

- `airbyte-connector-builder-server`
- `airbyte-cron`
- `airbyte-proxy`
- `airbyte-temporal`
- `airbyte_internal`
- `airbyte_public`
- `bootloader`
- `data`
- `db`
- `db`
- `driver json-file`
- `flags`
- `init`
- `options`
- `server`
- `webapp`
- `worker`
- `workspace`

### Key Plugin Classes

- `io.kestra.plugin.airbyte.cloud.jobs.AbstractTrigger`
- `io.kestra.plugin.airbyte.cloud.jobs.Reset`
- `io.kestra.plugin.airbyte.cloud.jobs.Sync`
- `io.kestra.plugin.airbyte.connections.CheckStatus`
- `io.kestra.plugin.airbyte.connections.Sync`

### Project Structure

```
plugin-airbyte/
├── src/main/java/io/kestra/plugin/airbyte/models/
├── src/test/java/io/kestra/plugin/airbyte/models/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
