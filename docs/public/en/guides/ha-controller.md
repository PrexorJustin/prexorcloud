---
title: Add a second controller for HA
description: Grow a single-controller install into a Raft-replicated HA cluster — prerequisites, join token, controller join, and a failover check.
---

A PrexorCloud Controller cluster replicates its control state through an embedded Apache Ratis Raft group. One Controller is the Raft leader; the rest are followers. Every cluster mutation — config patches, join tokens, member changes, leader-elected work leases — commits to the Raft log and replicates to all peers. If the leader dies, the survivors elect a new one and keep serving.

This guide takes a working single-Controller install to two Controllers: you issue a join token on the first, drop it on the second, and the second joins the Raft group on boot. Then you verify failover.

## Before you start

- A running v1.1+ Controller (`controller-1`). The cluster control plane is a v1.1 feature.
- A second host (`controller-2`) that can reach `controller-1` on its **Raft port** (default `9190/tcp`).
- Network reachability **both ways** between Controllers on the Raft port — Raft is peer-to-peer, not client-server.
- A REST/admin login on `controller-1` with the `cluster.manage` permission (to issue the token) and `cluster.view` (to inspect status).
- `prexorctl` on your workstation, logged in to `controller-1`.
- Roughly synced clocks (NTP). Join-token expiry is wall-clock; Raft elections are not clock-sensitive, but large skew makes logs hard to read.

What you do **not** need: a shared database. Each Controller keeps its own Raft data directory (`data/raft/` by default). The cluster state lives in the replicated Raft log, not in a shared store. There is no separate primary/standby config — every member runs the same binary and the same `controller.yml` shape.

## How the cluster works

| Concept | Where it lives | Notes |
|---|---|---|
| Raft group | All Controllers | Fixed group UUID `00000000-0000-0000-0000-707265786f72`. One group per install. |
| Cluster identity (`clusterId`) | Raft state (`ClusterMeta`) | Stamped on first boot; mirrored into `controller.yml` as `cluster.id`. |
| Cluster CA | Raft state | Minted in-memory on Day-0; signs each member's Raft mTLS leaf cert. Joiners receive it during join. |
| Seed secret | Raft state (`ClusterMeta`) | HMAC key for join tokens. Never leaves the cluster. Rotating it invalidates outstanding tokens. |
| Members | Raft state | One record per Controller: `nodeId`, `raftAddr`, `restAddr`, `gRPCAddr`, `label`. |
| Cluster-shared config | Raft state (versioned) | Seeded on first v1.1 boot from `controller.yml`, then distributed to joiners. |
| Leader-elected work leases | Raft state | Coarse singletons (audit pruner, DR drill runner) via `ClusterLeaseManager`. |

Reads from the local state machine are sequentially consistent — fast, correct for everything the dashboard does, but not guaranteed to reflect an uncommitted write on another follower in real time.

### Raft transport config

The Raft transport is node-local. It lives under `raft:` in `controller.yml` (record `RaftConfig`):

| Key | Default | Meaning |
|---|---|---|
| `raft.host` | `0.0.0.0` | Bind address for this node's Raft gRPC transport. |
| `raft.port` | `9190` | Raft transport port. Must be reachable from every other Controller. |
| `raft.dataDir` | `data/raft` | On-disk Raft storage (log + snapshots) for this node. |
| `raft.joinAddrs` | `[]` (empty) | gRPC endpoints of existing members for boot-time discovery. Empty means "first Controller of a new cluster, or restarting an existing member" — the bootstrap reads `data/raft/` to disambiguate. |

Cluster-wide tuning (snapshot retention, election timeout) is not in this record; it is internal to the Raft state machine.

## 1. Confirm controller-1 is a healthy single-member cluster

On your workstation, logged in to `controller-1`:

```bash
prexorctl cluster status
```

```
Cluster status
  Cluster ID              7b3f0c2e-...-a1
  Members                 1
  Active config version   1
  Created at              2026-06-01T09:12:44Z
```

`Members 1` is the expected starting point. `Active config version 1` means the v1.0→v1.1 migration seeded the cluster-shared config from `controller.yml` on first boot.

List the member to confirm its advertised addresses:

```bash
prexorctl cluster members
```

