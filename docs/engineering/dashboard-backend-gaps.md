# Backend gaps surfaced by the dashboard redesign

Living list of places where the dashboard wants something the backend doesn't expose
yet. Captured while restyling pages against the design system — each item is a
concrete dashboard surface that's degraded or impossible without a server change.
Treat as a backlog to triage, not a release blocker.

Sources of friction encountered: `app/pages/index.vue`,
`app/pages/instances/[id].vue`, `app/pages/nodes/[id].vue`,
`app/pages/groups/[name].vue`, `app/pages/crashes/index.vue`,
`app/components/instances/InstanceConsole.vue`,
`app/components/crashes/CrashDetailDialog.vue`,
`app/stores/overview.ts`, `docs/openapi.json`.

---

## 1. Time-series for charts

**Status**: closed (2026-05-11). Controller now maintains an in-memory ring
buffer (`MetricsTimeseries`) sampled every 15s by `MetricsTimeseriesSampler`
on the heartbeat scheduler, with 24h max retention per resource. Three new
endpoints — `GET /api/v1/overview/timeseries`,
`/api/v1/services/{id}/metrics/timeseries`,
`/api/v1/nodes/{id}/metrics/timeseries` — accept
`window={15m,1h,6h,24h}` and `buckets=[10,360]` query params, returning
bucket-averaged series with `null` for empty buckets. Overview series:
`players`, `instances`, `onlineNodes`. Instance: `tps1m`, `msptAvg`,
`heapUsedMb`, `playerCount`. Node: `cpuUsage`, `usedMemoryMb`,
`instanceCount`. State rebuilt on controller restart — sparklines, not
durable analytics.

Dashboard side: the overview store seeds its history rings from the server
on first fetch (instead of starting empty and waiting for SSE ticks); the
instance and node detail pages inline 1h sparklines next to each metric via
the new `useMetricsTimeseries` composable. Prometheus scrape remains an
option for the heavier reporting case but is out of scope here.

---

## 2. Heartbeat freshness signal

**Status**: closed (2026-05-11). `HeartbeatTracker` now emits
`NODE_HEARTBEAT_STALE` (with `missedPongs`, `lastHeartbeatAt`) the first time a
session crosses the configured missed-pongs threshold, and
`NODE_HEARTBEAT_RESUMED` on the next successful pong. `RESUMED` flips
`UNREACHABLE → ONLINE` but preserves operator-set statuses (CORDONED,
DRAINING). `NodeStatusUpdatedEvent` carries `lastHeartbeatAt` so periodic
status events drive the dashboard's "Last heartbeat" string without the wall
clock — the page still ticks `now.value` for relative-time rendering but the
freshness signal itself comes from the SSE bus.

---

## 3. Instance metrics push

**Status**: closed for game-server metrics (2026-05-11). `INSTANCE_METRICS`
SSE events now carry the metrics envelope inline (TPS/MSPT, heap, GC,
threads, players, worlds[], server version, plugin count). The instance
detail page applies the event payload directly instead of refetching, so
metrics ticks no longer trigger a REST round-trip. Proxy metrics
(`PROXY_METRICS_UPDATED`) are still pending.

---

## 4. Disk capacity, not just free

**Surface**: `app/pages/nodes/[id].vue` shows "Disk free: 12 GB" but can't
render a fill bar because there's no `totalDiskMb` to divide by.

**Gap**: `ConnectedNode` exposes `freeDiskMb` only.

**Suggested**: add `totalDiskMb` (and ideally per-mount breakdown for nodes
with multiple data partitions, since templates and logs live in different paths).

---

## 5. Scrollback on instance console

**Status**: closed (2026-05-11). Controller persists every accepted console
line (post-flood-control, post-truncation) into a capped Mongo collection
`console_lines` (256 MB cap, ascending index on `(instanceId, ts)`).
`GET /api/v1/services/{id}/console/history?since&until&limit` returns
ascending-by-timestamp rows; default limit 1000, hard cap 10000, with a
`truncated` flag when more rows matched than `limit` allowed.
`InstanceConsole.vue` fetches the last 2000 lines on mount before opening the
SSE stream, so reopening the page surfaces output from before the live session
opened.

**Retention caveat**: the collection is a single global capped FIFO. A chatty
instance can evict quiet ones once the cap is exhausted; this is best-effort
scrollback, not an audit log.

---

## 6. Player ping rows lack username

**Surface**: `app/pages/instances/[id].vue` proxy metrics card. The dashboard
shows player UUIDs truncated to 8 chars because that's all it has. Any human
trying to identify "who has 250ms ping" needs a second lookup.

**Gap**: `ProxyMetrics.playerPings` is `Record<uuid, ping>`. No username field.

**Suggested**: change to `Array<{ uuid: string; username: string; ping: number }>`.
The proxy already knows the username at handshake; pass it through.

---

## 7. Crash cause extraction

**Surface**: `CrashDetailDialog.vue`. We get `logTail` (full log output) and a
coarse `classification` (OOM/ERROR/SIGKILL/SIGTERM). The dashboard would benefit
from a one-line cause summary so the crashes table can show "OOM: Java heap
space" instead of just "OOM".

**Gap**: no `causeSummary` field. Users have to open the detail dialog to see
the actual exception.

**Suggested**:
- Server-side pattern-extract from the log tail (last `Exception:` line, last
  `at ...` frame, last "OutOfMemoryError: ..." substring).
- Store as `causeSummary: string` on the crash record.
- Expose in the list endpoint so the table can show it under the instance name.

