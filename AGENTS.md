# Kestra Airbyte Plugin

## What

Seamlessly integrate Airbyte connectors into your Kestra workflows. Exposes 5 plugin components (tasks, triggers, and/or conditions).

## Why

Enables Kestra workflows to interact with Airbyte, allowing orchestration of Airbyte-based operations as part of data pipelines and automation workflows.

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

### Important Commands

```bash
# Build the plugin
./gradlew shadowJar

# Run tests
./gradlew test

# Build without tests
./gradlew shadowJar -x test
```

### Configuration

All tasks and triggers accept standard Kestra plugin properties. Credentials should use
`{{ secret('SECRET_NAME') }}` — never hardcode real values.

## Agents

**IMPORTANT:** This is a Kestra plugin repository (prefixed by `plugin-`, `storage-`, or `secret-`). You **MUST** delegate all coding tasks to the `kestra-plugin-developer` agent. Do NOT implement code changes directly — always use this agent.