```
NODE ID        RAFT ADDR              REST ADDR              GRPC ADDR              LABEL   JOINED AT
controller-1   10.0.0.11:9190         10.0.0.11:8080         10.0.0.11:9090                 2026-06-01T09:12:44Z
```

The `RAFT ADDR` here is what `controller-2` dials to join. If it shows `0.0.0.0:9190`, the node advertised its bind address rather than a routable one — set `raft.host` to a routable address on `controller-1` and restart before continuing, otherwise the joiner has nothing to dial.

## 2. Issue a join token on controller-1

A join token is a single-use, HMAC-signed wire string. It carries the `clusterId` and the gRPC `joinAddrs` the new Controller dials. The wire format is `prexor-jt:v1:<base64url(payload)>.<base64url(hmac)>`. Issue it against any existing member:

```bash
prexorctl cluster join-token create \
  --join-addr 10.0.0.11:9190 \
  --label controller-2 \
  --ttl-seconds 3600
```

```
Cluster join token issued
  JTI         8f2a9c10-...-3d
  Token       prexor-jt:v1:eyJqdGkiOiI...   (long)
  Expires at  2026-06-07T15:00:00Z

⚠ This is the only time the token is shown. Save it now.
```

| Flag | Default | Notes |
|---|---|---|
| `--join-addr` | (required) | Raft gRPC `host:port` of an existing Controller. Repeat for multiple; the joiner dials the first reachable one. |
| `--label` | (none) | Human label recorded on the token and surfaced in `join-token list`. |
| `--ttl-seconds` | `86400` (24 h) | Token lifetime. Hard cap is 30 days (`2592000`). Out-of-range is rejected `400 BAD_TTL`. |

The token string is returned exactly once. The Controller stores only its `jti` and HMAC record in Raft, never the cleartext token. The REST surface is `POST /api/v1/cluster/join-tokens`, body `{ttlSeconds, label, joinAddrs}`, requires `cluster.manage`.

Inspect or revoke outstanding tokens:

```bash
prexorctl cluster join-token list
prexorctl cluster join-token revoke 8f2a9c10-...-3d
```

`list` shows `jti`, `label`, `status` (`OUTSTANDING` / `REDEEMED` / `EXPIRED` / `REVOKED`), and timestamps — never the token string.

## 3. Boot controller-2 in join mode

`controller-2` joins the Raft group on its **next boot** when a pending join token is present on disk. The trigger is a single file:

```
config/security/pending-join-token
```

The bootstrap (`startClusterControlPlane`) picks its branch from disk state:

| Disk state | Branch |
|---|---|
| `config/security/pending-join-token` exists and is non-empty | **Day-N join** — dial the cluster, redeem the token, persist cluster TLS material, enter the Raft group. |
| `config/security/cluster/` already populated (no pending token) | **Restart** of an existing member — load persisted TLS, replay the Raft log. |
| Neither | **Day-0** — mint a fresh cluster CA + identity, start a single-member group. |

So the procedure on `controller-2` is: install the Controller binary and its `controller.yml`, but **do not start it yet**; write the token; then start.

1. Install `controller-2` with its own `controller.yml`. Set node-local values:

   ```yaml
   # controller-2: config/controller.yml (node-local fields only)
   uuid: controller-2
   http:
     host: 10.0.0.12
     port: 8080
   grpc:
     host: 10.0.0.12
     port: 9090
   raft:
     host: 10.0.0.12      # routable address the other peers dial
     port: 9190
     dataDir: data/raft
   ```

   Leave `cluster.id` unset — the join writes it. Cluster-shared config (security, scheduler, modules, allowed subnets, …) is delivered by the join; you do not copy it by hand.

