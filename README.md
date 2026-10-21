<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png"  alt="Kestra workflow orchestrator" />
  </a>
</p>

<h1 align="center" style="border-bottom: none">
    Event-Driven Declarative Orchestrator
</h1>

<div align="center">
 <a href="https://github.com/kestra-io/kestra/releases"><img src="https://img.shields.io/github/tag-pre/kestra-io/kestra.svg?color=blueviolet" alt="Last Version" /></a>
  <a href="https://github.com/kestra-io/kestra/blob/develop/LICENSE"><img src="https://img.shields.io/github/license/kestra-io/kestra?color=blueviolet" alt="License" /></a>
  <a href="https://github.com/kestra-io/kestra/stargazers"><img src="https://img.shields.io/github/stars/kestra-io/kestra?color=blueviolet&logo=github" alt="Github star" /></a> <br>
<a href="https://kestra.io"><img src="https://img.shields.io/badge/Website-kestra.io-192A4E?color=blueviolet" alt="Kestra infinitely scalable orchestration and scheduling platform"></a>
<a href="https://kestra.io/slack"><img src="https://img.shields.io/badge/Slack-Join%20Community-blueviolet?logo=slack" alt="Slack"></a>
</div>

<br />

<p align="center">
  <a href="https://twitter.com/kestra_io" style="margin: 0 10px;">
        <img src="https://kestra.io/twitter.svg" alt="twitter" width="35" height="25" /></a>
  <a href="https://www.linkedin.com/company/kestra/" style="margin: 0 10px;">
        <img src="https://kestra.io/linkedin.svg" alt="linkedin" width="35" height="25" /></a>
  <a href="https://www.youtube.com/@kestra-io" style="margin: 0 10px;">
        <img src="https://kestra.io/youtube.svg" alt="youtube" width="35" height="25" /></a>
</p>

<br />
<p align="center">
    <a href="https://go.kestra.io/video/product-overview" target="_blank">
        <img src="https://kestra.io/startvideo.png" alt="Get started in 4 minutes with Kestra" width="640px" />
    </a>
</p>
<p align="center" style="color:grey;"><i>Get started with Kestra in 4 minutes.</i></p>

# Kestra Airbyte Plugin

## Why

- What user problem does this solve? Teams need to trigger and monitor Airbyte sync and reset jobs in self-hosted Airbyte and Airbyte Cloud from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Airbyte steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Airbyte.

## What

- Provides plugin components under `io.kestra.plugin.airbyte`.
- Includes classes such as `JobStatus`, `AttemptFailureSummary`, `AttemptFailureReason`, `AttemptStatus`.

## Self-hosted job outputs

When `wait` is enabled, `io.kestra.plugin.airbyte.connections.Sync` and `CheckStatus` expose:

- `job`: status, timing, attempt counts, `partialSuccess` and sanitized `failures` (`origin`, `type`, `externalMessage`, `retryable`, `stream`).
- `attempts`: internal storage Ion file with one row per Airbyte attempt, including per-stream statistics.
- `metadata`: `rowsSynced`, `bytesSynced`, `recordsRejected` and the source and destination tables with their row and byte counts.

Each Airbyte attempt is also recorded as a sub task run named `attempt-<n>`.

A job that does not end `SUCCEEDED` fails the task but keeps these outputs, so `errors` and `finally` branches and `allowFailure: true` can read them. With `onPartialSuccess: WARNING`, a `FAILED` job that committed records ends in `WARNING` instead. `internalMessage` and stack traces from Airbyte are never logged or returned, and `externalMessage` is truncated to 2 KiB. `finalJobStatus` is deprecated: use `job.status`.

### Airbyte logs

While `Sync` (with `wait`) or `CheckStatus` waits, the Airbyte job logs are forwarded to the Kestra task logs, each event once, prefixed with `[Airbyte]` and the component that emitted it: `[Airbyte][source]`, `[Airbyte][destination]`, `[Airbyte][orchestrator]` or `[Airbyte][platform]` (plain-text lines get `[Airbyte]` only). The `airbyteLogs` file and the `logs` endpoint are not prefixed. The Kestra level follows the level Airbyte reports (unknown levels are `INFO`). Older Airbyte versions that only return plain text lines are still supported, with the level guessed from the line.

- `airbyteLogs`: internal storage Ion file with one row per event of every attempt (`attemptNumber`, `timestamp`, `level`, `logSource`, `message`). It is also available when the job fails.
- `logFrequency` (default `10s`): how often the logs of the running attempt are fetched, independent of `pollFrequency`. Job status is polled with Airbyte's lightweight `jobs/get_light` (with an automatic fallback to `jobs/get` on versions without it), and the remaining logs are fetched once when the job ends.
- `maxLogEvents` (default `100000`): events beyond this count, from the start of the job, are neither logged nor stored, and a warning is logged. `0` disables the Airbyte logs.
- `platformLogLevel` (default `INFO`): Kestra level of the `platform` events that Airbyte reports at `info`, which are mostly stage transitions. Set it to `DEBUG` to hide them at the default level. `platform` warnings and errors and the other sources keep their own level, and `airbyteLogs` keeps Airbyte's level.

Like `failures`, the logs are sanitized: stack trace frames are replaced by a single `... stack trace omitted` line (the exception class and message lines are kept, since they are what explains a connector failure) and Airbyte's internal `caller` details are dropped.

