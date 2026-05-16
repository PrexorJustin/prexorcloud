---
title: Configuration Reference
description: Every key in controller.yml and daemon.yml — type, default, impact, and the production overrides that matter.
---

The controller and daemon both read a single YAML file at startup.
Every key is mapped to a Java record with a compact constructor that
applies defaults — if you omit a key, you get the default below. This
page is the canonical reference. When in doubt, the records under
`java/cloud-controller/.../config/` and
`java/cloud-daemon/.../config/` are the ground truth.

## What you'll learn

- Every controller and daemon configuration key, its type, default, and effect
- Which keys are required for production and which are safe defaults
- How environment variables override config for containerised deployments
- The full Valkey key namespace the controller writes under

## controller.yml

Lives at `config/controller.yml` under the install root (default
`/etc/prexorcloud/`). Reload requires a controller restart — there is
no signal-based hot reload.

### `uuid`

| Field | Type | Default | Required |
|---|---|---|---|
| `uuid` | string | auto-generated | no |

Stable controller identifier persisted across restarts. The first boot
generates one and writes it back into `controller.yml`. In HA, every
controller must have its own.

### `http`

| Field | Type | Default | Notes |
|---|---|---|---|
| `host` | string | `0.0.0.0` | Bind address. Use `127.0.0.1` if a reverse proxy on the same host is the only listener you want. |
| `port` | int | `8080` | REST + SSE + dashboard. |
| `cors.allowedOrigins` | list&lt;string&gt; | `["http://localhost:3000"]` | Origins allowed for browser clients. The dashboard origin must be listed when served from a different domain. |

### `grpc`

| Field | Type | Default | Notes |
|---|---|---|---|
| `host` | string | `0.0.0.0` | Bind address for daemon connections. |
| `port` | int | `9090` | gRPC port; mTLS terminated by the controller. |

### `network`

| Field | Type | Default | Notes |
|---|---|---|---|
| `allowedSubnets` | list&lt;string&gt; | `["0.0.0.0/0", "::/0"]` | CIDR allowlist for HTTP clients. **Lock down for production.** Empty list rejects all clients. Behind a reverse proxy, set `http.trustedProxyCidrs` so the real client IP is evaluated. |

### `database` (MongoDB)

| Field | Type | Default | Required |
|---|---|---|---|
| `uri` | string | `mongodb://localhost:27017` | yes |
| `database` | string | `prexorcloud` | yes |

PrexorCloud does not embed Mongo. The reference Compose stack runs
Mongo as its own service. Replica-set URIs are supported and
recommended for HA: `mongodb://h1,h2,h3/prexorcloud?replicaSet=rs0&w=majority`.

### `redis`

| Field | Type | Default | Required |
|---|---|---|---|
| `uri` | string | `redis://localhost:6379` | yes (production) |

Redis or Valkey, both speak the same protocol. The block is nullable
in development; production refuses to start without it.
`rediss://...` enables TLS to the coordination store.

All keys live under `prexor:v1:`. The version suffix is reserved for
forward-compatibility — every read and write today uses this
namespace. Full key map:

| Family | Prefix | TTL / retention |
|---|---|---|
| Lease ownership | `prexor:v1:lease:` | scheduler-configured lease TTL |
| Lease fencing tokens | `prexor:v1:lease-token:` | no TTL |
| Runtime snapshots | `prexor:v1:node:` / `instance:` / `player:` | no TTL; removed on cleanup |
| Plugin tokens | `prexor:v1:plugintoken:` | 15 minutes |
| JWT revocation | `prexor:v1:jwt:revoked:` | remaining JWT lifetime |
| Rate limits | `prexor:v1:ratelimit:` | 60 seconds |
| Console flood windows | `prexor:v1:console:window:` | 2× active flood window |
| Workload replay protection | `prexor:v1:workloadseq:` | workload-token lifetime, 15 minutes |
| SSE tickets | `prexor:v1:sse:ticket:` | 30 seconds |
| SSE replay buffer | `prexor:v1:sse:sequence` / `replay` | bounded by replay trim |
| Module Redis storage | `prexor:v1:platform:<moduleId>:` | module-managed |
| Login attempts / locks | `prexor:v1:login:fail:` / `prexor:v1:login:lock:` | failure-window / lockout-duration |
| Password reset tokens | `prexor:v1:pwreset:` | 30 minutes; deleted on consume |

