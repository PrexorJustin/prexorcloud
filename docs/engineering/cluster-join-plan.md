# Cluster control plane — design plan

**Status:** v3 reworked 2026-05-29. Phase 1 from the v1 plan (cluster_meta in
Mongo) shipped on this branch and is **superseded** by this rework — its
data moves into the Raft state machine. Phase 2 from the v1 plan (the
`/join-template` REST endpoint, `Permission.CLUSTER_JOIN`) shipped on this
branch and is **deprecated and deleted** by this rework. Not yet started for
v3-specific work.
**Owner:** unassigned.
**Target:** v1.1.

## The architectural decision

PrexorCloud's controller stops sharing its cluster control plane through
Mongo and instead **embeds a Raft consensus group**. The N controllers in a
cluster form a Raft group with each other; the replicated state machine
holds every piece of state that requires consensus, version monotonicity,
membership semantics, or leader election. Mongo continues to be the system
of record for business state (templates, instances, deployments, audit log,
shares). Redis continues to handle ephemeral high-frequency state
(rate-limit counters, SSE tickets, daemon heartbeats, console buffers).

This is the architecture used by Consul, etcd, CockroachDB, Kafka KRaft,
NATS JetStream, and Apache Ozone. It is the answer when "best-in-class
distributed control plane" is the requirement.

The Raft state machine holds:

| Sub-state | Purpose |
|---|---|
| `clusterMeta`        | clusterId, createdAt, seedSecret, schema version |
| `clusterConfig`      | every cluster-shared setting (jwtSecret, CORS list, lockout policy, signing trust root metadata, …), versioned append-only with an `activeVersion` pointer |
| `members`            | controller list: nodeId, raftAddr, restAddr, gRPCAddr, status, joinedAt, lastSeen |
| `joinTokens`         | outstanding / redeemed / revoked single-use tokens |
| `leases`             | named leader leases (e.g. `scheduler`, `deployment-reconciler`, `dr-drill-runner`) with holder + TTL |
| `clusterFiles`       | small binary blobs the cluster shares (module signing trust root PEM, future CA bundle, etc.) |

Mongo holds none of this in v1.1. Redis holds none of this in v1.1.

## Why this design

Compared to the v1 plan (join-template REST endpoint) and the intermediate
v2 plan (Mongo-as-truth), embedded Raft wins on four dimensions
simultaneously:

1. **Strong consistency by construction.** Two operators editing the CORS
   list at the same instant can't lose a write; the Raft log linearises
   them. No locking dance, no compare-and-set retries.
2. **No external coordinator.** No etcd, no Consul, no ZooKeeper. The
   controllers *are* the coordinator. Operations stays "N controllers +
   Mongo + Redis", the same envelope as today.
3. **Membership is first-class.** Adding a controller is a single Raft
   joint-consensus change. Removing one is the inverse. Split brain is
   structurally prevented by quorum. The same code path handles a cluster
   of size 1 and a cluster of size 7.
4. **Leader election is free.** The existing Redis-based "who runs the
   scheduler this minute" leases get replaced by Raft leader leases —
   simpler, correct under partition, no lease-expiry races.

What we give up: Raft is non-trivial. Snapshot tuning, log compaction,
joint-consensus during membership changes, recovery from majority loss are
all real ops concerns. We accept that complexity in exchange for the
guarantees above; it is what every serious cloud control plane runs.

## Library choice — Apache Ratis

JVM Raft library options were evaluated:

- **Apache Ratis** — actively maintained, Apache 2, production-hardened by
  Apache Ozone (storing exabytes), supports pluggable transport (gRPC, Netty)
  and pluggable state-machine snapshotting. **Selected.**
- **MicroRaft** — clean and lightweight but development has slowed.
- **JRaft (Alibaba)** — heavy framework, opinionated, used in SOFA stack.
- **Atomix** — comprehensive but development stalled in 2022.
- **Roll our own** — rejected. Raft is famously easy to get subtly wrong;
  this is not the place to be original.