The `logs` plugin endpoint pages through `airbyteLogs` (`origin: airbyte`), oldest first, with the filters `attempt`, `source` (repeated or comma-separated: `platform`, `source`, `destination`, `orchestrator`), `level` (minimum, default `INFO`), `q` (case-insensitive text) plus `page` and `size`. Without `airbyteLogs`, it serves the Kestra task run logs (`origin: kestra`, `level` and `q` only). Plugin endpoints cannot answer with an HTTP 400, so an invalid `attempt`, `source` or `level` is ignored.

### Connection snapshot, job link and assets

`Sync` and `CheckStatus` also return `connection` (source and destination connector types, workspace, stream count), `connectionSnapshot` (JSON file in internal storage) and `jobUrl` (link to the job logs in the Airbyte web app, built from `webappUrl`, which defaults to `url`). They come from Airbyte's config API, and connector configurations are never read. If the credentials cannot read connections, sources and destinations, the task logs a warning and these outputs stay `null`.

With `assets.enableAuto: true` (Enterprise Edition only; Kestra core applies this setting, so the plugin makes no extra Airbyte call for assets), each replicated stream that committed records is emitted as a table asset `io.kestra.plugin.ee.assets.Table`, with the source stream as its input. The asset id is `<database>.<schema>.<table>` (`<project>.<dataset>.<table>` for BigQuery, `<database>.<table>` for MySQL and ClickHouse, where the database is the stream namespace when there is one, else the configured database), read from the destination connector configuration so it matches the ids of the dbt, JDBC and Fivetran plugins for the same table. Segments are sanitized (other characters become `_`, 150 characters at most with a hash suffix) and case is not normalized, so a Snowflake table can differ in case. Only the location keys of Postgres, Redshift, MS SQL Server, Snowflake, Databricks, BigQuery, MySQL and ClickHouse destinations are read, and never stored. When the location cannot be resolved the id falls back to `<destinationId>.<namespace>.<table>` with the Airbyte destination ID. The destination ID, database, schema, source ID, source name and source type are in the asset metadata, and the source stream input keeps the source ID: `<sourceId>.<namespace>.<stream>`. The source ID is used rather than the source name because it is unique and survives renames, so lineage is not broken when a source is renamed; the name is in the metadata as `sourceName`. If building or emitting an asset fails, `assets.assetFailureBehavior` applies: `FAIL` fails the task, `WARN` (the default) ends it in WARNING, `IGNORE` only logs at debug level. Asset ids changed from `<destinationId>.<namespace>.<table>` to the location-based format: assets emitted by earlier versions stay as orphans. Assets are emitted only when the task ends in SUCCESS or WARNING: Kestra drops the assets of a FAILED task run, so none are emitted for a failed job. Their row and byte counts are the same per-stream figures as `metadata`, which only count successful attempts, so a job that ended in WARNING (partial success, no successful attempt) emits no asset.

## Airbyte Cloud

`cloud.jobs.Sync` and `cloud.jobs.Reset` return `job` plus `connection`, `connectionSnapshot` and `jobUrl` (link to the job in Airbyte Cloud). `Sync` emits table assets for the replicated streams when the job succeeds, with the same id format and `assets.assetFailureBehavior` handling as self-hosted (the location comes from the destination `configuration()` of the SDK). The Cloud API does not report stream namespaces, so with a `source` namespace or a custom format using `${SOURCE_NAMESPACE}` the id falls back to `<destinationId>.<table>`; `Reset` emits none. The Airbyte Cloud public API only reports `bytesSynced`, `rowsSynced` and `duration` for a job, so there are no per-stream or per-attempt statistics, no `attempts` output, no per-attempt sub task runs and no `onPartialSuccess`. On Airbyte Cloud, the SDK only reads sources and destinations of connectors it knows: if one cannot be read, the task logs a warning and still returns the connection, `jobUrl` and assets, with that side's connector type left `null`.

**Migration note:** `cloud.jobs.Sync` and `cloud.jobs.Reset` now fail when the Airbyte job ends `FAILED` or `CANCELLED`. Outputs remain available; use `allowFailure: true` or an `errors` branch to keep the previous behavior.

## UI

On Kestra 2.0 and later (open the dialog with the "Airbyte details" action of the node), `connections.Sync`, `connections.CheckStatus`, `cloud.jobs.Sync` and `cloud.jobs.Reset` get a topology card and a details dialog. The card shows the source and destination types, stream count, rows and bytes synced, attempts and status. The dialog shows a status header with a link to the job in Airbyte, an alert for failed jobs, the task settings (authentication shows the method only, never credentials), the job summary, grouped failure reasons, a per-stream table with emitted and committed rows, bytes and rejected records, and the connection summary. Airbyte Cloud jobs show job totals only.

The UI only displays inline outputs and metrics: the contents of the `attempts`, `airbyteLogs` and `connectionSnapshot` files are not shown. Kestra 1.3.x ignores the UI. See [`ui/README.md`](ui/README.md) for local development.

## Documentation
* Full documentation can be found under: [kestra.io/docs](https://kestra.io/docs)
* Documentation for developing a plugin is included in the [Plugin Developer Guide](https://kestra.io/docs/plugin-developer-guide/)


## License
Apache 2.0 © [Kestra Technologies](https://kestra.io)


## Stay up to date

We release new versions every month. Give the [main repository](https://github.com/kestra-io/kestra) a star to stay up to date with the latest releases and get notified about future updates.

![Star the repo](https://kestra.io/star.gif)
