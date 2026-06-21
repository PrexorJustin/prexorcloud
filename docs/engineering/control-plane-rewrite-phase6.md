# Phase 6 — Remove Redis/Valkey entirely

The single-writer Mongo control plane is live-proven (Phases 0–5, 7). Phase 6 retires
the last backing store other than Mongo: Redis/Valkey. After it, the stack is **one
store** — MongoDB.

## Why each Redis use moves where it does

A census (four exploration passes) found a clean seam already in place: `RuntimeServices`
with a production (`RedisRuntimeServices`) and a development (`InMemoryRuntimeServices`)
implementation, and most stores already have an `InMemory*` twin. Phase 6 re-homes the
~29 uses four ways.

| Cluster | Members | Target | Rationale |
|---|---|---|---|
| Durable security | JWT revocation, login throttle/lockout, password-reset tokens, node-cert revocation | **Mongo (TTL)** | Must survive a controller restart / leadership change — in-memory would reset every attacker's counter and un-revoke every token. |
| Workload identity | plugin tokens, `workloadseq` replay window | **Mongo-durable + leader cache** | The 401 fix: a cold new leader reads the token back from Mongo instead of rejecting every running plugin. |
| Ephemeral | console flood window, SSE tickets + replay, rate-limit buckets | **Leader memory** | All client traffic routes to the leader, so a per-leader in-memory bucket is correct; replay/tickets are leader-local and re-sync on reconnect. |
| Coordination | `DistributedLeaseManager`/`LeaseGate`, `RedisEventBridge` relay, `RedisStartRetryWakeupQueue`, `nodeowner:*`, runtime projection (`RedisRuntimeStore` node/instance/player + `reconcileInstancesFromRedis`) | **Delete** | Ownership is leadership now. The leader owns every daemon stream (Phase 3), so cross-controller relay and projection are moot; it rebuilds runtime state from daemon re-announce. Start-retries are already persisted in Mongo `workflow_start_retries`. |
| Module KV | `PlatformModuleStorageManager` Lettuce backend, `PlatformRedisStorage`, `ControllerModuleContext.{find,require}RedisStorage` | **Drop Lettuce, keep Mongo** | Module storage already has a `MongoModuleDataStore`; the ephemeral Redis backend is removed. |

## Slices

Each slice keeps the controller suite green and is committed independently. New Mongo
stores land as **unwired primitives first** (the cadence used for `MongoLeaderElector`
and `MongoClusterStore`), then a wiring slice swaps the production path and deletes Redis.

- **Slice 1 — `9ef0f5e`** ✅ Mongo durable security stores (unwired): `MongoJwtRevocationStore`,
  `MongoLoginAttemptStore`, `MongoPasswordResetTokenStore`, `MongoNodeCertificateRevocationStore`
  behind their existing interfaces. TTL index `expireAfter(0)` on an absolute `expiresAt`;
  reads compare `expiresAt` to now (the ~60s TTL sweep is too late to trust); `recordFailure`
  is an atomic `$$NOW` pipeline; `take` is a guarded `findOneAndDelete` (single-use). RS test green.
- **Slice 2 — `1f40d3d`** ✅ Mongo workload-token store (unwired): `WorkloadTokenStore` seam +
  `MongoWorkloadTokenStore`. Tokens in `plugin_tokens`, replay high-water in `workload_sequences`,
  both TTL. `acceptSequence` is an atomic accept-if-greater pipeline (`returnDocument BEFORE`).
  RS test green.
- **Slice 3a–d** ⏳ Collapse ephemeral consumers to in-memory, one at a time: rate-limit
  (`RateLimitMiddleware` → `WindowCounter` only; delete `RateLimitReloader` + `failOpenOnRedisError`),
  SSE tickets/replay, console flood, module KV (Mongo only).