The same map is exposed live at `GET /api/v1/system/redis/schema` for
operators with `system.settings` permission.

### `runtime`

| Field | Type | Default | Notes |
|---|---|---|---|
| `profile` | string | `development` | One of `development`, `production`. Production requires Redis at startup; the wiring graph swaps in real coordination accessors for leases, JWT revocation, login lockouts, SSE replay, rate limits. |

### `logging`

| Field | Type | Default | Notes |
|---|---|---|---|
| `level` | string | `INFO` | Standard SLF4J levels: `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE`. |
| `format` | string | `HUMAN` | `HUMAN` for single-line text. `JSON` for line-delimited structured JSON via `JsonLogEncoder`. |

### `scheduler`

| Field | Type | Default | Notes |
|---|---|---|---|
| `evaluationIntervalSeconds` | int | `15` | Tick frequency. New placement decisions and scaling evaluations happen each tick. |
| `scalingCooldownSeconds` | int | `60` | Default per-group cooldown after a scale event. Per-group config can override. |
| `nodeTimeoutSeconds` | int | `90` | Time without heartbeat before a node is marked offline. |
| `auditRetentionDays` | int | `90` | TTL on the `audit_log` collection. Mongo's TTL index is keyed off `createdAt`. |

### `heartbeat`

| Field | Type | Default | Notes |
|---|---|---|---|
| `intervalMs` | long | `30000` | Daemon → controller heartbeat cadence. |
| `missedThreshold` | int | `3` | Consecutive misses before the node session is considered stale. |

### `security`

| Field | Type | Default | Notes |
|---|---|---|---|
| `jwtSecret` | string | empty (auto-generated) | Base64 ≥ 32 bytes. Auto-generated on first boot is fine for dev; **production should set a managed value.** |
| `jwtExpirationMinutes` | int | `1440` | 24 hours. |
| `initialAdminPassword` | string | empty (auto-generated) | First-boot bootstrap. Written to `config/.initial-admin-password` mode 0600 if generated. |
| `jwtPreviousSecrets` | list&lt;string&gt; | `[]` | Old secrets accepted during rotation until tokens expire naturally. |
| `rateLimiting.perIpPerMinute` | int | `100` | Per-IP REST rate limit. |
| `rateLimiting.perUserPerMinute` | int | `300` | Per-user REST rate limit. |
| `rateLimiting.failOpenOnRedisError` | bool | `false` | Whether to allow traffic when Valkey is unreachable. Default closed. |
| `lockout.enabled` | bool | `true` | Account lockout on failed logins. |
| `lockout.maxAttempts` | int | `5` | Failed logins before a lock. |
| `lockout.windowSeconds` | int | `900` | Sliding failure window. |
| `lockout.lockoutSeconds` | int | `900` | Lock duration after threshold. |
| `passwordReset.enabled` | bool | `false` | Off by default. When false, `/api/v1/auth/password-reset/*` returns 404. |
| `passwordReset.tokenTtlMinutes` | int | `30` | Single-use token lifetime. |
| `passwordReset.resetUrlBase` | string | empty | Dashboard base URL. Mailer appends `/auth/reset-password?token=...`. |
| `passwordReset.smtp.host` | string | empty | When blank, `LogMailer` writes the reset link to the controller log instead of sending email. |
| `passwordReset.smtp.port` | int | `587` | |
| `passwordReset.smtp.startTls` | bool | `true` | |
| `passwordReset.smtp.implicitTls` | bool | `false` | |
| `passwordReset.smtp.username` | string | empty | |
| `passwordReset.smtp.password` | string | empty | |
| `passwordReset.smtp.from` | string | empty | Defaults to `no-reply@<host>` when blank. |

In production profile, lockout + password-reset state lives in Valkey
and is shared across controllers.

### `crashes`

