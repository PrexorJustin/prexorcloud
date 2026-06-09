---
title: Configuration Reference
description: Every key in controller.yml and daemon.yml — type, default, validation, and the env vars that actually exist.
---

The Controller and Daemon each read one YAML file at startup. Every key maps
to a Java record with a compact constructor that applies the default below —
omit a key and you get that default. This page is the canonical reference. The
records under `java/cloud-controller/.../controller/config/` and
`java/cloud-daemon/.../daemon/config/` are the ground truth.

## What you'll learn

- Every Controller and Daemon configuration key, its type, default, and effect
- Which keys `ConfigValidator` rejects, and the exact error each emits
- What is required in the `production` profile
- Which environment variables exist (fewer than you'd expect) and what they do

## How config is loaded

- The Controller reads `config/controller.yml`, relative to its working
  directory (the install root). The Daemon reads `config/daemon.yml`.
- If the file is absent on first boot, the process copies the bundled
  `defaults/controller.yml` or `defaults/daemon.yml` from the classpath, then
  loads it.
- Parsing uses Jackson with `FAIL_ON_UNKNOWN_PROPERTIES` disabled. Unknown keys
  are ignored, not rejected. A removed key (for example `dashboard.path`) left
  in an old file is silently dropped.
- There is no signal-based hot reload. Changing the file requires a restart.
- Defaults are applied per record. For most numeric fields the rule is "value
  `<= 0` resolves to the default", so `0` does not mean "zero" — it means "use
  the default". The exceptions are noted inline.

The Controller writes back to `controller.yml` in two cases: it generates and
persists a `uuid` on first boot if none is set, and it generates and persists a
`security.jwtSecret` if none is set.

## controller.yml

### `uuid`

| Field | Type | Default | Notes |
|---|---|---|---|
| `uuid` | string | random UUID | Stable Controller identifier. Generated on first boot and written back into `controller.yml`. In a cluster, every Controller must have its own. |

### `http`

| Field | Type | Default | Notes |
|---|---|---|---|
| `host` | string | `0.0.0.0` | REST/SSE/dashboard bind address. |
| `port` | int | `8080` | REST + SSE + bundled dashboard. Must differ from `grpc.port`. |
| `cors.allowedOrigins` | list&lt;string&gt; | `http://localhost:3000`, `:3001`, `:3002`, `:3003` | Browser origins allowed. Each entry must start with `http://` or `https://` or the Controller refuses to boot. Add the dashboard origin when it is served from another domain. |

### `grpc`

| Field | Type | Default | Notes |
|---|---|---|---|
| `host` | string | `0.0.0.0` | Bind address for Daemon connections. |
| `port` | int | `9090` | gRPC port; mTLS terminated by the Controller. Must differ from `http.port`. |

### `network`

| Field | Type | Default | Notes |
|---|---|---|---|
| `allowedSubnets` | list&lt;string&gt; | `0.0.0.0/0`, `::/0` | CIDR allowlist for HTTP clients. Lock this down for production. An empty list rejects all clients. |

### `database` (MongoDB)

| Field | Type | Default | Notes |
|---|---|---|---|
| `uri` | string | `mongodb://localhost:27017` | Required — boot fails if blank. |
| `database` | string | `prexorcloud` | Database name. |

PrexorCloud does not embed Mongo. Replica-set URIs are supported and
recommended for HA:
`mongodb://h1,h2,h3/prexorcloud?replicaSet=rs0&w=majority`.

### `redis`

| Field | Type | Default | Notes |
|---|---|---|---|
| `uri` | string | `redis://localhost:6379` | Redis or Valkey (same protocol). `rediss://...` enables TLS. |

The whole `redis` block is nullable. Omit it entirely and the Controller runs
with in-process coordination fallbacks (single-controller development only).
Set it for multi-controller or load-testing. The `production` profile refuses
to start without it.

When present but with a blank `uri`, boot fails with
`redis.uri must not be blank when redis is configured`.

### `runtime`

| Field | Type | Default | Notes |
|---|---|---|---|
| `profile` | string | `development` | One of `development`, `production` (case-insensitive, trimmed). Any other value is rejected. Production swaps in Redis-backed coordination for leases, JWT revocation, login lockout, SSE replay, and rate limits, and tightens signing defaults. |

### `logging`

| Field | Type | Default | Notes |
|---|---|---|---|
| `level` | string | `INFO` | SLF4J level: `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE`. |
| `format` | enum | `HUMAN` | `HUMAN` for single-line text, `JSON` for line-delimited structured JSON. An unrecognized value falls back to `HUMAN`. |

### `scheduler`

| Field | Type | Default | Notes |
|---|---|---|---|
| `evaluationIntervalSeconds` | int | `15` | Tick frequency for placement and scaling decisions. Must be `>= 1`. |
| `scalingCooldownSeconds` | int | `60` | Default per-group cooldown after a scale event. Per-group config can override. |
| `nodeTimeoutSeconds` | int | `90` | Time without heartbeat before a node is marked offline. |
| `auditRetentionDays` | int | `90` | TTL on the `audit_log` collection, keyed off `createdAt`. |

### `heartbeat`

| Field | Type | Default | Notes |
|---|---|---|---|
| `intervalMs` | long | `30000` | Daemon → Controller heartbeat cadence. Must be `>= 1000`. |
| `missedThreshold` | int | `3` | Consecutive misses before a node session is stale. Must be `>= 1`. |

### `security`

| Field | Type | Default | Notes |
|---|---|---|---|
| `jwtSecret` | string | empty → auto-generated | When blank, a secret is generated on first boot and written back into `controller.yml`. Set a managed value in production. |
| `jwtExpirationMinutes` | int | `1440` | 24 hours. Must be `>= 1` and `<= 43200` (30 days). |
| `initialAdminPassword` | string | empty → auto-generated | First-boot bootstrap only. See note below. |
| `jwtPreviousSecrets` | list&lt;string&gt; | `[]` | Old secrets accepted during rotation until their tokens expire. Invalid entries are logged and skipped. |
| `rateLimiting.perIpPerMinute` | int | `100` | Per-IP REST limit. Must be `>= 1`. |
| `rateLimiting.perUserPerMinute` | int | `300` | Per-user REST limit. Must be `>= 1`. |
| `rateLimiting.failOpenOnRedisError` | bool | `false` | Allow traffic when Redis is unreachable. Default closed. |
| `lockout.enabled` | bool | `true` | Account lockout on failed logins. |
| `lockout.maxAttempts` | int | `5` | Failed logins before a lock. Must be `>= 1`. |
| `lockout.windowSeconds` | int | `900` | Sliding failure window. Must be `>= 1`. |
| `lockout.lockoutSeconds` | int | `900` | Lock duration after threshold. Must be `>= 1`. |
| `passwordReset.enabled` | bool | `false` | When false, `/api/v1/auth/password-reset/*` returns 404 and no manager is wired. |
| `passwordReset.tokenTtlMinutes` | int | `30` | Single-use token lifetime. |
| `passwordReset.resetUrlBase` | string | empty | Dashboard base URL. The mailer appends `/auth/reset-password?token=...`. Blank still mints tokens; the email falls back to a relative path. |
| `passwordReset.smtp.host` | string | empty | Blank means SMTP is disabled — `LogMailer` writes the reset link to the Controller log instead of sending mail. |
| `passwordReset.smtp.port` | int | `587` | |
| `passwordReset.smtp.startTls` | bool | `false`* | |
| `passwordReset.smtp.implicitTls` | bool | `false` | |
| `passwordReset.smtp.username` | string | empty | |
| `passwordReset.smtp.password` | string | empty | |
| `passwordReset.smtp.from` | string | empty | |
| `passwordReset.smtp.connectTimeoutMs` | int | `10000` | |
| `passwordReset.smtp.readTimeoutMs` | int | `10000` | |

\* `startTls` is a primitive boolean. Its no-arg record default is `true`, but
because absent YAML keys deserialize to the primitive zero value, an omitted
`startTls` resolves to `false`. Set it explicitly to `true` when your relay
requires STARTTLS.

The initial admin password: when no users exist on first boot, the Controller
creates `admin`. If `initialAdminPassword` is blank it generates a random one.
Either way it writes the plaintext to `config/.initial-admin-password` (mode
`0600`) and logs the file location, not the password. Change the password and
delete the file after first login.

In the `production` profile, lockout and password-reset state live in Redis and
are shared across Controllers.

### `crashes`

| Field | Type | Default | Notes |
|---|---|---|---|
| `ringBufferSize` | int | `500` | Per-process in-memory ring of recent crashes for the dashboard. |
| `crashLoopThreshold` | int | `3` | Crashes within the window before a group is paused. |
| `crashLoopWindowSeconds` | int | `300` | Sliding crash-loop window. |

### `metrics`

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `true` | Exposes `/metrics` (Prometheus exposition). Gate access with a reverse-proxy ACL. |
| `retentionHours` | int | `168` | In-process retention for the dashboard's mini-graphs. Independent of your Prometheus retention. |
| `collectionIntervalSeconds` | int | `30` | Internal sampling cadence for derived gauges. |

### `modules`

| Field | Type | Default | Notes |
|---|---|---|---|
| `directory` | string | `modules` | Installed module bundles. |
| `dataDirectory` | string | `modules/data` | Per-module on-disk storage root. |
| `hotReload` | — | — | Not a config field. Module hot-reload is not toggled here. |
| `registries` | list&lt;string&gt; | `[]` | Registry index URLs trusted for `install-from-registry`. Empty means no registry browsing. |
| `quotas.<moduleId>` | map | `{}` | Per-module soft quotas. See below. |
| `signing.required` | bool | profile-default | `null` resolves to `true` in `production`, `false` in `development`. |
| `signing.trustRoot` | string | empty | PEM bundle. `PUBLIC KEY` blocks for `KEYED`; `PUBLIC KEY` and/or `CERTIFICATE` blocks for `COSIGN_BUNDLE`. |
| `signing.mode` | enum | `KEYED` | `KEYED` for a `.sig` sidecar; `COSIGN_BUNDLE` for a `.cosign.bundle` from `cosign sign-blob --bundle`. |
| `signing.allowUnsignedDevelopment` | bool | `true` | Lets the `development` profile install unsigned bundles even when `required=true`. |
| `signing.rekor.policy` | enum | `DISABLED` | `REQUIRE_SET` enforces offline Rekor SET verification — the bundle must carry a `SignedEntryTimestamp`. |
| `signing.rekor.publicKey` | string | empty | PEM for the Rekor public key. Required when `policy=REQUIRE_SET`. |

Each `quotas.<moduleId>` entry holds `maxCpuMillisPerMinute` (long),
`maxAllocatedMbPerMinute` (long), and `maxThreads` (int). Any non-positive value
means unlimited for that dimension, so an empty quota block enforces nothing.
Breaches are advisory: a WARN log and the `prexorcloud.module.quota.exceeded`
metric, no throttling.

See [Cosign Pipeline](/internals/cosign-pipeline/) for the verification flow.

### `maintenance`

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `false` | Global maintenance mode. Overrides per-group settings. |
| `message` | string | `The network is currently under maintenance.` | Surfaced to dashboards and proxy plugins. |

Per-group bypass is configured on the group (`maintenanceBypass`), not here.
There is no global bypass list.

### `dashboard`

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `true` | Serves the bundled dashboard from a fixed `dashboard/` directory under the install root. Set `false` when running the dashboard separately so the Controller does not serve a stale bundle. |

`dashboard.path` is no longer configurable; a leftover `path:` entry is ignored.

### `backup`

| Field | Type | Default | Notes |
|---|---|---|---|
| `directory` | string | `backups` | Backup root, relative to the install root. |
| `retentionCount` | int | `10` | Manifests kept by the catalog. Older ones are pruned by `prexorctl backup prune`. |

### `share`

Controls the `--share` workflow that uploads redacted diagnostics to a
pastebin. Sharing is operator-invoked; redaction is unconditional.

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `false` | Opt in before any artifact leaves the cluster. |
| `pasteUrl` | string | `https://pste.dev` | Pastebin endpoint. |
| `pasteToken` | string | empty | Optional auth token for the pastebin. |
| `defaultExpiry` | string | `1d` | Paste expiry. |
| `defaultPrivate` | bool | `false`* | |
| `e2e` | bool | `false` | End-to-end encryption flag. |

\* The no-arg default for `defaultPrivate` is `true`, but an omitted YAML key
resolves the primitive boolean to `false`. Set it explicitly.

### `cluster`

| Field | Type | Default | Notes |
|---|---|---|---|
| `id` | string | null | Pins this Controller to a Mongo cluster. Cross-checked against `cluster_meta` at boot; mismatch refuses to start. |
| `joinedFrom` | string | null | Informational, written by the join wizard. |
| `joinedAt` | string | null | Informational. |

### `raft`

Node-local Raft transport for the cluster control plane. Cluster-wide tuning
(election timeout, snapshot retention) lives in the state machine, not here.

| Field | Type | Default | Notes |
|---|---|---|---|
| `host` | string | `0.0.0.0` | Raft bind address. |
| `port` | int | `9190` | Raft gRPC port. |
| `dataDir` | string | `data/raft` | On-disk Raft log/snapshot directory. |
| `joinAddrs` | list&lt;string&gt; | `[]` | gRPC endpoints of existing members, used at boot for discovery. Empty means first member of a new cluster or a restarting member; the on-disk Raft data dir disambiguates. |

### `telemetry`

Distributed tracing (OpenTelemetry). Disabled by default with zero runtime
cost — a no-op tracer is installed and the SDK never starts.

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `false` | Turn tracing on. |
| `otlpEndpoint` | string | `http://localhost:4317` | OTLP target (Jaeger, Tempo, Honeycomb, Datadog). |
| `serviceName` | string | `prexorcloud-controller` | Span service name. |
| `samplerRatio` | double | `1.0` | Parent-based head-sampler ratio, clamped to `[0,1]`. |
| `traceUiTemplate` | string | empty | Deep-link template with a literal `{traceId}` placeholder, e.g. `http://localhost:16686/trace/{traceId}`. Empty means no "view trace" link. |

### `networks`

A list of seed `NetworkComposition` records applied on first boot only. Later
edits via `POST /api/v1/networks` win — a seed whose name already exists is not
re-applied. See [Network Composition](/concepts/groups-instances-templates/).

### `events`

A list of seed `EventChoreography` scaling overlays. Same "first-boot only"
semantics as `networks`.

## daemon.yml

Lives at `config/daemon.yml` under the Daemon install root.

### `nodeId`

| Field | Type | Default | Notes |
|---|---|---|---|
| `nodeId` | string | `node-1` | Cluster-unique. Two Daemons on the same `nodeId` is undefined behavior. |

### `advertiseAddress`

| Field | Type | Default | Notes |
|---|---|---|---|
| `advertiseAddress` | string | empty | Address the Controller and other nodes reach this Daemon at. Empty means auto-detect. |

### `controller`

| Field | Type | Default | Notes |
|---|---|---|---|
| `host` | string | `127.0.0.1` | Controller hostname/IP reachable from this Daemon. |
| `grpcPort` | int | `9090` | Controller gRPC port. |

### `health`

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `true` | Local readiness/liveness HTTP endpoint. |
| `bindAddress` | string | `127.0.0.1` | Bind locally; expose to systemd/k8s only. |
| `port` | int | `9091` | |

### `security`

| Field | Type | Default | Notes |
|---|---|---|---|
| `certificateDir` | string | `config/security` | mTLS material. Bootstrap writes the Daemon cert, key, and CA cert here. |
| `joinToken` | string | empty | One-time bootstrap credential. The first successful registration clears it. When no cert exists and `joinToken` is blank, the Daemon refuses to start. |

### `instances`

| Field | Type | Default | Notes |
|---|---|---|---|
| `directory` | string | `instances` | Per-instance working directories. |
| `shutdownTimeoutSeconds` | int | `30` | Graceful-stop budget before forced kill. |
| `killTimeoutSeconds` | int | `10` | Time after `SIGTERM` before `SIGKILL`. |
| `logRingBufferLines` | int | `500` | Console buffer per instance. |
| `maxConsoleOutputLinesPerSecond` | int | `200` | Per-instance console flood cap. |

### `resources`

| Field | Type | Default | Notes |
|---|---|---|---|
| `maxMemoryMb` | long | `0` | `0` auto-detects 80% of total physical memory. Set explicitly to cap how much the Daemon will admit. |

### `logging`

Same shape as the Controller: `level` (default `INFO`), `format` (default
`HUMAN`).

### `reconnect`

| Field | Type | Default | Notes |
|---|---|---|---|
| `initialDelayMs` | long | `1000` | First retry delay after gRPC stream loss. |
| `maxDelayMs` | long | `60000` | Cap for exponential backoff. |
| `multiplier` | double | `2.0` | Backoff multiplier. |

### `modules.signing`

Daemon-side platform-module signing policy. Defaults match a development
cluster.

| Field | Type | Default | Notes |
|---|---|---|---|
| `required` | bool | `false` | Production should set `true`. |
| `mode` | enum | `COSIGN_BUNDLE` | `KEYED` for a Base64 `.sig`; `COSIGN_BUNDLE` for a `.cosign.bundle`. Note this default differs from the Controller's `KEYED`. |
| `trustRoot` | string | empty | PEM bundle matching `mode`. |

### `telemetry`

Mirrors the Controller. Disabled by default.

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `false` | |
| `otlpEndpoint` | string | `http://localhost:4317` | |
| `serviceName` | string | `prexorcloud-daemon` | |
| `samplerRatio` | double | `1.0` | Clamped to `[0,1]`. Keep aligned with the Controller so a sampled trace stays sampled across the hop. |

### `labels`

Free-form key-value pairs. Groups target nodes via `placement.nodeSelector`.

```yaml
labels:
  region: "eu-west"
  tier: "dedicated"
  hardware: "ryzen-9950x"
```

## Environment variables

PrexorCloud does not read `PREXORCLOUD_*` env vars into the config records. The
loader parses YAML only — there is no env-var override layer for `http.host`,
`database.uri`, and so on. Put those values in `controller.yml` /
`daemon.yml`. The env vars that do exist fall into three groups.

### Compose stack (substituted by Docker Compose, not by the app)

These are read by `deploy/compose/compose.yml` to template the stack, not by
the Controller or Daemon:

| Variable | Default | Purpose |
|---|---|---|
| `PREXORCLOUD_CONTROLLER_IMAGE` | `…/prexorcloud-controller:latest` | Controller image pin. |
| `PREXORCLOUD_DAEMON_IMAGE` | `…/prexorcloud-daemon:latest` | Daemon image pin. |
| `PREXORCLOUD_DASHBOARD_IMAGE` | `…/prexorcloud-dashboard:latest` | Dashboard image pin. |
| `PREXORCLOUD_CONTROLLER_HEAP` | `1g` | Controller `-Xmx`. |
| `PREXORCLOUD_DAEMON_HEAP` | `512m` | Daemon `-Xmx`. |
| `PREXORCLOUD_HTTP_PORT` | `8080` | Host port mapped to the Controller HTTP port. |
| `PREXORCLOUD_GRPC_PORT` | `9090` | Host port mapped to the Controller gRPC port. |
| `PREXORCLOUD_DASHBOARD_PORT` | `3000` | Host port mapped to the dashboard. |

The compose stack mounts `controller.yml` and `daemon.yml` read-only into the
containers. Secrets (`security.jwtSecret`, `security.initialAdminPassword`,
`database.uri`, `redis.uri`) belong in those files; `.env.example` lists them
only as a pointer.

### Per-instance (injected by the Daemon into each MC server process)

Read by the bundled plugin inside each instance JVM:

| Variable | Purpose |
|---|---|
| `CLOUD_INSTANCE_ID` | Instance identifier. |
| `CLOUD_GROUP` | Group name. |
| `CLOUD_PORT` | Assigned port. |
| `CLOUD_NODE_ID` | Host node identifier. |
| `CLOUD_CONTROLLER_URL` | Controller REST API URL (set only when non-blank). |
| `CLOUD_PLUGIN_TOKEN` | Short-lived plugin auth token (set only when present). |
| `CLOUD_CPU_RESERVATION` | CPU reservation hint. |
| `CLOUD_DISK_RESERVATION_MB` | Disk reservation hint, MB. |

### CLI (`prexorctl`)

| Variable | Purpose |
|---|---|
| `PREXOR_CONTROLLER` | Default `--controller` URL. |
| `PREXOR_TOKEN` | Default auth token. |
| `PREXOR_CONTEXT` | Default context name. |
| `PREXOR_OUTPUT=json` | JSON output across all commands. |
| `PREXOR_NO_BROWSER` | Skip the browser launch in `setup`. |
| `NO_COLOR` | Disable colored output. |

## Validation

`ConfigValidator.validate(...)` runs at Controller startup. It collects every
error before failing, so one pass fixes all of them, then throws
`IllegalStateException` (the process exits non-zero before binding ports). The
checks:

- `runtime.profile` must be `development` or `production`.
- `http.port` and `grpc.port` must each be `1..65535`.
- `http.port` and `grpc.port` must differ.
- `security.jwtExpirationMinutes` must be `>= 1` and not exceed `43200`.
- `security.rateLimiting.perIpPerMinute` and `perUserPerMinute` must be `>= 1`.
- `security.lockout.maxAttempts`, `windowSeconds`, `lockoutSeconds` must be `>= 1`.
- `database.uri` must not be blank.
- `redis.uri` must not be blank when the `redis` block is present.
- `redis` must be set when `runtime.profile=production`.
- When module signing is required (explicit `true`, or `production` default),
  `modules.signing.trustRoot` must be set.
- `modules.signing.rekor.policy=REQUIRE_SET` requires
  `modules.signing.mode=COSIGN_BUNDLE` and a non-blank
  `modules.signing.rekor.publicKey`.
- `scheduler.evaluationIntervalSeconds` must be `>= 1`.
- `heartbeat.intervalMs` must be `>= 1000`; `heartbeat.missedThreshold` `>= 1`.
- Every `cors.allowedOrigins` entry must start with `http://` or `https://`.

Many of these are also enforced by the record defaults (a `<= 0` numeric
resolves to the default before validation sees it), so in practice the
validator catches profile/cross-field mistakes — port collisions, a production
profile without Redis, a Rekor policy without a key.

## Roles (`roles.yml`)

The Controller seeds three roles on first boot:

- `ADMIN` — `["*"]`, all permissions.
- `OPERATOR` — node/group/instance/template/module/catalog/audit/metrics view + mutate.
- `VIEWER` — read-only across the same surfaces.

Custom roles are editable via `prexorctl role` or the `roles` Mongo collection.

## Next up

- [Production Checklist](/operations/production-checklist/) — launch hardening
- [HA Setup](/operations/ha-setup/) — multi-controller wiring
- [Storage Schema](/internals/storage-schema/) — Mongo collections + Redis keys