Ratis ships with a gRPC transport, which fits cleanly alongside our existing
gRPC infrastructure (mTLS already wired). Same TLS material covers Raft
traffic — no new keys, no new ports if we co-locate Raft on the controller
gRPC server.

## Node-local vs cluster-shared

`controller.yml` shrinks to genuinely node-local fields. Everything else
lives in `clusterConfig` (Raft state machine), regardless of how often it
changes.

| Section in controller.yml (v1.1) | Why node-local |
|---|---|
| `uuid`                                          | per-node identity |
| `http.host` / `http.port`                       | bind decided per-NIC + LB |
| `grpc.host` / `grpc.port`                       | same |
| `raft.host` / `raft.port`                       | Raft transport bind |
| `raft.joinAddrs[]`                              | comma-separated peer addresses used to find the cluster at startup |
| `logging.level` / `logging.format`              | operators turn DEBUG on per-node |
| `dashboard.enabled`                             | a node may run dashboard-less |
| `database.uri` / `database.database`            | Mongo for business state — bootstrap secret |
| `redis.uri`                                     | Redis for ephemeral state — bootstrap secret |
| `security.initialAdminPassword`                 | one-shot, irrelevant on second node |
| `cluster.id` (read-only mirror)                 | written by bootstrap from Raft state; mismatch refusal |

Everything from today's `controller.yml` not listed above moves into
`clusterConfig`.

## Bootstrap flow — Day 0 and Day N are the same code

```
loadControllerYaml()                  # node-local only
connectMongo(database.uri)            # business state
connectRedis(redis.uri)               # ephemeral state

if pendingJoinTokenFileExists():
    joinExistingCluster()             # Day-N: contact peers, redeem, replay log
elif raft.joinAddrs[] is non-empty:
    joinExistingCluster()             # same, addresses came from config not file
else:
    bootstrapOrRecoverLocalRaft()     # Day-0 OR restart of an existing member

raftReady()
readStateMachine() → effectiveConfig
validate(effectiveConfig)
startRestAndGrpc()
```

### Day 0 — first controller, fresh cluster

```
raftDir = <data>/raft
if raftDir is empty:
    seed       = secureRandomBytes(32)
    clusterId  = newUuid()
    initialCfg = wizardCollectedSharedConfig()
    raft.bootstrap(group = [self])
    proposeAndCommit([
      SetClusterMeta{clusterId, seed, createdAt: now, schemaVersion: 1},
      SetClusterConfig{version: 1, ...initialCfg},
      AddMember{nodeId: self, raftAddr, restAddr, gRPCAddr, joinedAt: now},
    ])
    writeYaml(cluster.id = clusterId)        # node-local mirror
else:
    raft.recover()                            # rejoin own log
```

### Day N — operator adds a controller

```
operator @ existing controller:
    prexorctl cluster join-token create [--ttl 24h] [--label "controller-2"]
    → server proposes WriteJoinToken{jti, hmac, expiresAt, label}
    → committed in Raft, server prints token string
    → audit: cluster.join_token.issued

token format:
    prexor-jt:v1:<base64url(payload)>.<base64url(hmac)>
    payload = { joinAddrs[], clusterId, jti, expiresAt }
    hmac    = HMAC-SHA256(seedSecret, payload)

operator @ new controller (Day-N node):
    runs wizard, pastes token, fills node-local fields, installer writes
    pending-join-token to disk and starts the controller

new controller boot:
    parses token (un-verified — verification needs cluster state)
    dials joinAddrs[] via gRPC (with bootstrap mTLS from cluster CA — see
      "TLS bootstrap" below)
    sends ClusterMembership.RequestJoin(token, raftAddr, restAddr, csr)

existing controller (any peer, redirects to leader if needed):
    leader.proposeAndCommit([
      RedeemJoinToken{jti, redeemedAt, redeemedFrom},       # rejects on replay
      AddMember{nodeId: new, raftAddr, restAddr, gRPCAddr},
    ])
    signs CSR with cluster CA, returns:
      { clusterCa, signedCert, raftMembership, snapshotRef }

new controller:
    installs mTLS materials, joins Raft group via Ratis member-add,
    replays snapshot + log delta, applies to state machine
    deletes the pending-join-token file
    starts REST + gRPC, hooks into Redis pub/sub for ephemeral events
    → live as a full peer
```

