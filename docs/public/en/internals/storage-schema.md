---
title: Storage Schema
description: Every MongoDB collection and Valkey key family the controller writes — owner, shape, durability class, and TTL.
---

PrexorCloud splits state across three memory tiers (see
[Architecture](/internals/architecture/#three-memory-tiers)). This
page is the catalogue: every MongoDB collection, every Valkey key
family, who owns it, and how long it lives.

## What you'll learn

- Every Mongo collection the controller persists
- Every Valkey key prefix and its TTL
- What process memory holds and how it's rebuilt on restart
- The decision rule for adding new state

## MongoDB collections

| Collection | Owner | Purpose |
|---|---|---|
| `users` | `MongoUserStore` | Local user accounts (username, password hash, email, role, MC link, avatar). Sparse-unique index on `email`. |
| `roles` | `MongoRoleStore` | Roles and permission lists. ADMIN / OPERATOR / VIEWER seeded on first boot from `defaults/roles.yml`. |
| `groups` | `MongoGroupStore` | Group configuration (platform, version, scaling, templates, MC config). |
| `templates` | `TemplateManager` (file-backed under `templates/`) and `MongoTemplateStore` (metadata) | Named bundles of files applied to instances during materialisation. |
| `catalog` | `MongoCatalogStore` | Available platform jars (Paper 1.21.4, Velocity 3.4.0, etc.). Maps platform + version to download URL + sha256. |
| `deployments` | `MongoDeploymentStore` | Active and historical rolling-restart records, including pause / resume / rollback state. |
| `instance_composition_plans` | `CompositionPlanStore` | Per-instance plans: template chain hashes, runtime jar reference, workload extensions, env / config patches, plugin token. Hash-keyed; replayed by daemons on reconnect. |
| `workflow_intents` | `WorkflowStateStore` | Durable workflow intent: pending start retries, drains, healings, deployments, recoverable starts. Each entry references a lease scope; reads on resume require holding the matching lease. |
| `module_packages` | `PlatformModuleStore` | Platform module package metadata (id, version, hash, manifest, signature ref). |
| `mod_<moduleId>_*` | per-module `ModuleDataStore` | Per-module document storage. Collection prefix isolates modules. |
| `audit_log` | `AuditRepository` | Audit log of state-changing API operations. TTL index on `createdAt`, driven by `scheduler.auditRetentionDays` (default 90). |
| `crashes` | `CrashStore` | Crash records with classification, exit code, console tail. |
| `recovery` | recovery harness | Recovery metadata used by `RestoreExecutor`. |
| `backups` | `BackupCatalog` | Backup manifests. The on-disk artefact under `backups/` is the source of truth; this collection is a searchable index. |
| `networks` | `MongoNetworkStore` | Network Composition records (lobbyGroup, fallbackGroups, kickMessage). |
| `player_journey` | `cloud-module-player-journey` | Append-only per-player event log: PLAYER_CONNECTED / PLAYER_TRANSFER / PLAYER_DISCONNECTED / INSTANCE_CRASHED. Owned by the bundled module. |

### Indexes

The controller creates required indexes on startup. The notable ones:

| Collection | Index | Purpose |
|---|---|---|
| `users` | `{ username: 1 }` unique | Login lookup. |
| `users` | `{ email: 1 }` sparse-unique | Password reset by email. |
| `audit_log` | `{ createdAt: 1 }` TTL `auditRetentionDays` | Auto-prune. |
| `audit_log` | `{ "actor.username": 1, createdAt: -1 }` | "Who did what" queries. |
| `crashes` | `{ groupId: 1, createdAt: -1 }` | Per-group crash history. |
| `instance_composition_plans` | `{ planHash: 1 }` unique | Idempotent dispatch. |
| `workflow_intents` | `{ leaseScope: 1, status: 1 }` | Lease-aware resumption. |

If you killed a controller mid-startup, re-run; index creation is
idempotent.

### What MongoDB does *not* own

We deliberately do not move these to Mongo:

- **Leases / fencing tokens.** Mongo has no native TTL on records
  keyed for fast contention. Doing this in Mongo would require
  `findOneAndUpdate` with timestamp checks on every contention; it
  works but is slower and noisier than Redis primitives.
- **JWT / workload-credential revocation.** TTL-based; needs cheap
  `EXPIRE`.
- **SSE replay buffers.** High write rate, bounded retention, no need
  for query.
- **Rate-limit counters.** High write rate, sliding windows.
- **Per-module Redis storage.** Modules that *want* Redis-shape
  primitives use the per-module Redis prefix; we do not back-fill it
  with Mongo.

## Valkey key families

All keys are prefixed `prexor:v1:`. The version suffix is reserved
for forward compatibility — every read and write today uses this
namespace.

| Key family | Prefix | TTL / retention | Purpose |
|---|---|---|---|
| Lease ownership | `prexor:v1:lease:` | scheduler-configured lease TTL (default = `evaluationIntervalSeconds × 2`) | Active-active mutation gating. Each lease holds the controller UUID and the fencing token. |
| Lease fencing tokens | `prexor:v1:lease-token:` | no TTL | Monotonic per-scope counters. Incremented on every acquire. |
| Runtime snapshots | `prexor:v1:node:` / `instance:` / `player:` | no TTL; removed on state cleanup | Compact ownership records used by other controllers to route commands to the right session. |
| Plugin tokens | `prexor:v1:plugintoken:` | 15 minutes | Per-instance bearer tokens (`ptk_` prefix). Issued at start, refreshed by the running plugin, revoked on stop. |
| JWT revocation | `prexor:v1:jwt:revoked:` | remaining JWT lifetime | Revocations from logout / change-password / explicit revoke. |
| Rate limits | `prexor:v1:ratelimit:` | 60s sliding window | Per-IP and per-user counters. |
| Console flood windows | `prexor:v1:console:window:` | 2× active flood window | Suppresses console event flooding. |
| Workload replay protection | `prexor:v1:workloadseq:` | workload-token lifetime (15 min) | Sequence windows for plugin-token replay rejection. |
| SSE tickets | `prexor:v1:sse:ticket:` | 30s | Short-lived auth tickets exchanged from a JWT. |
| SSE replay buffer | `prexor:v1:sse:sequence` / `replay` | no TTL; bounded by replay trim | Per-stream sequence counter and replay window. |
| Module Redis storage | `prexor:v1:platform:<moduleId>:` | module-managed | Per-module key space. Modules MUST scope their keys under their own `moduleId` — controller does not enforce this; the prefix is the contract. |
| Login attempts / locks | `prexor:v1:login:fail:` / `prexor:v1:login:lock:` | failure-window / lockout-duration | Account-lockout counters and active locks. |
| Password reset tokens | `prexor:v1:pwreset:` | 30 minutes default; deleted on consume | Single-use email-token state. |

The full list is also exposed live by a running controller via
`GET /api/v1/system/redis/schema` (requires `system.settings`).

### Eviction policy

Recommended Valkey config for a PrexorCloud-only instance:

```text
maxmemory <appropriate-size>
maxmemory-policy volatile-lru
appendonly yes
appendfsync everysec
```

- All PrexorCloud keys carry TTLs, so `volatile-lru` evicts
  oldest-TTL'd keys first.
- **Never** use `noeviction` (writes will fail) or `allkeys-*` (will
  evict no-TTL operator keys you might be sharing).

If Valkey is shared with other workloads, the controller still works
but operators who run `FLUSHALL` will lose JWT revocations, lockouts,
leases, and replay buffers in one go. Document the policy.

## Process memory

| Component | What it holds | Rebuilt how |
|---|---|---|
| `ClusterState` | Live model: nodes, instances, players, group memberships, plugin tokens issued this run. | On controller start: Mongo for groups + templates + composition plans + crashes; daemon reconnect for live node + instance state. |
| `EventBus` | In-process pub-sub handler list. | N/A (per-process). |
| `NodeSessionManager` | Per-node gRPC stream handles. | Daemons reconnect on controller restart; sessions rebuild. |
| `ConsoleBuffer` | Ring buffer per instance of recent console lines. | Lost on restart; daemons re-stream new console output. |
| `RingBufferLogAppender` | Recent controller log lines for `prexorctl logs controller`. | Lost on restart. |
| `DaemonLogStore` | Recent daemon log lines, per node. | Lost on restart; daemons stream fresh on reconnect. |
| `CrashLoopDetector` | In-memory sliding window of recent crashes per group. | Rebuilt from `crashes` collection on start. |
| `CapabilityRegistry` | Resolved capability handles + dynamic-handle proxy cache. | Re-registered as modules load. |
| `ExtensionRegistry` | Resolved workload extension manifests. | Re-registered as modules load. |
| `ModuleFrontendManager` | Loaded frontend manifests + asset paths. | Re-registered as modules load. |

## Backup scope

`prexorctl backup create` produces a single tarball that includes:

- The full MongoDB dump (every collection above).
- The on-disk filesystem state under `templates/`, `cloud-modules/`,
  `backups/<manifest>`, `config/` (redacted secrets).
- Optionally the Valkey RDB snapshot.
- A manifest with backup metadata.

Valkey is **optional** in the backup scope. Coordination state is not
durable by definition — the only thing in Valkey worth restoring is
rate-limit history, which is not worth the complexity. The `restore`
path rebuilds the cluster from MongoDB; Valkey starts cold and
refills as the cluster runs.

See [Backups and DR](/operations/backups-and-dr/).

## Adding new state

Walk down this checklist:

1. **Does it have to survive a full restart of every controller?**
   → MongoDB.
2. **Is it ephemeral but cluster-shared (TTL-driven, lease-shaped,
   rate-limited)?** → Valkey.
3. **Is it derivable from MongoDB + live gRPC reconciliation in <5s?**
   → process memory.
4. **None of the above?** Walk through the design with whoever built
   `ClusterState`. You probably want a different abstraction.

There is exactly one rule that overrides the checklist: **never split
a single piece of conceptual state across two stores.** A workflow
intent lives in MongoDB *or* in Valkey, never half-and-half. We have
made the mistake before; we will not again.

## Why this split exists

Three observations drove it:

1. **TTL semantics are fundamental to coordination.** Leases that
   don't auto-expire are not leases; they are deadlock generators.
   Mongo's TTL index can do this but the overhead is much higher than
   Redis primitives, and the latency variance hurts contention.
2. **Replay / rate-limit shape is bursty + bounded.** Mongo handles
   bursty writes fine, but the natural queries (range over sequence,
   decrement counter) are awkward in Mongo and trivial in Redis.
3. **Durability requirements are different.** A Mongo loss is "the
   cluster is gone." A Valkey loss is "in-flight retries pause; SSE
   replay window shrinks." Splitting them lets each store focus on
   its job.

## Next up

- [Architecture](/internals/architecture/) — the three-tier model and lease semantics in depth
- [Configuration Reference](/operations/configuration/) — every key under `database`, `redis`, `scheduler`
- [Backups and DR](/operations/backups-and-dr/) — what restore preserves
