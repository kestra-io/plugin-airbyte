# Kestra Airbyte Plugin

## What

- Provides plugin components under `io.kestra.plugin.airbyte`.
- Includes classes such as `JobStatus`, `AttemptFailureSummary`, `AttemptFailureReason`, `AttemptStatus`.

## Why

- This plugin integrates Kestra with Airbyte Connections.
- It provides tasks that start and monitor sync jobs on a self-hosted Airbyte instance.

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