There is **no separate "first controller" wizard mode and "join" wizard
mode**. The wizard's only branch is one question — *do you have a join
token?* — and the Day-0 vs Day-N divergence shows up only in the
bootstrap subroutine, which is one `if` statement.

### TLS bootstrap for Raft traffic

Raft messages need mTLS. The new controller doesn't yet have a
cluster-signed certificate when it makes its `RequestJoin` call. We use
**join-token-bound TLS**: the new controller generates an ephemeral key
pair, signs a CSR, and sends it inside the `RequestJoin` payload — which is
itself authenticated by the join token HMAC. The receiving controller
validates the token, signs the CSR with the cluster CA, and returns the
signed cert in the response. Subsequent Raft traffic uses the signed cert.

This avoids a chicken-and-egg problem (need a cert to talk to the cluster,
need the cluster to get the cert) without exposing a public "anyone can ask
for a cert" endpoint.

## State machine schema (typed Raft entries)

All mutations are typed log entries. Entries are versioned for
forward-compat.

```
SetClusterMeta            { clusterId, seed, createdAt, schemaVersion }
RotateSeedSecret          { newSeed, rotatedBy, rotatedAt }
                          # invalidates outstanding tokens

WriteClusterConfig        { version, parentVersion, mutator, mutatedAt,
                            patch: {section → fieldPatches}, reason? }
SetActiveConfigVersion    { version }
                          # config history retained for N versions (e.g. 50);
                          # older versions GC'd at snapshot time

AddMember                 { nodeId, raftAddr, restAddr, gRPCAddr,
                            joinedAt, label }
RemoveMember              { nodeId, leftAt, reason }
TouchMember               { nodeId, lastSeen }   # heartbeat — batched, not
                          # every tick; coarse health only

WriteJoinToken            { jti, hmac, label, createdBy, expiresAt }
RedeemJoinToken           { jti, redeemedAt, redeemedAs, redeemedFrom }
RevokeJoinToken           { jti, revokedBy, revokedAt }

GrantLease                { leaseName, holder, grantedAt, ttl }
RenewLease                { leaseName, holder, renewedAt }
ReleaseLease              { leaseName, releasedBy }

WriteClusterFile          { key, sha256, bytes }   # small; large blobs go
                          # to Mongo and the entry holds the ObjectId
DeleteClusterFile         { key }
```

Reads are local to the state machine (every controller has the full copy).
Linearizable reads available via Ratis's `ReadIndex` protocol when callers
opt in (`?consistency=linearizable` on REST). Default reads are
*sequentially consistent* (read your own writes, no real-time guarantee
across controllers) — fast enough for the dashboard.

## Versioned config — every change is a new version

`clusterConfig` is **append-only versioned**, not mutate-in-place:

```
clusterConfigVersions:
  1 → { ..initial.. } @ 2026-05-29T10:00 by wizard
  2 → patch: corsAllowList += "https://dashboard.example.com" @ 10:42 by alice
  3 → patch: lockout.maxAttempts: 5 → 3 @ 11:15 by bob
  ...
activeVersion: 17
```

Benefits:
- **Free audit by construction.** The Raft log is the audit; replay the log
  to see every change.
- **Trivial rollback.** `prexorctl cluster config rollback <version>`
  proposes a new entry that sets the active version back. No surprise
  diffs.
- **Honest concurrent edits.** Each patch carries `parentVersion`; conflicting
  patches against the same parent are rejected (HTTP 409), forcing the
  caller to refetch and rebase. No silent last-writer-wins.

Old versions are garbage-collected at snapshot time (configurable retention
— default 50 versions or 90 days, whichever is larger).

## Live reload — no Redis pub/sub needed

When a config change commits, every controller's state machine `apply()`
fires locally. The applied entry's diff is published on the controller's
internal `EventBus`; subscribers (CorsAllowList, RateLimiter, JwtManager,
the dashboard SSE feed, …) update in place. No Redis round-trip; the
notification is the Raft commit itself.