| Field | Type | Default | Notes |
|---|---|---|---|
| `ringBufferSize` | int | `500` | Per-process in-memory ring of recent crashes for the dashboard. |
| `crashLoopThreshold` | int | `3` | Crashes within window before a group is paused. |
| `crashLoopWindowSeconds` | int | `300` | Sliding window. |

### `metrics`

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `true` | Exposes `/metrics` (Prometheus exposition). Gate access via reverse-proxy ACL. |
| `retentionHours` | int | `168` | In-process metric retention for the dashboard's mini-graphs. Independent of your Prometheus retention. |
| `collectionIntervalSeconds` | int | `30` | Internal sampling cadence for derived gauges. |

### `modules`

| Field | Type | Default | Notes |
|---|---|---|---|
| `directory` | string | `modules` | Directory of installed module bundles. |
| `dataDirectory` | string | `cloud-modules/data` | Per-module on-disk storage root. |
| `hotReload` | bool | `true` | File-watcher reload on bundle change. |
| `signing.required` | bool | profile-default | True in production, false in development. Production fails-closed when a signature cannot be verified. |
| `signing.mode` | enum | `KEYED` | `KEYED` for sidecar `.sig`; `COSIGN_BUNDLE` for `cosign sign-blob --bundle`. |
| `signing.trustRoot` | string | empty | PEM bundle. PUBLIC KEY blocks for raw-keyed; CERTIFICATE blocks for cosign-with-internal-CA. |
| `signing.allowUnsignedDevelopment` | bool | `true` | Lets dev profile install unsigned bundles even if `required=true` is set elsewhere. |
| `signing.rekor.policy` | enum | `DISABLED` | `REQUIRE_SET` enforces offline Rekor SET verification — bundle must include a `SignedEntryTimestamp`. |
| `signing.rekor.publicKey` | string | empty | PEM bundle for the Rekor public key. Required when policy = `REQUIRE_SET`. |

See [Cosign Pipeline](/internals/cosign-pipeline/) for the verification
flow.

### `maintenance`

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `false` | Global maintenance mode. Overrides per-group settings. |
| `message` | string | network-default | Surfaced to dashboards and proxy plugins. |
| `bypass` | list&lt;string&gt; | `[]` | Group names exempt from maintenance. |

### `dashboard`

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `true` | Serves the bundled dashboard. |
| `path` | string | `dashboard` | On-disk directory. |

### `backup`

| Field | Type | Default | Notes |
|---|---|---|---|
| `directory` | string | `backups` | Backup root, relative to install root. |
| `retentionCount` | int | `10` | Manifests kept by the catalog. Older manifests are pruned by `prexorctl backup prune`. |

### `networks`

A list of seed `NetworkComposition` records applied on first boot.
Subsequent edits via REST (`/api/v1/networks`) win — seeds with names
that already exist are not re-applied. See
[Network Composition](/concepts/groups-instances-templates/).

### `events`

A list of seed `EventChoreography` cron-shaped scaling overlays. Same
"first-boot only" semantics as `networks`.

## daemon.yml

Lives at `config/daemon.yml` under the daemon install root.

### `nodeId`

| Field | Type | Default | Required |
|---|---|---|---|
| `nodeId` | string | `node-1` | yes |

Cluster-unique. Setting two daemons to the same `nodeId` is undefined
behaviour and likely to produce certificate-renewal contention.

### `controller`

| Field | Type | Default | Notes |
|---|---|---|---|
| `host` | string | `127.0.0.1` | Controller hostname / IP reachable from this daemon. |
| `grpcPort` | int | `9090` | Controller gRPC port. |

### `health`

| Field | Type | Default | Notes |
|---|---|---|---|
| `enabled` | bool | `true` | Local readiness/liveness HTTP endpoint. |
| `bindAddress` | string | `127.0.0.1` | Bind locally; expose to systemd / k8s only. |
| `port` | int | `9091` | |

### `security`

| Field | Type | Default | Notes |
|---|---|---|---|
| `certificateDir` | string | `config/security` | mTLS material lives here. The bootstrap flow writes `daemon.crt`, `daemon.key`, `ca.crt`. |
| `joinToken` | string | empty | One-time bootstrap credential. The first successful registration deletes this from config. |

