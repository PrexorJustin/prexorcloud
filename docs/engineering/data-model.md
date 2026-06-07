# Data model: what's stored where

PrexorCloud has three distinct memory tiers. Knowing which tier a piece of state lives in is the difference between "this survives a controller crash" and "this evaporates on restart." This document is the authoritative reference.

## The three tiers

### MongoDB (durable)

Everything that must survive a full restart of every controller, every daemon, and every coordination-store node. If MongoDB is gone, the cluster is gone.

### Valkey / Redis-protocol (coordination)

Everything ephemeral but cluster-shared. Leases, fencing tokens, replay buffers, rate-limit windows, JWT revocation. If Valkey is gone, in-flight workflows pause and SSE replay windows shrink, but no operator-meaningful data is lost — recovery is automatic when Valkey returns.

### Process memory (transient)

`ClusterState` and friends. Authoritative live model of nodes, instances, players. Reconstructed from MongoDB + gRPC reconciliation on controller start. Lost on controller restart, then rebuilt.

## What MongoDB owns

| Collection | Owner | Purpose |
|---|---|---|
| `users` | `MongoUserStore` | Local user accounts (username, password hash, email, role, MC link, avatar). Sparse-unique index on `email`. |
| `roles` | `MongoRoleStore` | Roles + permission lists. ADMIN / OPERATOR / VIEWER seeded on first boot from `defaults/roles.yml`. |
| `groups` | `MongoGroupStore` | Group configuration (platform, version, scaling, templates, MC config). |
| `templates` | `TemplateManager` (file-backed under `templates/`) and `MongoTemplateStore` (metadata) | Named bundles of files applied to instances during materialisation. |
| `catalog` | `MongoCatalogStore` | Available platform JARs (Paper 1.21.4, Velocity 3.4.0, etc.). Maps platform + version to download URL + sha256. |
| `deployments` | `MongoDeploymentStore` | Active and historical rolling-restart records, including pause / resume / rollback state. |
| `instance_composition_plans` | `CompositionPlanStore` | Per-instance plans: template chain hashes, runtime jar reference, workload extensions, env / config patches, plugin token. Hash-keyed. Replayed by daemons on reconnect. |
| `workflow_intents` | `WorkflowStateStore` | Durable workflow intent: pending start retries, drains, healings, deployments, recoverable starts. Each entry references a lease scope; reads on resume require holding the matching lease. |
| `module_packages` | `PlatformModuleStore` | Platform module package metadata (id, version, hash, manifest, signature ref). |
| `mod_<moduleId>_*` | per-module `ModuleDataStore` | Per-module document storage. Collection prefix isolates modules. |
| `audit` | `AuditRepository` | Audit log of state-changing API operations. |
| `crashes` | `CrashStore` | Crash records with classification, exit code, console tail. |
| `recovery` | recovery harness | Recovery metadata used by `RestoreExecutor`. |
| `backups` | `BackupCatalog` | Backup manifests. The on-disk artefact under `backups/` is the source of truth; this collection is a searchable index. |
| `networks` | `MongoNetworkStore` | Network Composition records (lobbyGroup, fallbackGroups, kickMessage). |
| `player_journey` | `PlayerJourneyService` | Append-only per-player event log: PLAYER_CONNECTED / PLAYER_TRANSFER / PLAYER_DISCONNECTED / INSTANCE_CRASHED. |

### What MongoDB does *not* own

We deliberately do not move these to Mongo:

- **Leases / fencing tokens.** Mongo has no native TTL on records keyed for fast contention. Doing this in Mongo would require `findOneAndUpdate` with timestamp checks on every contention; it works but it is slower and noisier than Valkey primitives.
- **JWT / workload-credential revocation.** TTL-based; needs cheap `EXPIRE`. Same reason as leases.
- **SSE replay buffers.** High write rate, bounded retention, no need for query.
- **Rate-limit counters.** High write rate, sliding windows.
- **Per-module Redis storage.** Modules that *want* Redis-shape primitives use the per-module Redis prefix; we do not back-fill it with Mongo.

## What Valkey owns

All keys are prefixed `prexor:v1:`. The version suffix is reserved for forward compatibility.

