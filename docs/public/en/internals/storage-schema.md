---
title: Storage schema
description: Every MongoDB collection, Redis key family, and Raft state projection the controller writes — owner, shape, durability class, and TTL.
---

PrexorCloud splits controller state across four stores: MongoDB for
durable record state, Redis for ephemeral cluster-shared coordination,
an embedded Raft control plane for cluster identity and config, and
process memory for live runtime model. This page is the catalogue:
every collection, every key family, who owns it, and how long it lives.

Every fact here is read from source: `MongoStateStore.ensureIndexes()`
and the per-subsystem Mongo stores, `RedisKeys`, `RedisRuntimeStore`,
and the Raft `ClusterControlStateMachine`.

## What you'll learn

- Every MongoDB collection the controller persists, and its indexes
- Every Redis key family and its TTL
- What the embedded Raft control plane holds
- What process memory holds and how it rebuilds on restart
- The decision rule for adding new state

## The four stores

| Store | Holds | Durability |
|---|---|---|
| MongoDB | Record state: users, roles, groups, templates, deployments, crashes, audit log, networks, workflow intents, composition plans, console scrollback. | Authoritative. Loss = the cluster is gone. |
| Redis | Coordination: leases, fencing tokens, runtime snapshots, plugin tokens, JWT/cert revocation, rate limits, SSE replay, login lockouts, password-reset tokens. | Ephemeral. Loss = in-flight retries pause; replay window shrinks. |
| Raft control plane | Cluster identity, versioned cluster config, members, join tokens, leader leases, cluster files (CA cert/key). | Replicated across controllers; persisted as the Raft log plus JSON snapshots under `data/raft`. |
| Process memory | Live model: nodes, instances, players, console ring buffers, registries. | Rebuilt on start from Mongo plus daemon reconnect. |