### `instances`

| Field | Type | Default | Notes |
|---|---|---|---|
| `directory` | string | `instances` | Per-instance working directories. |
| `shutdownTimeoutSeconds` | int | `30` | Time given to a graceful stop before forced kill. |
| `killTimeoutSeconds` | int | `10` | Time after `SIGTERM` before `SIGKILL`. |
| `logRingBufferLines` | int | `500` | Console buffer per instance. |

### `resources`

| Field | Type | Default | Notes |
|---|---|---|---|
| `maxMemoryMb` | int | `0` | `0` = auto-detect from cgroup / system. Set explicitly to cap how much the daemon will admit. |

### `logging`

Same shape as the controller: `level`, `format`.

### `labels`

Free-form key-value pairs. Groups can target nodes via
`placement.nodeSelector`. Common patterns:

```yaml
labels:
  region: "eu-west"
  tier: "dedicated"
  hardware: "ryzen-9950x"
```

### `reconnect`

| Field | Type | Default | Notes |
|---|---|---|---|
| `initialDelayMs` | long | `1000` | First retry delay after gRPC stream loss. |
| `maxDelayMs` | long | `60000` | Cap for exponential backoff. |
| `multiplier` | double | `2.0` | Backoff multiplier. |

## Environment variables

All values can be overridden by environment variables, mostly used
inside the Compose stack.

### Controller

| Variable | Purpose |
|---|---|
| `PREXORCLOUD_HTTP_HOST` | Override `http.host`. |
| `PREXORCLOUD_GRPC_HOST` | Override `grpc.host`. |
| `PREXORCLOUD_DATABASE_URI` | Mongo URI. |
| `PREXORCLOUD_REDIS_URI` | Valkey/Redis URI. |
| `PREXORCLOUD_RUNTIME_PROFILE` | `development` or `production`. |
| `PREXORCLOUD_SECURITY_JWT_SECRET` | JWT signing secret. |

### Daemon

| Variable | Purpose |
|---|---|
| `PREXORCLOUD_CONTROLLER_HOST` | Controller host. |
| `PREXORCLOUD_CONTROLLER_GRPC_PORT` | Controller gRPC port. |
| `PREXORCLOUD_NODE_ID` | Override `nodeId`. |
| `PREXORCLOUD_SECURITY_JOIN_TOKEN` | One-shot join token; cleared after first registration. |

### Per-instance (injected by the daemon)

These are read by the bundled `prexor-plugin` inside each MC server JVM:

| Variable | Purpose |
|---|---|
| `CLOUD_INSTANCE_ID` | Instance identifier |
| `CLOUD_GROUP` | Group name |
| `CLOUD_PORT` | Assigned port |
| `CLOUD_NODE_ID` | Host node identifier |
| `CLOUD_CONTROLLER_URL` | Controller REST API URL |

### CLI

| Variable | Purpose |
|---|---|
| `PREXOR_CONTROLLER` | Default `--controller` URL. |
| `PREXOR_TOKEN` | Default auth token. |
| `PREXOR_OUTPUT=json` | JSON output across all commands. |

## Roles (`roles.yml`)

The controller seeds three roles on first boot from
`defaults/roles.yml`:

- `ADMIN` — `["*"]`. All permissions.
- `OPERATOR` — node / group / instance / template / module / catalog / audit / metrics view + mutate.
- `VIEWER` — read-only across the same surfaces.

Custom roles edit-able via `prexorctl role` or directly in the
`roles` Mongo collection.

## Validation

`ConfigValidator` runs at startup and refuses to boot in production
when:

- `redis` is unset.
- `modules.signing.required=true` but `trustRoot` is empty.
- `modules.signing.rekor.policy=REQUIRE_SET` but `rekor.publicKey` is empty
  or `mode != COSIGN_BUNDLE`.

A misconfigured production controller logs the full validation report
and exits non-zero before binding ports.

## Next up

- [Production Checklist](/operations/production-checklist/) — the launch hardening list
- [HA Setup](/operations/ha-setup/) — multi-controller wiring
- [Storage Schema](/internals/storage-schema/) — Mongo collections + Valkey keys in depth