| Key family | Prefix | TTL / retention | Purpose |
|---|---|---|---|
| Lease ownership | `prexor:v1:lease:` | scheduler-configured lease TTL (default = scheduler interval × 2) | Active-active mutation gating. Each lease holds the controller UUID and the fencing token. |
| Lease fencing tokens | `prexor:v1:lease-token:` | no TTL | Monotonic per-scope counters. Incremented on every acquire. |
| Runtime snapshots | `prexor:v1:node:` / `instance:` / `player:` | no TTL; removed on state cleanup | Compact ownership records used by other controllers to route commands to the right session. |
| Plugin tokens | `prexor:v1:plugintoken:` | 15 minutes by default | Per-instance bearer tokens (`ptk_` prefix). Issued at start, refreshed by the running plugin, revoked on stop. |
| JWT revocation | `prexor:v1:jwt:revoked:` | remaining JWT lifetime | Revocations from logout / change-password / explicit revoke. |
| Rate limits | `prexor:v1:ratelimit:` | 60s sliding window | Per-IP and per-user counters. |
| Console flood windows | `prexor:v1:console:window:` | 2× active flood window | Suppresses console event flooding. |
| Workload replay protection | `prexor:v1:workloadseq:` | workload token lifetime (15 min default) | Sequence windows for plugin-token replay rejection. |
| SSE tickets | `prexor:v1:sse:ticket:` | 30s | Short-lived auth tickets exchanged from a JWT. |
| SSE replay buffer | `prexor:v1:sse:sequence` / `replay` | no TTL; bounded by replay trim | Per-stream sequence counter and replay window. |
| Module Redis storage | `prexor:v1:platform:<moduleId>:` | module-managed | Per-module key space. Modules MUST scope their keys under their own moduleId — controller does not enforce this; the prefix is the contract. |
| Login attempts / locks | `prexor:v1:login:fail:` / `prexor:v1:login:lock:` | failure-window / lockout-duration | Account-lockout counters and active locks. |
| Password reset tokens | `prexor:v1:pwreset:` | 30 minutes default; deleted on consume | Single-use email-token state. |

The full list is also exposed on the running controller via `GET /api/v1/system/redis/schema` (requires `system.settings`).

### Why this split exists

Three observations drove it:

1. **TTL semantics are fundamental to coordination.** Leases that don't auto-expire are not leases; they are deadlock generators. Mongo's TTL index can do this but the overhead is much higher than Redis primitives, and the latency variance hurts contention.
2. **Replay / rate-limit shape is bursty + bounded.** Mongo handles bursty writes fine, but the natural queries (range over sequence, decrement counter) are awkward in Mongo and trivial in Redis.
3. **Durability requirements are different.** A Mongo loss is "the cluster is gone." A Valkey loss is "in-flight retries pause; SSE replay window shrinks." Splitting them lets each store focus on its job.

## What process memory owns

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
- The on-disk filesystem state under `templates/`, `modules/`, `backups/<manifest>`, `config/` (redacted secrets).
- A manifest with backup metadata.

Valkey is **not** in the backup scope, by design. Coordination state is not durable by definition — the only thing in Valkey worth restoring is rate-limit history, which is not worth the complexity. The `restore` path rebuilds the cluster from MongoDB; Valkey starts cold and refills as the cluster runs.

See [`runbooks/backup.md`](runbooks/backup.md) and [`runbooks/restore.md`](runbooks/restore.md).

## Disaster recovery RPO / RTO

| Tier | RPO | RTO |
|---|---|---|
| MongoDB | ≤ 1 hour (target backup interval) | 30 minutes from backup |
| Valkey | best-effort | 5 minutes (start fresh; no data restored) |
| Filesystem (templates, modules) | ≤ 24 hours (file-level backup) | 30 minutes |
| Daemon hosts | n/a (rebuilt by re-installing) | 15 minutes per host |

See [`dr.md`](dr.md).

## How the controller decides where state goes

When you add a new piece of state, work down this checklist:

1. **Does it have to survive a full restart of every controller?** → MongoDB.
2. **Is it ephemeral but cluster-shared (TTL-driven, lease-shaped, rate-limited)?** → Valkey.
3. **Is it derivable from MongoDB + live gRPC reconciliation in <5s?** → process memory.
4. **None of the above?** Walk through the design with the engineer who built `ClusterState`. You probably want a different abstraction.

There is exactly one rule that overrides the checklist: **never split a single piece of conceptual state across two stores.** A workflow intent lives in MongoDB *or* in Valkey, never half-and-half. We have made the mistake before. We will not make it again.