2. Write the token to the pending-join file (relative to the Controller's working directory):

   ```bash
   install -m 600 -D /dev/stdin config/security/pending-join-token <<'EOF'
   prexor-jt:v1:eyJqdGkiOiI...
   EOF
   ```

   The file must contain only the wire token. An empty file fails fast at boot with an explicit error.

3. Start `controller-2`:

   ```bash
   sudo systemctl start prexorcloud-controller   # or your run command
   ```

On boot, `controller-2`:

- Parses the token, dials the first `joinAddr`, and redeems it over gRPC (`ClusterMembership` join RPC).
- Receives the cluster CA and a CA-signed Raft leaf cert; persists them to `config/security/cluster/`.
- Brings up its Raft server in join mode and enters the group via Ratis joint consensus.
- Mirrors the joined `clusterId` into its `controller.yml`.
- Deletes `pending-join-token`.

The token is single-use server-side. If the join fails partway, the file stays in place — fix the cause and restart; the next attempt purges stale local state (`config/security/cluster/` and the Raft data dir) and retries cleanly. A retry against an **already-redeemed** token surfaces a write conflict; issue a fresh token instead.

Watch the join in the Controller log:

```
Found pending join token at config/security/pending-join-token — joining cluster as controller-2 (raft=10.0.0.12:9190, rest=10.0.0.12:8080, grpc=10.0.0.12:9090)
Joined cluster 7b3f0c2e-...-a1 as controller-2 with 1 existing peer(s); local TLS material persisted to config/security/cluster
Cluster join complete — deleted config/security/pending-join-token
```

## 4. Verify the cluster has two members

From your workstation:

```bash
prexorctl cluster status
```

```
Cluster status
  Cluster ID              7b3f0c2e-...-a1
  Members                 2
  Active config version   1
```

```bash
prexorctl cluster members
```

```
NODE ID        RAFT ADDR          REST ADDR          GRPC ADDR          LABEL          JOINED AT
controller-1   10.0.0.11:9190     10.0.0.11:8080     10.0.0.11:9090                    2026-06-01T09:12:44Z
controller-2   10.0.0.12:9190     10.0.0.12:8080     10.0.0.12:9090     controller-2   2026-06-07T14:05:12Z
```

Both `clusterId` values match, member count is 2, and `controller-2` shows the label you set on the token. Add `--json` to any of these for machine-readable output.

## 5. Verify failover

Failover means: kill the Raft leader and confirm a survivor takes over and still serves cluster operations.

1. Find the leader. With two members, the founder is usually still leader. Stop it:

   ```bash
   # on the leader host
   sudo systemctl stop prexorcloud-controller
   ```

2. Point `prexorctl` at the survivor and confirm cluster reads still work:

   ```bash
   prexorctl --controller https://10.0.0.12:8080 cluster status
   ```

   ```
   Cluster status
     Cluster ID              7b3f0c2e-...-a1
     Members                 2
     Active config version   1
   ```

   The survivor answers because Raft re-elected it leader. `Members 2` still shows both records — a stopped Controller is still a member; it has not been ejected.

3. Confirm a cluster **write** commits on the survivor. Issue and revoke a throwaway token (a Raft write that needs a live leader):

   ```bash
   prexorctl --controller https://10.0.0.12:8080 cluster join-token create \
     --join-addr 10.0.0.12:9190 --label failover-check --ttl-seconds 300
   prexorctl --controller https://10.0.0.12:8080 cluster join-token revoke <jti>
   ```

   Both succeeding proves the survivor holds the Raft leadership and can commit. If the write returns `503 RAFT_UNAVAILABLE`, the cluster lost quorum (see below).

4. Bring the stopped Controller back:

   ```bash
   sudo systemctl start prexorcloud-controller
   ```

   It restarts as an existing member, replays its Raft log, and rejoins as a follower. `prexorctl cluster status` returns `Members 2` from either Controller.

### Quorum and the two-node caveat

Raft needs a majority of members to commit a write. Cluster sizes and their tolerance:

| Members | Majority needed | Failures tolerated (writes still commit) |
|---|---|---|
| 1 | 1 | 0 |
| 2 | 2 | 0 |
| 3 | 2 | 1 |
| 5 | 3 | 2 |

A **two-member** cluster does not tolerate a member loss for *writes*: with one Controller down, the survivor cannot form a majority, so cluster mutations (token issue, member eject, config patch) return `503 RAFT_UNAVAILABLE`. Reads from the survivor's local state machine still work, and the rest of the Controller's REST/gRPC surface keeps serving. For write-side fault tolerance, run an **odd** number of Controllers, three or more. Two members buys you read availability and a warm peer, not write quorum during an outage.

## Day-2 operations

### Inspect leader-elected work leases

Coarse singletons (audit pruner, DR drill runner) are gated by Raft leases. The holders are exposed over REST at `GET /api/v1/cluster/leases` (`cluster.view`):

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  https://10.0.0.11:8080/api/v1/cluster/leases | jq .
```

Each entry shows `name`, `holder` (the Controller's `nodeId`), `grantedAt`, `ttlMillis`, `renewedAt`. A lease held by one Controller cannot be renewed by another — that is the singleton guarantee.

### Gracefully remove a Controller

To retire a Controller, have it leave the group, then decommission the host:

```bash
prexorctl --controller https://10.0.0.12:8080 cluster leave
```

The targeted Controller proposes `RemoveMember(self)` via Raft, then shuts down about a second later. Leave **refuses** (`409 LAST_MEMBER`) if it is the only member — a one-member cluster has no peer to take over.

### Force-eject a dead Controller

If a Controller is gone and cannot leave gracefully, eject it from a surviving member (needs quorum):

```bash
prexorctl cluster eject controller-2 --reason "host decommissioned"
```

This is irreversible — it removes the member from the Raft group. The CLI confirms first unless you pass `--yes`. REST: `DELETE /api/v1/cluster/members/{nodeId}` (`cluster.manage`).

### Rotate the join-token seed

Rotating the seed invalidates every outstanding join token at once:

```bash
prexorctl cluster seed rotate
```

Use it after a token may have leaked, or as a step in cluster recovery. The CLI confirms first unless you pass `--yes`. REST: `POST /api/v1/cluster/seed/rotate` (`cluster.manage`).

### Recover a degraded cluster

`prexorctl cluster recover` walks two scenarios:

- **Quorum preserved** (you lost no more than `floor((N-1)/2)` members): force-ejects the dead peers. Pass `--eject <nodeId,...>` or answer the interactive prompt.
- **Quorum lost** (majority gone): `prexorctl cluster recover --i-have-only-survivor` prints the offline single-survivor reset playbook. This is destructive filesystem surgery on a stopped Controller — back up `data/raft/` and `config/security/cluster/`, preserve the state-machine snapshot, sideline the broken log files, restart as a single-member group, rotate the seed, and grow back via fresh join tokens. The canonical procedure is [`docs/runbooks/recover-cluster.md`](https://github.com/PrexorJustin/prexorcloud/blob/main/docs/runbooks/recover-cluster.md). Anything that had not replicated to the survivor is lost.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `controller-2` log: "join token contains no joinAddrs" | The token was issued without `--join-addr`. Re-issue with at least one routable Raft `host:port`. |
| Join hangs, then times out awaiting leader | `controller-2` cannot reach `controller-1` on the Raft port, or the advertised `RAFT ADDR` is `0.0.0.0`. Set a routable `raft.host` on both sides; open the Raft port both ways. |
| Join fails with a write conflict on retry | The token was already redeemed (single-use). Issue a fresh token; the failed joiner purges its local state on the next attempt. |
| `pending-join-token exists but is empty` at boot | The file is present but blank. Write the wire token into it, or delete it to run a Day-0 bootstrap instead. |
| `cluster status` shows mismatched `clusterId` between nodes | `controller-2` was pointed at the wrong Raft data dir, or `cluster.id` in its `controller.yml` disagrees with the Raft state — the Controller refuses to boot and logs the mismatch. Restore the correct Raft data dir, or remove `cluster.id` from the yaml to adopt the existing Raft state's id. |
| Cluster writes return `503 RAFT_UNAVAILABLE` | Quorum is lost (a majority of members are down). Restore enough members, or run `prexorctl cluster recover`. |
| `cluster leave` returns `409 LAST_MEMBER` | You tried to leave a one-member cluster. There is no graceful path; use recovery tooling to tear it down. |

## Where to go next

- [Guides → Backup and restore](/guides/backup-and-restore/) — back up `data/raft/` and `config/security/cluster/` alongside Mongo.
- `prexorctl cluster --help` — full subcommand reference (`status`, `members`, `eject`, `leave`, `join-token`, `seed`, `recover`).
- [`docs/runbooks/recover-cluster.md`](https://github.com/PrexorJustin/prexorcloud/blob/main/docs/runbooks/recover-cluster.md) — the catastrophic-recovery playbook.
