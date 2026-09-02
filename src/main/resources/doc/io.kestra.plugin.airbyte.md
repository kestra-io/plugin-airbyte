# How to use the Airbyte plugin

Trigger and monitor Airbyte syncs from Kestra flows — with separate task packages for self-hosted Airbyte and Airbyte Cloud.

## Authentication

**Self-hosted** (`connections.*`): set `url` to your Airbyte instance URL. For authenticated instances, set `username` and `password`, or `token` for bearer token auth. For OAuth M2M, set `applicationCredentials.clientId` and `applicationCredentials.clientSecret`.

**Airbyte Cloud** (`cloud.jobs.*`): set `clientId` and `clientSecret` from your Airbyte Cloud workspace API credentials, or set `token` directly. Basic auth (`username`/`password`) is also supported as a fallback when neither is set.

Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and set connection properties on each task.

## Tasks

**Self-hosted** — `connections.Sync` triggers a sync by `connectionId` and waits for completion by default (`wait: true`). Set `failOnActiveSync: false` so that, when a sync is already running for the connection, the task succeeds and reports `alreadyRunning: true` (with a null `jobId`) instead of failing — it does not start or queue a second sync. Control polling with `pollFrequency` (default 1 second) and cap wait time with `maxDuration` (default 60 minutes). `connections.CheckStatus` polls an existing sync job by `jobId` until it reaches a terminal state.

**Airbyte Cloud** — `cloud.jobs.Sync` triggers a Cloud sync by `connectionId` and waits by default. `cloud.jobs.Reset` resets a connection's state. Both support `wait`, `maxDuration`, and `pollFrequency` with the same defaults.
