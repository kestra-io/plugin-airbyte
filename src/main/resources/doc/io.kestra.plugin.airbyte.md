# How to use the Airbyte plugin

Trigger and monitor Airbyte syncs from Kestra flows — with separate task packages for self-hosted Airbyte and Airbyte Cloud.

## Authentication

**Self-hosted** (`connections.*`): set `url` to your Airbyte instance URL. For authenticated instances, set `username` and `password`, or `token` for bearer token auth. For OAuth M2M, set `applicationCredentials.clientId` and `applicationCredentials.clientSecret`.

**Airbyte Cloud** (`cloud.jobs.*`): set `clientId` and `clientSecret` from your Airbyte Cloud workspace API credentials, or set `token` directly.

Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and apply connection properties globally with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults).

## Tasks

**Self-hosted** — `connections.Sync` triggers a sync by `connectionId` and waits for completion by default (`wait: true`). Set `failOnActiveSync: false` to let a new sync queue behind an active one rather than failing. Control polling with `pollFrequency` (default 1 second) and cap wait time with `maxDuration` (default 60 minutes). `connections.CheckStatus` polls an existing sync job by `jobId` until it reaches a terminal state.

**Airbyte Cloud** — `cloud.jobs.Sync` triggers a Cloud sync by `connectionId` and waits by default. `cloud.jobs.Reset` resets a connection's state. Both support `wait`, `maxDuration`, and `pollFrequency` with the same defaults.