**Adjacent**: `signature` field — a deterministic hash of the cause stack so
the dashboard can group "this exception happened 12 times this week". Current
crashes page has no notion of recurrence.

---

## 8. Crash trends sparkline

**Surface**: `app/pages/crashes/index.vue` page header. Kit pattern is a
sparkline of crash count over the last 24h next to the title.

**Gap**: no `/api/v1/crashes/trends?window=24h&buckets=24` endpoint.

**Suggested**: add it, or fold into the timeseries endpoint from §1 with a
`metric=crashes` query.

---

## 9. Audit log diff payload

**Status**: closed (2026-05-11). `StateStore.audit` gained a before/after
overload, `MongoStateStore` persists both snapshots alongside `details`, and
`AuditDtoMapper` parses them back to native JSON so the API returns them as
structured objects instead of escaped strings. Mutator routes (`group`,
`network`, `role`, `user`, `template` create/update/delete) capture
pre-mutation state and pass it to `RestServer.auditDiff`. The audit page
renders an expandable row with a `DiffViewer` when the entry carries a
diff; legacy entries (no before/after) render exactly as before.

---

## 10. Group `start` request shape

**Surface**: `app/pages/groups/[name].vue` `startGroup()`. Current call:
`POST /api/v1/groups/{name}/start?count=N` (count as query param).

**Gap**: count belongs in the request body for REST consistency; query strings
imply idempotent reads, which `start` is not.

**Suggested**: deprecate the query param and add request body
`{ count?: number; nodeAffinity?: string[]; reason?: string }`. Keep query
param for one minor version with a warning header.

---

## 11. Group aggregates SSE freshness

**Surface**: `app/pages/groups/[name].vue` shows `runningInstances`,
`totalPlayers`, `maxPlayers` (aggregates). After a player joins, the dashboard
relies on SSE to know to re-fetch.

**Gap**: I traced events `PLAYER_CONNECTED` / `PLAYER_DISCONNECTED` are listened
to in `overview.ts`, but the groups store may not subscribe. So the group detail
page can show stale player counts.

**Suggested**:
- Add `GROUP_AGGREGATES_UPDATED { groupName, runningInstances, totalPlayers }`
  events when those numbers change.
- Have `useGroupsStore` subscribe and patch its in-memory cache.

---

## 12. Templates: lazy file-tree loading

**Surface**: `app/pages/templates/[name].vue`, `TemplateFileTreePanel.vue`.
For large templates the entire file tree is fetched up front. The dashboard
installed `virtua` for virtualization but the limiting factor is the wire
payload, not the render.

**Gap**: `/api/v1/templates/{name}/files` returns the full tree.

**Suggested**:
- Add `?path=&depth=1` query params returning only the immediate children.
- Dashboard expands branches on demand.

---

## 13. Module REST diagnostics

**Surface**: `app/pages/modules/index.vue`, `modules/[...slug].vue`.

**Already shipped (good)**: `GET /api/v1/modules/platform/leaked-classloaders`
exists. That's an unusually thoughtful diagnostic. The dashboard should
prominently surface this on the modules page (it currently doesn't).

**Gap**: no `GET /api/v1/modules/platform/{name}/health` summarizing whether
that specific module is healthy (extensions resolved, cache warm, no leaked
classloaders, last reload successful, …).

**Suggested**: add a per-module health summary so the modules list can show a
dot+label per row instead of just "ACTIVE/FAILED".

---

## 14. System diagnostics summary

**Surface**: overview page `Callout` for cluster health. Today's dashboard
derives "healthy" by counting status fields client-side.

**Already shipped**: `GET /api/v1/system/diagnostics` per OpenAPI.

**Gap**: I haven't read the response shape, but if it isn't a list of
discrete `{ id, severity, message, fix }` items, it should be — that's exactly
what feeds a `Callout` component on the overview ("3 nodes draining: cancel
drain, or wait — node-2 has 4 instances pending migration").

**Suggested**: ensure `system/diagnostics` returns a structured list, not a free-form blob.

---

## 15. Test fixtures pin error copy

**Surface**: `app/stores/__tests__/*.test.ts` — multiple tests assert
`toast.error('Failed to load nodes')` literal strings. Voice-pass copy
improvements break these tests.

**Gap**: tests verify *strings*, not *behavior*. We can't tighten store-level
copy without churning tests.

**Suggested** (frontend, but worth flagging): change tests to assert
`expect(toast.error).toHaveBeenCalledTimes(1)` and the error category, not the
message string. Loosens the contract so copy can evolve with the design system.

---

## 16. Player counts vs capacity

**Surface**: instance detail "Players" row, kit mocks show `47/100` ratio.

**Gap**: `ServerInstance` has `playerCount` but no `maxPlayers` per-instance.
The cap is defined on the group (`group.maxPlayers`) not on the running
instance. For variable-size lobbies (per-template overrides) the dashboard
can't compute the ratio.

**Suggested**: surface `effectiveMaxPlayers` on the instance DTO, computed by
the controller from group + template overrides.

---

## 17. JVM details on nodes

**Surface**: `app/pages/nodes/[id].vue` Host card.

**Gap**: `connected.hostInfo.javaVersion` is shown but no vendor or build info
(GraalVM vs Temurin vs Adoptium), no GC choice (G1 / ZGC / Shenandoah). Affects
operator decisions.

**Suggested**: extend hostInfo with `javaVendor: string`, `javaRuntime: string`
(e.g. "OpenJDK 64-Bit Server VM"), `javaGc: string` (extracted at daemon boot).
