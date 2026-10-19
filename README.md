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

### Connection snapshot, job link and assets

`Sync` and `CheckStatus` also return `connection` (source and destination connector types, workspace, stream count), `connectionSnapshot` (JSON file in internal storage) and `jobUrl` (link to the job logs in the Airbyte web app, built from `webappUrl`, which defaults to `url`). They come from Airbyte's config API, and connector configurations are never read. If the credentials cannot read connections, sources and destinations, the task logs a warning and these outputs stay `null`.

With assets enabled (Enterprise Edition only), each replicated stream that committed records is emitted as a table asset `io.kestra.plugin.ee.assets.Table` with id `<destinationId>.<namespace>.<table>` (the namespace segment is left out when it cannot be resolved without the destination configuration) and the source stream as its input. Ids use Airbyte's logical names, which can differ from the physical table names, and do not match the ids emitted by other plugins. Assets are emitted only when the task ends in SUCCESS or WARNING: Kestra drops the assets of a FAILED task run, so none are emitted for a failed job. Their row and byte counts are the same per-stream figures as `metadata`, which only count successful attempts, so a job that ended in WARNING (partial success, no successful attempt) emits no asset.

## Airbyte Cloud

`cloud.jobs.Sync` and `cloud.jobs.Reset` return `job` plus `connection`, `connectionSnapshot` and `jobUrl` (link to the job in Airbyte Cloud). `Sync` emits table assets for the replicated streams when the job succeeds, with the same id format as self-hosted; `Reset` emits none. The Airbyte Cloud public API only reports `bytesSynced`, `rowsSynced` and `duration` for a job, so there are no per-stream or per-attempt statistics, no `attempts` output, no per-attempt sub task runs and no `onPartialSuccess`.

**Migration note:** `cloud.jobs.Sync` and `cloud.jobs.Reset` now fail when the Airbyte job ends `FAILED` or `CANCELLED`. Outputs remain available; use `allowFailure: true` or an `errors` branch to keep the previous behavior.

## UI

On Kestra 2.0 and later (open the drawer with the "Show Details" action of the node), `connections.Sync`, `connections.CheckStatus`, `cloud.jobs.Sync` and `cloud.jobs.Reset` get a topology card and a task drawer. The card shows the source and destination types, stream count, rows and bytes synced, attempts and status. The drawer adds the job summary, failure reasons, a per-stream table with rows, bytes and rejected records, the connection summary and a link to the job in Airbyte. Airbyte Cloud jobs show job totals only.

The UI only displays inline outputs and metrics: the contents of the `attempts` and `connectionSnapshot` files are not shown. Kestra 1.3.x ignores the UI. See [`ui/README.md`](ui/README.md) for local development.

## Documentation
* Full documentation can be found under: [kestra.io/docs](https://kestra.io/docs)
* Documentation for developing a plugin is included in the [Plugin Developer Guide](https://kestra.io/docs/plugin-developer-guide/)


## License
Apache 2.0 © [Kestra Technologies](https://kestra.io)


## Stay up to date

We release new versions every month. Give the [main repository](https://github.com/kestra-io/kestra) a star to stay up to date with the latest releases and get notified about future updates.

![Star the repo](https://kestra.io/star.gif)