A single piece of conceptual state lives in exactly one store. See
[Adding new state](#adding-new-state) for the rule.

## MongoDB collections

The controller writes one database (named by `database.name`, default
`prexorcloud`). Collections are created lazily on first write;
`MongoStateStore.initialize()` opens its handles and calls
`ensureIndexes()` on every startup.

### Owned by `MongoStateStore`

| Collection | Purpose | `_id` |
|---|---|---|
| `templates` | Template metadata: description, platform, content hash, size, version history, variables. File bodies live on disk under the template store. | template name |
| `deployments` | Rolling-restart records: trigger, strategy, state, template/config snapshots, progress, rollback reference. | Mongo `ObjectId`; `seqId` is a monotonic counter |
| `crashes` | Crash records: instance, group, node, exit code, classification, cause summary, signature, log tail, uptime. | crash id |
| `audit_log` | Audit log of state-changing operations: username, action, resource, before/after JSON, IP. | `ObjectId` (used as the seek cursor) |
| `nodes` | Registered nodes: `firstSeen` / `lastSeen`. | node id |
| `user_preferences` | Per-user dashboard preferences (opaque JSON blob). | username |
| `workflow_transfers` | Durable player-transfer intents pending replay. | player UUID |
| `workflow_drains` | Node-drain intents: target node, instances draining, timeout. | node id |
| `workflow_healing` | Self-healing action intents: instance, group, reason. | instance id |
| `workflow_start_retries` | Pending start-retry intents: instance, group, node, plan hash, attempt, `retryAt`. | instance id |
| `instance_composition_plans` | Per-instance composition plan payload (template chain, runtime jar, patches, plugin token). Replayed by daemons on reconnect. | instance id |
| `console_lines` | Console scrollback. **Capped collection**, 256 MiB. | `ObjectId` |
| `shares` | Share records (paste links for crash logs etc.): kind, resource, URLs, expiry, revocation. | share id |
| `cluster_meta` | Local projection of cluster identity. Singleton document `_id: "cluster"`. | `"cluster"` |
| `counters` | Atomic named sequence counters (e.g. `deployment_id`), via `findOneAndUpdate` + `$inc`. | counter name |

`console_lines` is the only capped collection. `MongoStateStore`
creates it explicitly with `capped(true).sizeInBytes(256 MiB)` if it
does not exist; Mongo evicts oldest lines once the cap is reached.

### Owned by other subsystem stores

| Collection | Owner | Purpose |
|---|---|---|
| `users` | `MongoUserStore` | Local user accounts (username = `_id`, password hash, email, role, MC link). |
| `roles` | `MongoRoleStore` | Roles and permission lists. |
| `groups` | `MongoGroupStore` | Group configuration (platform, version, scaling, templates, MC config). |
| `catalog` | `MongoCatalogStore` | Available platform jars: platform + version → download URL + sha256. |
| `networks` | `MongoNetworkStore` | Network Composition records (lobby group, fallback groups, kick message). |

### Module collections

Modules get an isolated collection namespace. Two paths exist:

- **Capability-API platform modules** that request Mongo storage get the
  prefix `platform_<sanitizedModuleId>_`. Dropping the module drops
  every collection under that prefix.
- **Bundled cloud-modules** that use `ModuleDataStore` directly get the
  prefix `mod_<sanitizedModuleId>_`. For example, the player-journey
  module writes to `mod_player_journey_journey` (the `journey`
  collection under its prefix), with a compound index
  `{ playerUuid: 1, timestamp: -1 }`.

The prefix is the isolation contract; the controller does not police
key shapes inside a module's namespace.

### Indexes

`ensureIndexes()` is idempotent — re-running it (or restarting a
controller mid-bootstrap) re-asserts indexes without error. The full
set the controller creates:

| Collection | Index | Type | Purpose |
|---|---|---|---|
| `deployments` | `{ seqId: 1 }` | unique | Sequence lookup. |
| `deployments` | `{ groupName: 1, revision: -1 }` | unique | One revision per group; history scan. |
| `crashes` | `{ groupName: 1 }` | plain | Per-group crash list. |
| `crashes` | `{ crashedAt: -1 }` | TTL, 30 days (`crashes_ttl`) | Auto-prune. |
| `audit_log` | `{ createdAt: -1 }` | TTL, 90 days (`audit_ttl`) | Auto-prune. |
| `audit_log` | `{ username: 1 }` | plain | "Who did what" queries. |
| `workflow_transfers` | `{ createdAt: 1 }` | plain | Replay ordering. |
| `workflow_drains` | `{ requestedAt: 1 }` | plain | Replay ordering. |
| `workflow_healing` | `{ createdAt: 1 }` | plain | Replay ordering. |
| `workflow_start_retries` | `{ retryAt: 1 }` | plain | Due-retry scan. |
| `instance_composition_plans` | `{ createdAt: -1 }` | plain | Recency. |
| `console_lines` | `{ instanceId: 1, ts: 1 }` | plain | Per-instance time-range reads. |
| `shares` | `{ sharedAt: -1 }` | TTL, 30 days (`shares_ttl`) | Bounded retention; revoked entries still expire. |
| `shares` | `{ kind: 1 }` | plain | Filter by share kind. |
| `shares` | `{ sharedByUser: 1 }` | plain | Per-user shares. |
| `users` | `{ email: 1 }` | sparse-unique (`email_unique`) | Password reset by email; nulls allowed. |

The TTL retentions above (30 / 90 / 30 days) are compiled constants in
`MongoStateStore.ensureIndexes()` and `MongoUserStore`. `pruneAuditLog`
is a deliberate no-op — the TTL index handles rotation.

The `roles`, `groups`, `catalog`, and `networks` collections carry only
their default `_id` index. Don't assume an index that isn't in the
table above.

### Workflow intents are Mongo-backed, hydrated into memory

`WorkflowStateStore` keeps the four intent classes (transfers, drains,
healing actions, start retries) in `ConcurrentHashMap`s for fast access
and writes through to the matching `workflow_*` collection. On
construction it hydrates those maps from Mongo, so a controller restart
resumes pending intents. The durable record is the collection; the map
is a cache.

### What MongoDB does not own

These deliberately live in Redis, not Mongo:

- **Leases and fencing tokens.** Coordination needs cheap auto-expiry;
  Mongo TTL is per-minute granularity and adds contention overhead.
- **JWT and node-certificate revocation.** TTL-bound to the credential's
  remaining lifetime.
- **Runtime snapshots (node / instance / player).** High write rate,
  read by other controllers for routing, rebuilt from daemons anyway.
- **Rate-limit and login-lockout counters.** High write rate, sliding
  windows.
- **SSE replay buffers.** Bursty, bounded, no query need.

## Redis key families

Every controller key is prefixed `prexor:v1:`. The literal formats live
in one place — `RedisKeys` — so backup scope, the diagnostics endpoint,
and the runtime producers cannot drift apart. The version segment is
reserved for forward compatibility; all current reads and writes use
`v1`.

| Key family | Prefix | TTL / retention | Purpose |
|---|---|---|---|
| Lease ownership | `prexor:v1:lease:` | configured lease TTL (usually scheduler interval × 2) | Active-active mutation gating. Holds the owning controller and fencing token; expires if not renewed. |
| Fencing tokens | `prexor:v1:lease-token:` | no TTL | Monotonic per-resource counter, incremented on every acquire. |
| Node-owner hint | `prexor:v1:nodeowner:` | heartbeat interval × missed-threshold | Which controller owns a node's session, for command routing. Refreshed by heartbeats. |
| Node runtime snapshot | `prexor:v1:node:` | no TTL; deleted on cleanup | Shared node state for cross-controller reads. |
| Instance runtime snapshot | `prexor:v1:instance:` | no TTL; deleted on cleanup | Shared instance state. |
| Player runtime snapshot | `prexor:v1:player:` | no TTL; deleted on cleanup | Shared player state. |
| Plugin tokens | `prexor:v1:plugintoken:` | token expiry (default 15 min) | Per-instance bearer tokens; refreshed by the running plugin, revoked on stop. |
| JWT revocation | `prexor:v1:jwt:revoked:` | remaining JWT lifetime | Logout / change-password / explicit revoke. |
| Node-cert revocation | `prexor:v1:nodecert:revoked:` | remaining certificate validity | Revoked node certs, keyed by `serial:` or `cn:`. |
| Module Redis storage | `prexor:v1:platform:<moduleId>:` | module-managed | Per-module key space. Modules must scope under their own id; the prefix is the contract. |
| Rate limits | `prexor:v1:ratelimit:` | 60 s | Per-bucket REST/API counters. |
| Scaling cooldown | `prexor:v1:cooldown:` | configured cooldown duration | Per-group scheduler cooldown windows. |
| Workload replay protection | `prexor:v1:workloadseq:` | workload-token TTL (default 15 min) | Sequence window for plugin-token replay rejection. |
| Start-retry coordination | `prexor:v1:startretry:` | wakeups persist until `retryAt`; claims expire after scheduler interval × 2 | Cross-controller start-retry wakeups and claims. |
| Console flood window | `prexor:v1:console:window:` | 2 × active flood window | Console flood suppression. |
| SSE sequence / replay | `prexor:v1:sse:sequence`, `prexor:v1:sse:replay-stream` | no TTL; replay bounded by stream trim | Per-stream sequence counter and replay window. |
| SSE tickets | `prexor:v1:sse:ticket:` | 30 s | Short-lived auth tickets exchanged from a JWT. |
| Login failures / locks | `prexor:v1:login:fail:`, `prexor:v1:login:lock:` | failure window / lockout duration (defaults 15 min) | Per-username failed-login counters and active lockouts. |
| Password reset | `prexor:v1:pwreset:` | configured token TTL (default 30 min); deleted on consume | Single-use email-token state bound to a username. |

Pub/sub channels (not stored keys) carry cross-controller events under
`prexor:v1:events:node` / `instance` / `player` / `group` / `command`
/ `reply`.

A running controller exposes the live policy list at
`GET /api/v1/system/redis/schema` and a live keyspace count at
`GET /api/v1/system/redis/keyspace`. Both require the
`system.settings` permission.

### Eviction policy

Recommended Redis config for a PrexorCloud-only instance:

```text
maxmemory <appropriate-size>
maxmemory-policy volatile-lru
appendonly yes
appendfsync everysec
```

- Most PrexorCloud keys carry TTLs, so `volatile-lru` evicts
  oldest-TTL'd keys first.
- Do not use `noeviction` (writes fail) or `allkeys-*` (would evict the
  no-TTL fencing-token and runtime-snapshot keys).
- The runtime snapshots and fencing tokens have no TTL by design.
  `FLUSHALL` on a shared Redis drops leases, fencing tokens, runtime
  snapshots, revocations, and replay buffers in one stroke. If Redis is
  shared, document the policy.

## The Raft control plane

When a controller runs the embedded cluster control plane, an Apache
Ratis Raft group replicates a small, typed state machine across
controllers. This is separate from Redis: Redis coordinates per-cycle
mutation; Raft holds the cluster's slowly-changing identity and config.

`ClusterControlStateMachine` holds these projections in memory and
replicates every write through the Raft log:

| Projection | Holds |
|---|---|
| `ClusterMeta` | Cluster identity. Also mirrored to the `cluster_meta` Mongo collection as a local cache. |
| Config versions | Versioned cluster config (`NavigableMap` of version → config), with an active-version pointer. |
| Members | Cluster members and their endpoints. |
| Join tokens | Outstanding join tokens for new members. |
| Leases | Leader / coordination leases held in Raft. |
| Cluster files | Inline bytes for shared trust material, e.g. `cluster-ca.crt` and `cluster-ca.key`, keyed with a sha256. |

Durability: writes go through the Raft log; the full state is
serialised to a single JSON snapshot file under the Ratis snapshot
directory. On restart, the controller drops in-memory state, reloads
the latest snapshot, and replays the log delta to the live tail. Reads
do not go through Raft — they return immutable snapshots of the local
projection.

Storage location and binding come from the `raft` config block:

```yaml
raft:
  host: 0.0.0.0
  port: 9190
  dataDir: data/raft
  joinAddrs: []
```

`dataDir` defaults to `data/raft`; `port` defaults to `9190`.
`joinAddrs` lists the gRPC endpoints of existing members when joining
an established cluster.

## Process memory

Process memory is reconstructable and never the source of truth.

| Component | What it holds | Rebuilt how |
|---|---|---|
| `ClusterState` | Live model: nodes, instances, players, group memberships, plugin tokens issued this run. | On start: Mongo for groups + templates + composition plans + crashes; daemon reconnect for live node/instance state; Redis runtime snapshots for cross-controller view. |
| `WorkflowStateStore` | In-memory transfer/drain/healing/start-retry intents. | Hydrated from the `workflow_*` Mongo collections on construction. |
| `EventBus` | In-process pub-sub handler list. | Per-process; not persisted. |
| `NodeSessionManager` | Per-node gRPC stream handles. | Daemons reconnect on controller restart. |
| Console buffers | Recent console lines per instance, in memory. | Lost on restart; daemons re-stream. Durable scrollback is the `console_lines` capped collection. |
| `RingBufferLogAppender` | Recent controller log lines for `prexorctl logs controller`. | Lost on restart. |
| `CrashLoopDetector` | Sliding window of recent crashes per group. | Rebuilt from `crashes` on start. |
| `CapabilityRegistry` / `ExtensionRegistry` / `ModuleFrontendManager` | Resolved module capability handles, workload extensions, frontend manifests. | Re-registered as modules load. |

## Backup scope

`prexorctl backup create` produces a tarball with a manifest. The
manifest records exactly what was captured: the Mongo database name,
the explicit collection list, the module collection prefixes
(`mod_*`, `platform_*`), the Redis key prefixes, and document/key
counts.

- **MongoDB** is the durable core — every collection above, including
  module-prefixed collections.
- **On-disk state** under the template store, module data directory,
  and config (with secrets redacted).
- **Redis** is optional. Coordination state is ephemeral by definition;
  restore rebuilds the cluster from MongoDB and lets Redis refill cold.
- **Raft** state under `data/raft` is rebuilt by the surviving cluster
  members; a single-controller restore re-bootstraps it.

See [Backups and DR](/operations/backups-and-dr/).

## Adding new state

Walk down this checklist:

1. **Must it survive a full restart of every controller?** → MongoDB.
2. **Is it ephemeral but cluster-shared (TTL-driven, lease-shaped,
   rate-limited, a runtime snapshot)?** → Redis.
3. **Is it cluster identity / config / membership that must be
   consistent across controllers?** → the Raft control plane.
4. **Is it derivable from MongoDB + live gRPC reconciliation in
   seconds?** → process memory.

One rule overrides the checklist: **never split a single piece of
conceptual state across two stores.** A workflow intent lives in
MongoDB and is cached in memory — it is never half in Redis. Register a
new key family in `RedisKeys` (not as an ad-hoc string), and a new
collection's indexes in `ensureIndexes()` or the owning store's
constructor, so backup scope and diagnostics stay accurate.

## Why this split exists

1. **TTL semantics are fundamental to coordination.** Leases that don't
   auto-expire are deadlock generators. Redis primitives give cheap,
   low-variance expiry; Mongo TTL is coarser and noisier under
   contention.
2. **Replay and rate-limit shapes are bursty and bounded.** Range scans
   over a sequence and counter decrements are trivial in Redis and
   awkward in Mongo.
3. **Cluster identity needs consensus, not only shared storage.** Member
   sets, config versions, and the cluster CA must be agreed across
   controllers — that is what Raft is for, and why it is a separate tier
   from Redis.
4. **Durability requirements differ.** A Mongo loss means the cluster is
   gone. A Redis loss pauses in-flight retries and shrinks the replay
   window. A Raft loss is recovered from surviving members. Each store
   is sized for its own failure mode.

## Next up

- [Architecture](/internals/architecture/) — the runtime model and lease semantics
- [Configuration reference](/operations/configuration/) — every key under `database`, `redis`, `raft`, `scheduler`
- [Backups and DR](/operations/backups-and-dr/) — what restore preserves