This replaces the v2 plan's Redis pub/sub broadcast. Redis pub/sub stays
in the codebase for what it's good at — ephemeral fan-out of events that
don't belong in a replicated log (player join/leave, console lines, daemon
heartbeats).

## Leader-elected work

Several existing pieces of the controller hold ad-hoc Redis leases to
single-write critical work — scheduler, deployment reconciler, DR drill,
audit pruner. They each migrate to Raft-leased work:

```java
clusterLeases.takeLease("scheduler", ttl = 30s, onAcquire = () -> {
    scheduler.start();
    return () -> scheduler.stop();                  // released on lease loss
});
```

Under the hood: lease acquisition is a `GrantLease` Raft entry; renewals
are `RenewLease`; the leader's own lease loss (partition) fires `onLost`
and the work stops cleanly. A new leader picks it up on the next tick.
This replaces several hundred lines of Redis-based lease code (and its
known races around TTL expiry) with a single shared mechanism.

## REST surface

```
GET    /api/v1/cluster                              - cluster status, members, raft health
GET    /api/v1/cluster/members                      - detailed member list
DELETE /api/v1/cluster/members/{nodeId}             - force-eject a dead member (quorum-only)
POST   /api/v1/cluster/leave                        - graceful self-removal

GET    /api/v1/cluster/config                       - current effective config (with masking)
GET    /api/v1/cluster/config/versions              - version history
GET    /api/v1/cluster/config/versions/{n}          - a specific version
POST   /api/v1/cluster/config                       - propose a patch (body: {parentVersion, patch})
POST   /api/v1/cluster/config/rollback              - body: {targetVersion}

POST   /api/v1/cluster/join-tokens                  - issue a token
GET    /api/v1/cluster/join-tokens                  - list outstanding
DELETE /api/v1/cluster/join-tokens/{jti}            - revoke

POST   /api/v1/cluster/seed/rotate                  - rotate seedSecret (interactive confirmation)
GET    /api/v1/cluster/leases                       - lease holders (debugging / on-call)
```

Permissions:

| Permission         | Bundled in default ADMIN? | Covers |
|---|---|---|
| `CLUSTER_VIEW`         | yes | GET status/members/config/leases |
| `CLUSTER_CONFIG_WRITE` | yes | POST config patch & rollback (CORS list, rate limits, etc.) |
| `CLUSTER_MANAGE`       | **no** — must be granted via custom role | issue/revoke join tokens, force-eject members, rotate seed |

`CLUSTER_MANAGE` stays out of the default ADMIN bundle (mechanism reused
from the v1 work — the `Role.EXCLUDED_FROM_DEFAULT_ADMIN` set just changes
contents). Reason: a join-token creator can add controllers to the cluster.
That should be a conscious grant, not a default.

Sensitive sub-fields (`security.jwtSecret`, `redis.uri`, SMTP creds) are
masked on `GET /cluster/config` to `***` unless the caller passes
`?reveal=true` and holds `CLUSTER_MANAGE`. Rotating them is a dedicated
sub-action (`POST /cluster/config/security/jwt-secret/rotate`), not a
PATCH.

## CLI surface

```
prexorctl cluster status                     # leader, members, log lag
prexorctl cluster members                    # detail
prexorctl cluster leave [--node <id>]        # graceful
prexorctl cluster eject <nodeId>             # force-remove dead peer

prexorctl cluster join-token create [--ttl 24h] [--label "..."]
prexorctl cluster join-token list
prexorctl cluster join-token revoke <jti>

prexorctl cluster seed rotate                # interactive
prexorctl cluster config get [<path>]
prexorctl cluster config set <path> <value>
prexorctl cluster config versions
prexorctl cluster config rollback <version>

prexorctl cluster recover                    # majority-lost recovery (last resort)
```

## Snapshot and log compaction

Ratis handles log compaction via state-machine snapshots. The state
machine is small (a few MB at most — config + member list + open tokens),
so snapshots are cheap and frequent (every 10k entries or 1h, whichever
first). Snapshots are stored on disk per-controller under `<data>/raft/snapshots/`.