- **Slice 3e** — leadership-gate the self-gated components and retire their leases.
  - ✅ Healing (`699b4cc`), node drain (`5da1a11`), platform module mutation (`5da86dd`):
    each gates on `leadership.isLeader()` and drops its Redis lease; the multi-controller-lease
    tests became leadership tests (a follower ignores the work).
  - ✅ Scheduler-cluster lease threading: `LeaseGate`/`LeaseGuard` collapsed to a boolean
    leadership fence (drop the `DistributedLeaseManager.Lease` token from every signature);
    `Scheduler`/`InstancePlacementCoordinator`/`StartRetryOrchestrator`/`RecoveryOrchestrator`
    no longer thread a lease; `DistributedLeaseManager` + `newLeaseManager` (RuntimeServices/impls)
    + `DiagnosticsCollector.scanAllLeases` + `DistributedLeaseManagerTest` deleted. The
    `onLeaseAcquired` per-group reconcile hook is dropped — `MongoLeaderElector.onAcquired`
    already triggers a full `scheduler.requestReconcile()` on takeover. Controller suite green.
  - ✅ Start-retries are leader-memory only (`7a31023`): `RedisStartRetryWakeupQueue` +
    `StartRetryWakeupQueue` deleted; the in-process scheduled-executor on the leader is the only
    path (intents stay durable in Mongo `workflow_start_retries`, resumed via `reconcilePersisted`).
  - ✅ REST leader-redirect (`cde80b8`): a non-leader returns `307` to the leader's REST address
    (`LeaderRedirectMiddleware`, resolving `Member.restAddr` via the leadership holder) — the HTTP
    analog of the daemon handshake redirect, and the enabler for deleting the relay. This is
    finding #11 fix-A, pulled forward (no longer deferred to 3f). Health/ready/metrics exempt; no
    known leader → `503`.
  - ✅ Cross-controller relay + `nodeowner:*` deleted (`921feee`): `RedisEventBridge` removed
    wholesale (command **and** reply **and** cache relay), `NodeMessageDispatcher.routeRemote`/`ownsNode`
    gone, `nodeowner:*` keys/writes gone, the file-reply relay collapsed to local-complete. Safe now
    that all traffic reaches the leader (daemon redirect + REST redirect): nothing lands on a follower
    to relay. ⚠️ behaviour-changing — still wants the fleet gate (kill leader under load; no double-
    mutation / split-brain). Controller suite green throughout.
- **Slice 3f** — ✅ DONE.
  - ✅ Dead `applyRemote*` projection hooks removed (`97f4ed3`) — orphaned when `RedisEventBridge` went.
  - ✅ `ClusterState` token-store swap (`6e4d0ee`): the store field is now `WorkloadTokenStore`
    (Mongo, via `MongoWorkloadTokenStore`), token-only. Dropped every node/instance/player
    write-through + `reconcileInstancesFromRedis` + `adoptInstanceFromRedis` + the prune helper;
    `Scheduler.evaluate` no longer ticks the projection; `DaemonServiceImpl.verifyNodeOwnership`
    drops the Redis adopt (daemon-handshake `decideReportedInstance` is the rebuild path). `hydrate`
    warms the token cache from `loadAllTokens` on takeover. **This completes the 401 fix** — a cold
    leader reads a still-valid token back from Mongo (`hydratePluginTokenFromStore`) instead of
    rejecting the plugin. Finding #11's token read-through is now Mongo-backed. Suite green (810).
    ⚠️ behaviour-changing — the leader rebuilds node/instance state from daemon re-announce + Mongo
    composition-plan recovery (no projection); fleet gate validates it.
- **Slice 3g** ⏳ Rebuild the production `RuntimeServices` on Mongo (wire slices 1+2; select by
  `RuntimeConfig.profile`, not `config.redis()`); delete `RedisRuntimeServices`.
  - **Note (see `live-run-findings.md` #12):** the Mongo state-write path is not epoch-fenced (a deposed
    leader can LWW-clobber instance/deployment/intent docs in the failover-overlap window). Tackle AFTER
    3f/3g settle the write surface — don't fence a surface you're about to restructure. Fix = `ownerEpoch`
    stamp on every state write + txn-precondition on destructive writes/intents. Orthogonal P0 prerequisite:
    a real 3-node replica set (the single-member RS gives CAS but no quorum) — fold into the next HA fleet
    stand-up, not behind the code.
- **Slice 4** ⏳ Delete `redis/` package, `RedisConfig`/`RedisTracing`/`RedisKeyspaceInspector`,
  `ControllerConfig.redis()`, the Lettuce gradle dependency, the Valkey compose/systemd container,
  and the Redis readiness/diagnostics probes. Suite passes with Valkey **absent**, not disabled.
- **Slice 5** ⏳ ADR (amend the relevant decisions) + docs.

## Fleet validation (behaviour-changing slices)

Slices 3e/3f change failover behaviour (no cross-controller projection; leader rebuilds from
daemon re-announce). That is consistent with Phase 3 consolidating all daemon streams onto the
leader, but it is best confirmed on the fleet: roll the build, kill the leader under a running
instance, and verify the new leader re-adopts with **zero** `HTTP 401` / token-refresh / re-dispatch
(the historical post-restart wedge). See `control-plane-rewrite-fleet-validation.md` for access
and the gate format.
