# How to use the Airbyte plugin

Trigger and monitor Airbyte syncs from Kestra flows — with separate task packages for self-hosted Airbyte and Airbyte Cloud.

## Authentication

**Self-hosted** (`connections.*`): set `url` to your Airbyte instance URL. For authenticated instances, set `username` and `password`, or `token` for bearer token auth. For OAuth M2M, set `applicationCredentials.clientId` and `applicationCredentials.clientSecret`.

**Airbyte Cloud** (`cloud.jobs.*`): set `clientId` and `clientSecret` from your Airbyte Cloud workspace API credentials, or set `token` directly. Basic auth (`username`/`password`) is also supported as a fallback when neither is set.

Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and set connection properties on each task.

## Tasks

**Self-hosted** — `connections.Sync` triggers a sync by `connectionId` and waits for completion by default (`wait: true`). Completed syncs return `metadata` with `rowsSynced` and the affected source and destination tables, including their namespaces and per-table row counts. Metadata is `null` when `wait: false` or when an active sync is skipped because final job statistics are not yet available. If a sync is already running for the connection, `onActiveSync` controls what happens: `ADOPT` (default) attaches to the in-flight job and polls it to completion instead of triggering a new sync; `FAIL` fails the task immediately; `SKIP` succeeds immediately with `alreadyRunning: true` and a null `jobId`, without starting or queuing a second sync. `failOnActiveSync` is deprecated but still supported: when set, it overrides `onActiveSync` (`true` → `FAIL`, `false` → `SKIP`). Control polling with `pollFrequency` (default 1 second) and cap wait time with `maxDuration` (default 60 minutes) — for an adopted job, `maxDuration` is counted from the moment it is adopted, not from the job's actual start time. `connections.CheckStatus` polls an existing sync job by `jobId` until it reaches a terminal state and exposes the same metadata.

**Job outputs and failures** (self-hosted) — when `wait` is enabled, `connections.Sync` and `connections.CheckStatus` return `job` (status, timing, attempt counts, `partialSuccess` and sanitized `failures`), `attempts` (an internal storage Ion file with one row per attempt and its per-stream statistics) and an extended `metadata` (`bytesSynced`, `recordsRejected`, bytes per table). Each Airbyte attempt also appears as a sub task run named `attempt-<n>`. A job that ends in any state other than `SUCCEEDED` fails the task, but its outputs are kept, so `errors` and `finally` branches and `allowFailure: true` can still read them. Set `onPartialSuccess: WARNING` to end in `WARNING` instead when the job failed after committing records. Failure messages keep Airbyte's `externalMessage` (truncated to 2 KiB) and never include its `internalMessage` or stack trace. `finalJobStatus` is deprecated in favor of `job.status`. New metrics: `duration`, `records.rejected` and `attempts.failed`.

**Airbyte Cloud** — `cloud.jobs.Sync` triggers a Cloud sync by `connectionId` and waits by default. `cloud.jobs.Reset` resets a connection's state. Both support `wait`, `maxDuration`, and `pollFrequency` with the same defaults.