Joining members fetch the latest snapshot via gRPC streaming, then catch
up via the log delta. No business data crosses Raft, so even a multi-year-
old cluster's snapshot fits in a single file.

## Recovery from majority loss

When the cluster loses quorum (e.g. 2-of-3 controllers gone), the survivor
cannot proceed under normal Raft rules. We expose:

```
prexorctl cluster recover --i-have-only-survivor
```

which performs an unsafe single-member reset of the Raft group, with
prominent warnings, an interactive confirmation, and an audit log entry
that survives the reset. The doc warns that any uncommitted writes on the
lost nodes are gone — that's the cost of taking the system back up
unilaterally.

This is the same shape as etcd's `etcdctl snapshot restore --force-new-cluster`
or Consul's `consul operator raft remove-peer -force`. Necessary evil for
real-world ops; clearly marked as dangerous.

## Migration from v1.0

Existing v1.0 controllers have everything in `controller.yml` and no Raft.
The migration runs at first v1.1 boot:

```
if raftDir is empty AND controller.yml has the cluster-shared sections:
    log "Migrating from pre-Raft layout."
    initialCfg = projectClusterSharedSubset(controllerYaml)
    raft.bootstrap(group = [self])
    proposeAndCommit([
      SetClusterMeta{clusterId: existing-or-fresh, seed: new, ...},
      SetClusterConfig{version: 1, ...initialCfg},
      AddMember{self, ...},
    ])
    writeYaml(cluster.id = clusterId)
    log "Migration complete. Cluster-shared fields in controller.yml are " +
        "now ignored on subsequent boots — remove them at your leisure."
```

The `ClusterJoinTemplate` projection that shipped under the v1 plan
(`java/cloud-controller/src/main/java/me/prexorjustin/prexorcloud/controller/config/ClusterJoinTemplate.java`)
is **reused unchanged** as `projectClusterSharedSubset` here. The unit
tests that pin its inclusion/exclusion contract stay as the authoritative
spec for what counts as cluster-shared. Single highest-value carry-over
from the v1 work.

For one minor release (v1.1 → v1.2), if an operator mutates a
cluster-shared field in controller.yml AND it differs from the Raft
state, the bootstrap loudly warns but uses the Raft value. In v1.2 the
mismatch becomes a refusal to boot, pushing operators off the
duplicate-write workflow.

Operators with two pre-v1.1 controllers pointed at the same Mongo follow
this sequence: stop both, upgrade controller-1 to v1.1 (it migrates,
becomes a single-member Raft cluster), generate a join token, install
v1.1 on controller-2 and paste the token, controller-2 joins the Raft
group. Migration guide will spell this out.

## Implementation order

Each phase ships independently and adds value on its own.

1. **Ratis spike** — embed Apache Ratis, build a trivial state-machine that
   stores a key-value map, prove single-node bootstrap and recovery from
   restart. ~3 days.
2. **State machine schema + read path** — implement the typed entries
   above; expose them via a `ClusterControlPlane` Java interface; replace
   all readers of `ControllerConfig.security/runtime/network/...` with
   reads against the resolved (Raft-applied) state. ~4 days.
3. **First-boot bootstrap + v1.0 migration** — wizard input seeds the
   state machine on Day 0; on a v1.0 → v1.1 upgrade, the migration shim
   seeds it from `controller.yml`. ~3 days.
4. **gRPC membership protocol + TLS bootstrap** — `ClusterMembership`
   service, join-token-bound CSR signing, member-add via Ratis joint
   consensus, snapshot fetch over gRPC streaming. ~5 days. **Read
   [ratis-spike.md](ratis-spike.md) first** — the spike answers the four
   Ratis-API unknowns this phase rests on and already shipped one
   prerequisite fix (state-machine lifecycle transitions for InstallSnapshot
   on a joining follower).
5. **CLI + REST: join tokens, members, status** — `prexorctl cluster ...`
   subcommands and matching REST endpoints. ~3 days.
6. **Versioned config + REST patch surface** — append-only versions,
   parentVersion concurrency control, rollback, masking on read. ~3 days.
