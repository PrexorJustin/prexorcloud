---
title: Cluster model
description: The embedded Raft (Apache Ratis) control plane — cluster identity, members, leader leases, fencing, and what survives a controller restart.
---

PrexorCloud runs the Controller's cluster control plane on an embedded
[Apache Ratis](https://ratis.apache.org/) Raft group. Every Controller process is a
Raft peer. The Raft state machine holds the cluster's identity, its config history,
its member list, single-use join tokens, the cluster CA, and the coarse-grained
leader leases that gate cluster-singleton work.

This page is the mental model. Read it once, refer back when you add a Controller,
debug a lease holder, or reason about what comes back after a restart.

## What you'll learn

- What lives in the Raft state machine and what does not.
- How writes commit through Raft and how reads work today.
- How leader leases and lease holder identity gate cluster-singleton work.
- What survives a Controller restart, and how snapshots plus log replay rebuild state.
- The REST surface for inspecting cluster status, members, leases, config, and tokens.

## Two control planes, one product

Do not confuse the two lease systems. They solve different problems.

| Plane | Backed by | Granularity | Holds |
|---|---|---|---|
| Cluster control plane | Embedded Raft (Ratis) | Coarse cluster singletons | Cluster identity, config versions, members, join tokens, CA, leader leases |
| Coordination store | Valkey / Redis (`DistributedLeaseManager`) | Fine-grained (per-group, per-node, per-instance) | Scheduler placement leases, drain reconcile leases, healing leases, rate limits, SSE replay |

The Raft plane is for work where exactly one Controller in the whole cluster must
act, and where Raft's commit latency is a rounding error against the work itself —
the audit pruner, the deployment-reconciliation gate, the DR drill runner. Fine-grained
partitioned work (per-instance placement, per-node drains) stays on the Valkey-based
`DistributedLeaseManager`, because routing each tick through Raft consensus would cost
more than the work. The policy split is in
[`docs/engineering/cluster-join-plan.md`](https://github.com/prexorjustin/prexorcloud/blob/main/docs/engineering/cluster-join-plan.md).

This page covers the Raft plane.

## The Raft state machine

`ClusterControlStateMachine` is the single source of truth for cluster control state.
It is a Ratis `BaseStateMachine`. Every write is a `ClusterEntry` appended to the Raft
log and applied in commit order on every peer; every read is a direct snapshot of the
peer's local in-memory projection.

The state machine holds seven typed projections:

| Projection | Type | Holds |
|---|---|---|
| Cluster meta | `ClusterMeta` | `clusterId`, the base64 join-token seed secret, `createdAt`, schema version |
| Config versions | `NavigableMap<Integer, ClusterConfigVersion>` | Append-only history of cluster-shared config patches |
| Active config version | `int` | Which config version is currently effective (`0` = none) |
| Members | `Map<String, Member>` | One `Member` per Controller peer (Raft/REST/gRPC addresses, join + last-seen times) |
| Join tokens | `Map<String, JoinToken>` | Single-use cluster join tokens keyed by `jti` |
| Leases | `Map<String, Lease>` | Named leader leases |
| Cluster files | `Map<String, ClusterFile>` | Binary blobs — notably the cluster CA cert and key |

`ClusterControlPlane` is the typed façade over the state machine. Writes go through Raft
(`raft.submitRaw(...)`); reads return immutable snapshots from the local projection.

### Cluster identity

Identity is the `ClusterMeta` singleton: a `clusterId` (a UUID), a base64 seed secret,
a creation timestamp, and a schema version (`CURRENT_SCHEMA_VERSION = 1`).

- On first-ever boot the Controller stamps a fresh `clusterId` (or adopts
  `cluster.id` from `controller.yml` if you set it) plus a random 32-byte seed secret.
- The seed secret is the HMAC key that signs join tokens. It never appears in any REST
  response or audit log.
- On restart the Controller cross-checks `controller.yml`'s `cluster.id` against the
  `clusterId` in Raft state. A mismatch refuses to boot:

  ```
  Configured cluster.id=<yaml> but Raft state holds cluster.id=<raft>.
  Either restore the original Raft data dir, or remove cluster.id from
  controller.yml to adopt this Raft state's existing id.
  ```

The Raft group itself uses a fixed well-known UUID,
`00000000-0000-0000-0000-707265786f72`. That is Ratis bookkeeping, not the cluster's
semantic identity — the constant lets peers discover each other without out-of-band
configuration. The cluster's real identity is the `clusterId` inside `ClusterMeta`.

## Configuration

The Raft transport is node-local and lives under `raft` in `controller.yml`:

```yaml
raft:
  host: 0.0.0.0          # bind host for the Ratis gRPC transport
  port: 9190             # bind port
  dataDir: data/raft     # Raft log + snapshot storage
  joinAddrs: []          # gRPC endpoints of existing members, used at boot to discover the cluster
```

Defaults (`RaftConfig`):

| Key | Default | Meaning |
|---|---|---|
| `raft.host` | `0.0.0.0` | Address the Ratis transport binds to |
| `raft.port` | `9190` | Port for inter-peer Raft RPC |
| `raft.dataDir` | `data/raft` | On-disk Raft storage (log + snapshots) |
| `raft.joinAddrs` | `[]` | Existing members to dial at boot |

An empty `joinAddrs` means "I'm the first Controller of a new cluster, or I'm
restarting an existing member." The bootstrap reads the on-disk Raft data dir to
disambiguate: a formatted group directory under `dataDir` means restart; absence means
fresh bootstrap.

Cluster-wide tuning (config history, lease semantics) lives in the state machine, not
in `RaftConfig`. `RaftConfig` only describes how this node binds and finds peers.

Cluster identity is configured under `cluster`:

```yaml
cluster:
  id: ~          # optional; pins this Controller to a specific cluster
  joinedFrom: ~  # informational, written by the join wizard
  joinedAt: ~    # informational
```

## Writes, reads, and conflicts

### Writes commit through Raft

Every mutation is a `ClusterEntry` submitted to the leader, replicated, and applied in
commit order on each peer. `ClusterControlPlane` exposes typed methods that wrap the
entries: `setClusterMeta`, `proposeConfigPatch`, `rollbackConfig`, `addMember`,
`removeMember`, `touchMember`, `writeJoinToken`, `redeemJoinToken`, `revokeJoinToken`,
`grantLease`, `renewLease`, `releaseLease`, `writeClusterFile`, `deleteClusterFile`.

The apply step is deterministic and serialised by Ratis — one writer, applied identically
on every peer. After each successful apply, a commit listener fires on every Controller;
the membership reconciler and the EventBus bridge subscribe to it. The config-version and
rollback entries fan out as a `ClusterConfigChangedEvent` on the local EventBus.

### Reads are sequentially consistent

Reads do not go through Raft consensus. `ClusterControlPlane` read methods
(`listMembers`, `getLeases`, `getActiveConfigVersion`, …) return immutable snapshots of
the local peer's projection. This is fast and correct for everything the dashboard does,
but it does not guarantee real-time visibility of a write that just committed on a
different peer. Linearizable reads (Ratis ReadIndex) are not wired today.

### Conflict-checked writes

Some writes are conflict-checked in the apply step and reject with a typed code. The
façade raises a `ClusterWriteConflict` carrying that code; REST surfaces it (typically as
`409`). The codes you will see:

| Code | Raised by | Meaning |
|---|---|---|
| `CLUSTER_ID_MISMATCH` | `SetClusterMeta` | Refusing to overwrite an existing `clusterId` |
| `NO_CLUSTER_META` | `RotateSeed` | Seed rotation before identity was stamped |
| `VERSION_NOT_NEXT` | `WriteConfigVersion` | Proposed version is not `max + 1` (a concurrent writer won the race) |
| `PARENT_VERSION_STALE` | `WriteConfigVersion` | `parentVersion` is not the current active version |
| `PARENT_VERSION_INVALID` | `WriteConfigVersion` | First version must declare `parentVersion=0` |
| `VERSION_UNKNOWN` | `SetActiveConfigVersion` | Rollback target version does not exist |
| `MEMBER_UNKNOWN` | `RemoveMember` / `TouchMember` | No such member |
| `JTI_COLLISION` | `WriteJoinToken` | Same `jti`, different payload |
| `TOKEN_UNKNOWN` / `TOKEN_REVOKED` / `TOKEN_ALREADY_REDEEMED` / `TOKEN_EXPIRED` | `RedeemJoinToken` | Single-use token guard rails |
| `LEASE_HELD` | `GrantLease` | A different holder owns a still-valid lease |
| `LEASE_UNKNOWN` / `LEASE_NOT_HELD` | `RenewLease` / `ReleaseLease` | You do not hold the lease you are renewing/releasing |

When Raft itself is unreachable, REST mutation routes return `503 RAFT_UNAVAILABLE`.

## Leader leases

A `Lease` is a named claim guaranteeing that only one Controller in the cluster runs a
piece of singleton work at a time. The record:

```java
record Lease(String name, String holder, Instant grantedAt, long ttlMillis, Instant renewedAt)
```

Validity is `now < renewedAt + ttlMillis`. A holder that fails to renew before TTL
expiry implicitly releases the lease — any other Controller can then `GrantLease` itself.

### Lease holder identity is the fencing mechanism

Every Controller constructs its `ClusterLeaseManager` with its own `holderId` (the
Controller's UUID, `config.uuid()`). The state machine compares holder strings on grant,
renew, and release:

- **Grant** — if a different holder owns a still-valid lease, the apply rejects with
  `LEASE_HELD`. A self-grant of your own still-valid lease collapses to success (you keep
  it).
- **Renew** — only the recorded holder can renew. A renew from any other holder rejects
  with `LEASE_NOT_HELD`; an unknown lease rejects with `LEASE_UNKNOWN`.
- **Release** — only the holder can release; release of a non-existent lease is an
  idempotent success.

This is the write-safety guarantee. Because every grant/renew/release is a Raft-committed,
deterministically-applied entry, two Controllers cannot both believe they hold the same
lease. The holder check on renew means a Controller that lost its lease (TTL expired,
another peer took it) finds out on its next renew — `tryRenew` returns `false`, and the
caller stops the protected work. There is no separate fencing token: lease ownership is
itself the fence, enforced by single-writer Raft apply.

### Using a lease

`ClusterLeaseManager` surfaces lease contention as quiet booleans, not exceptions —
contention is structurally expected when N Controllers race on each tick:

| Method | Returns | Behavior |
|---|---|---|
| `tryAcquire(name, ttl)` | `boolean` | `true` if you now hold it (or re-acquired your own valid lease); `false` on contention or Raft unavailable |
| `tryRenew(name)` | `boolean` | `true` on success; `false` if you no longer hold it |
| `release(name)` | `void` | Best-effort; rejections swallowed (callers usually release in a `finally`) |
| `runUnderLease(name, ttl, work)` | `boolean` | Acquire → run → release; `true` if the work executed, `false` if another Controller held the lease and you skipped. Releases even if `work` throws |

A skipped tick is silent by design — one log line per skipped tick per Controller would
be noise. Raft-unavailable failures are logged at debug; the next scheduled tick retries.

### What is leased today

| Lease name | Used by | TTL |
|---|---|---|
| `audit-pruner` | Audit-log retention prune (one Controller per tick) | 1 hour |
| (scheduler gate) | Deployment reconciliation loop — gates which Controller iterates `IN_PROGRESS` deployments | scheduler-configured |

The bootstrap wires the audit pruner with `runUnderLease("audit-pruner", 1h, …)` on a
daily schedule, and gives the scheduler its own `ClusterLeaseManager` for the deployment
reconciliation gate. Both construct with `config.uuid()` as the holder. The `Lease`
record names the DR drill runner and deployment reconciler as the same class of
leader-elected work.

Inspect current lease holders at `GET /api/v1/cluster/leases`.

## Membership

A `Member` is one Controller peer. The state machine member list and the Ratis Raft
group membership are kept in sync by the `MembershipReconciler`: every committed
`AddMember`/`RemoveMember` wakes a background thread that — only if this Controller is the
current Raft leader — calls `setConfiguration` with the current member list. The change is
a Ratis joint-consensus membership change, and it is idempotent (same membership is a
no-op).

Each `Member` carries:

| Field | Meaning |
|---|---|
| `nodeId` | The Controller's UUID |
| `raftAddr` | Address other Ratis peers use to reach this Controller |
| `restAddr` / `gRPCAddr` | Advertised REST and gRPC bind addresses for tooling and Daemons |
| `label` | Human label |
| `joinedAt` / `lastSeen` | Timestamps |

### Joining a cluster

A Day-N joiner does not edit config and restart blindly. The join flow:

1. The operator mints a single-use join token on an existing Controller
   (`POST /api/v1/cluster/join-tokens`). The token is HMAC-signed with the cluster seed
   secret and carries the existing members' join endpoints and an expiry.
2. The joiner dials the first `joinAddr` in the token, redeems the token over the
   `ClusterMembership` gRPC service, and receives a cluster-CA-signed leaf certificate
   plus the current peer list.
3. The joiner persists its TLS material locally, brings up Raft in join mode, and calls
   `GroupManagementApi.add()` on itself. The leader then expands the Raft group via
   joint consensus; the joiner's state machine fills in by Ratis `InstallSnapshot`.

The token is single-use server-side. A retry that gets past redemption surfaces
`TOKEN_ALREADY_REDEEMED`. A half-failed join is recoverable: restart purges the local TLS
material and Raft data dir and retries from scratch.

### Leaving a cluster

`POST /api/v1/cluster/leave` proposes `RemoveMember(self)` and then triggers the
Controller's shutdown latch (delayed ~1s so the HTTP response and audit write flush
first). Leave refuses with `409 LAST_MEMBER` if this is the only Controller — a
one-member cluster has no peer to take over, so tear-down needs recovery tooling, not
graceful leave.

`DELETE /api/v1/cluster/members/{nodeId}` force-ejects a member. It returns
`404 MEMBER_NOT_FOUND` for an unknown `nodeId`.

## What survives a restart

The Raft control plane is durable on disk under `raft.dataDir`. On restart, Ratis loads
the latest snapshot and replays the log delta to the tip. Everything in the state machine
comes back:

| State | Survives Controller restart? | How it is rebuilt |
|---|---|---|
| Cluster identity (`ClusterMeta`) | Yes | Snapshot + log replay |
| Config version history + active version | Yes | Snapshot + log replay |
| Members | Yes | Snapshot + log replay; Raft membership re-reconciled by leader |
| Join tokens (incl. redeemed/revoked state) | Yes | Snapshot + log replay |
| Cluster CA cert + key | Yes | Stored as cluster files in the state machine; restarts and joiners load it from there (no on-disk CA keystore) |
| Leases | Yes (state), but TTL-bounded | Restored from snapshot/log; an expired lease is reclaimable by any Controller after `renewedAt + ttlMillis` |

### Snapshots and log replay

`ClusterControlStateMachine.takeSnapshot()` serialises the full state to a single JSON
file under the Ratis snapshot directory and records its `(term, index)`. On
`initialize()`, the state machine loads the latest snapshot, then Ratis replays the log
from the snapshot's last-applied index to the live tail.

A freshly joining follower receives the leader's snapshot via Ratis `InstallSnapshot`:
the state machine pauses, reloads the just-installed snapshot, and resumes. Trigger a
snapshot from code with `ClusterControlPlane.takeSnapshot()`.

### What a restart does NOT restore from Raft

The Raft plane holds control state only. Live operational state — running Instances,
connected Daemons, players, console buffers — is not in Raft. That state is rebuilt from
MongoDB plus gRPC reconciliation when Daemons reconnect, and ephemeral coordination state
(rate limits, SSE replay, fine-grained scheduler leases) lives in Valkey. The Raft plane
answers "what is this cluster, who is in it, and who holds the singletons," not "what is
running right now."

## Inspecting the cluster

Every cluster route requires a permission:

- **Read** (`/api/v1/cluster`, `/members`, `/leases`, `/config`, `/config/versions`) —
  `cluster.view` (`CLUSTER_VIEW`).
- **Membership mutation** (eject, leave) — `cluster.manage` (`CLUSTER_MANAGE`).
- **Config write** (propose patch, rollback) — `cluster.config.write`
  (`CLUSTER_CONFIG_WRITE`).
- **Join tokens and seed rotation** (mint, list, revoke, rotate) — `cluster.manage`. Even
  listing tokens needs `cluster.manage`, since a token list is sensitive.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/cluster` | Cluster id, created-at, schema version, member count, active config version |
| `GET` | `/api/v1/cluster/members` | Member list (sorted by `nodeId`) |
| `GET` | `/api/v1/cluster/leases` | Current lease holders: `name`, `holder`, `grantedAt`, `ttlMillis`, `renewedAt` |
| `DELETE` | `/api/v1/cluster/members/{nodeId}` | Force-eject a member |
| `POST` | `/api/v1/cluster/leave` | Graceful self-removal |
| `GET` | `/api/v1/cluster/config` | Currently active config patch (masked) |
| `GET` | `/api/v1/cluster/config/versions` | Config version metadata list |
| `GET` | `/api/v1/cluster/config/versions/{version}` | One version (masked) |
| `POST` | `/api/v1/cluster/config` | Propose a new config patch |
| `POST` | `/api/v1/cluster/config/rollback` | Roll the active version back to an earlier one |
| `POST` | `/api/v1/cluster/join-tokens` | Mint a single-use join token |
| `GET` | `/api/v1/cluster/join-tokens` | List outstanding tokens (jti + metadata; never the secret) |
| `DELETE` | `/api/v1/cluster/join-tokens/{jti}` | Revoke a token |
| `POST` | `/api/v1/cluster/seed/rotate` | Rotate the join-token seed secret |

### Worked example: inspect leases

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  https://controller.example:8443/api/v1/cluster/leases | jq
```

```json
{
  "leases": [
    {
      "name": "audit-pruner",
      "holder": "7f3c1a90-2b44-4e10-9d77-0c5e1f9a2b88",
      "grantedAt": "2026-06-07T02:00:00Z",
      "ttlMillis": 3600000,
      "renewedAt": "2026-06-07T02:00:00Z"
    }
  ]
}
```

The `holder` is the UUID of the Controller currently running the audit prune. Cross-
reference it against `GET /api/v1/cluster/members` to see which host that is.

## Config cheat sheet

```yaml
cluster:
  id: ~                  # optional; pins this Controller to one cluster
raft:
  host: 0.0.0.0          # default
  port: 9190             # default
  dataDir: data/raft     # default; Raft log + snapshots live here — back it up
  joinAddrs: []          # existing members to dial at boot
```

:::tip[Back up the Raft data dir]
The cluster CA private key, the join-token seed secret, and your config history all live
in the Raft state under `raft.dataDir`. Snapshot-driven recovery only works if that
directory survives. Treat it like any other durable store.
:::

## Related

- [`docs/engineering/cluster-join-plan.md`](https://github.com/prexorjustin/prexorcloud/blob/main/docs/engineering/cluster-join-plan.md) — the phased design and the coarse-vs-fine lease policy.
- [`docs/engineering/ratis-spike.md`](https://github.com/prexorjustin/prexorcloud/blob/main/docs/engineering/ratis-spike.md) — the multi-peer spike findings (join ordering, snapshot install).