7. **Live reload over Raft** — replace existing Redis pub/sub
   subscribers for CORS, rate limits, signing policy, etc. with state-
   machine `apply()` notifications. ~2 days.
8. **Leader leases for scheduler / deployment reconciler / DR drill /
   audit pruner** — replace Redis-based leases. Independent of Raft for
   correctness if not done immediately, so deferrable to v1.2. ~3 days.
9. **Wizard: token-pasted branch** — frontend + installer backend. ~2 days.
10. **Dashboard "Cluster" page** — members table, leader, log lag,
    config version history with diff view, lease holders. ~3 days.
11. **Recovery tooling** — `prexorctl cluster recover` + force-eject.
    ~2 days.
12. **Docs + ADR + migration guide** — record this architecture as the
    cluster-control-plane ADR; write the v1.0 → v1.1 ops migration guide.
    ~2 days.

Total: ~35 engineering days. Roughly 5× the v1 plan and 2.5× the v2
plan, in exchange for being a true distributed control plane.

## What we're NOT doing

- **Cross-region clusters.** Raft assumes low-latency same-region peers
  (sub-50ms RTT). For multi-region we would build a separate WAN-replication
  layer; out of scope.
- **Putting business data into Raft.** Templates, instances, audit log
  entries, share records — all stay in Mongo. Raft is for control plane,
  not for thousands-of-writes-per-second business state.
- **Auto-scaling the cluster.** Membership changes are operator-initiated.
- **Removing the Mongo and Redis dependencies.** Both still pull their
  weight on data they're good at.
- **Hot-reloading the Raft library.** Ratis upgrades follow a
  rolling-restart-with-quorum-preserving process; documenting that is in
  scope, hot-swap is not.

## Open questions

- **Default cluster size.** Single-controller installs work as a 1-node
  Raft group (commits to itself). HA needs ≥3 (for f=1) or ≥5 (for f=2).
  The wizard should call this out — "you have one controller; you have an
  HA cluster of zero. Add ≥2 more for fault tolerance."
- **Should we co-locate Raft traffic on the existing controller gRPC
  port, or use a dedicated `raft.port`?** Co-locating saves an open port
  but mixes traffic types. Lean dedicated port for operability — Raft
  traffic patterns are different and should be observable separately.
- **Membership-change throttling.** Joint consensus during a join briefly
  expands the quorum; doing two joins in flight needs care. Probably
  serialize joins behind a Raft-held mutex.

## What from the v1 work survives, dies, or repurposes

| Component | Fate |
|---|---|
| `ClusterConfig` record + read-only `cluster.id` mirror | **Keep.** Mismatch refusal still load-bearing — guards against operator pointing at wrong Mongo *or* wrong Raft data dir. |
| `cluster_meta` Mongo collection + `StateStore.getClusterId()`/`stampClusterId()` | **Delete.** Cluster identity moves into the Raft state machine. Mongo no longer holds it. |
| `PrexorCloudBootstrap.reconcileClusterIdentity()` | **Replace.** New responsibility: stand up or rejoin the Raft group, read clusterId from state machine, persist mirror to yaml. |
| JWT-secret rewrite preserves `networks`/`events` | **Keep.** Independent bugfix. |
| `Permission.CLUSTER_JOIN` | **Delete.** Replaced by `CLUSTER_VIEW` / `CLUSTER_CONFIG_WRITE` / `CLUSTER_MANAGE`. |
| `Role.EXCLUDED_FROM_DEFAULT_ADMIN` mechanism | **Keep.** Contents change: now `{CLUSTER_MANAGE}`. |
| `ClusterJoinTemplate` projection + tests | **Keep, repurpose.** Becomes `projectClusterSharedSubset` for the v1.0 → v1.1 migration. The unit tests remain the authoritative spec for what counts as cluster-shared. |
| `ClusterJoinRoutes` (`GET /api/v1/admin/cluster/join-template`) | **Delete.** Replaced by `/api/v1/cluster/join-tokens` and the gRPC `ClusterMembership` service. |
