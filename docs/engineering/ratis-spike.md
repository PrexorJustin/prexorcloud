# Ratis spike — findings for Phase 4 (gRPC membership + TLS bootstrap)

**Date:** 2026-05-31
**Ratis version:** 3.1.3
**Branch:** main (post phase 7)
**Test driver:** `java/cloud-controller/src/test/java/me/prexorjustin/prexorcloud/controller/cluster/raft/RatisMultiPeerSpikeTest.java`
**Run with:** `./gradlew :cloud-controller:spikeTest` — excluded from the default
`test` task via JUnit tag `spike` (see `cloud-controller/build.gradle.kts`).

## What this spike answered

Phase 4 of [cluster-join-plan.md](cluster-join-plan.md) lands the gRPC
`ClusterMembership` service, join-token-bound CSR signing, and member-add via
Ratis joint consensus. Before writing prod code, we needed concrete answers to
four open Ratis questions. Both spike tests pass; total runtime ~8.5s.

---

## A — Membership change API

**Question:** what's the exact API for adding a controller to an existing Raft
group? `GroupManagementApi.add()` vs `AdminApi.setConfiguration()` vs both?

**Answer:** both, in this order.

```java
// 1. New peer's server is built without an initial group:
RaftServer newServer = RaftServer.newBuilder()
        .setServerId(newPeerId)
        .setProperties(props)
        .setStateMachine(sm)
        // no .setGroup(...) — joined dynamically below
        .build();
newServer.start();

// 2. On the NEW peer's client: tell that server "you now belong to this group".
//    This formats the new peer's Raft storage and primes it to accept entries.
//    Second arg = format-on-add. Always true for a fresh join.
newPeerClient.getGroupManagementApi(newPeerId)
        .add(currentGroupIncludingNewPeer, true);

// 3. On the EXISTING leader's client: commit the membership change via joint
//    consensus. Ratis runs C_old,new → C_new internally.
leaderClient.admin().setConfiguration(
        List.of(p1.peer, p2.peer, p3.peer, newPeer.peer));
```

Order matters: `add()` must come first. If `setConfiguration()` lands before
`add()`, the leader starts appending to a server that doesn't yet think it's a
group member, log replication stalls, and joint consensus times out before it
can be committed.

**Phase 4 implication:** the `ClusterMembership.RequestJoin` gRPC handler on the
existing leader does two things in sequence:
1. RPC-call the joining peer's Ratis `GroupManagementApi.add(group, true)` —
   this is a *Ratis* call to the joining peer's server, distinct from our own
   gRPC service. The leader needs a `RaftClient` for the joining peer's
   `RaftPeerId` to make this call.
2. Locally call `localRaftClient.admin().setConfiguration([...allPeers, joiner])`
   to commit the membership change.

The leader's `RaftClient` must include the joiner's address when calling
`setConfiguration` — otherwise it can't route subsequent log appends to it. The
spike rebuilds clients each time membership changes; production code should
maintain a `MembershipAwareRaftClient` wrapper that rebuilds on every
`AddMember`/`RemoveMember` apply.

## B — Snapshot transfer to a behind peer

**Question:** does Ratis ship snapshot bytes automatically when a new peer
joins behind the leader's compaction point, or do we need custom machinery?

**Answer:** yes, automatic. The leader's `GrpcLogAppender` checks
`followerNextIndex` vs `logStartIndex` and switches to the `InstallSnapshot`
RPC when the follower is behind the log start. From the test's log:

```
controller-1@group->controller-4-GrpcLogAppender:
    followerNextIndex = 0 but logStartIndex = 0, send snapshot
    SingleFileSnapshotInfo(t:1, i:149):[.../snapshot.1_149] to follower
```

The follower's `SnapshotManager` receives the chunks, atomically renames the
temp dir to the state-machine storage location, then triggers
`StateMachineUpdater.reloadStateMachine()`.

**🚨 Spike found a real production bug.** Until this spike, the state machine
inherited `pause()`/`reinitialize()` from `BaseStateMachine` (both no-ops) and
left its lifecycle in `NEW`. The follower-side reload path then crashed:

```
StateMachineUpdater.reload():
    Preconditions.assertTrue(stateMachine.getLifeCycleState() == PAUSED);  // boom
```

Worse, `NEW → PAUSING` is itself an illegal lifecycle transition, so even
overriding `pause()` to drive the transition isn't enough — the state machine
must first transition `NEW → STARTING → RUNNING` in `initialize()`.

The fix is in `ClusterControlStateMachine` on this branch (commit added with
the spike):

```java
@Override
public void initialize(...) throws IOException {
    super.initialize(server, groupId, raftStorage);
    storage.init(raftStorage);
    loadLatestSnapshot();
    getLifeCycle().transition(LifeCycle.State.STARTING);
    getLifeCycle().transition(LifeCycle.State.RUNNING);
}

@Override
public void pause() {
    getLifeCycle().transition(LifeCycle.State.PAUSING);
    getLifeCycle().transition(LifeCycle.State.PAUSED);
}

@Override
public void reinitialize() throws IOException {
    loadLatestSnapshot();
    getLifeCycle().transition(LifeCycle.State.STARTING);
    getLifeCycle().transition(LifeCycle.State.RUNNING);
}
```

Without this fix, **no Phase 4 join would ever succeed** — every joining
controller would die during the very first InstallSnapshot. The single-node
tests in `ClusterControlPlaneTest` didn't catch it because they never trigger
the follower-side snapshot path. This is the spike's primary deliverable.

**Phase 4 implication:** snapshot transfer is fully transparent — no custom
gRPC streaming on top of Ratis is needed. `SimpleStateMachineStorage` +
`SingleFileSnapshotInfo` (our current setup) is sufficient. Joint-consensus
behaviour during a join is "leader sees `nextIndex < logStartIndex`, sends
snapshot, then resumes appending log entries from the snapshot's index+1."

## C — TLS plumbing

**Question:** can we hand Ratis programmatic key+cert material at runtime, or
must we write keystore files?

**Answer:** programmatic, via `GrpcTlsConfig`:

```java
GrpcTlsConfig tls = new GrpcTlsConfig(
        leafPrivateKey,            // java.security.PrivateKey
        leafCertificate,           // java.security.cert.X509Certificate
        List.of(caCertificate),    // trust store
        true);                     // mtlsEnabled

Parameters params = new Parameters();
GrpcConfigKeys.Server.setTlsConf(params, tls);
GrpcConfigKeys.Client.setTlsConf(params, tls);
GrpcConfigKeys.Admin.setTlsConf(params, tls);

RaftServer server = RaftServer.newBuilder()
        .setServerId(id).setProperties(props).setParameters(params)
        .setStateMachine(sm).build();
RaftClient client = RaftClient.newBuilder()
        .setProperties(props).setParameters(params)
        .setRaftGroup(group).build();
```

Three separate TLS slots — `Server`, `Client`, `Admin` — all of which must be
populated when mTLS is on; otherwise the side without a config opens an
insecure channel and the handshake fails. Our spike sets all three to the same
config because the same peer is acting as all three roles.

`GrpcTlsConfig` also has constructors accepting `(File, File, File, boolean)`
and `(KeyManager, TrustManager, boolean)`, but the programmatic
`(PrivateKey, X509Certificate, List<X509Certificate>, boolean)` form is what
production code wants — we can sign the leaf cert against the cluster CA
in-process and never touch disk.

**Phase 4 implication:** the bootstrap CSR-signing flow becomes:
1. New controller starts a **temporary** Ratis server with no TLS (or with a
   self-signed bootstrap cert), used only to handshake with the cluster's
   `ClusterMembership` service over the controller's *normal* gRPC port.
2. `ClusterMembership.RequestJoin` runs over the controller's main gRPC (not
   Ratis), carrying the join-token-bound CSR. Existing controller TLS material
   covers that channel.
3. The leader signs the CSR with the cluster CA (held in `clusterFiles` Raft
   state), returns the signed leaf + CA cert.
4. The joining controller tears down its bootstrap Ratis server, rebuilds with
   `GrpcConfigKeys.{Server,Client,Admin}.setTlsConf(params, productionTls)`,
   then runs the membership-change dance from finding A.

The CA itself lives in the Raft state machine (`clusterFiles` sub-state in
plan.md). On Day 0 the bootstrapping controller generates the CA in-memory and
proposes a `WriteClusterFile` entry. All subsequent controllers pull the CA
material from the state machine after join.

## D — Port co-location vs dedicated

**Question:** can Ratis share an existing gRPC `Server` with our controller's
business-logic gRPC services, or does it own its own port?

**Answer:** dedicated. Ratis exposes
`GrpcConfigKeys.Server.setPort(props, port)` but provides no API to bind its
gRPC services onto a foreign Netty server or share a channel with caller code.
The `GrpcFactory` creates and owns its own `io.grpc.Server` instance per Ratis
server, and the only knobs are host/port/TLS.

Confirms the plan's lean toward a separate `raft.port` in `controller.yml`:

```yaml
grpc:
  host: 0.0.0.0
  port: 9090           # existing controller gRPC (modules, daemon control)
raft:
  host: 0.0.0.0
  port: 9091           # NEW — dedicated Raft port (3.x onwards)
  joinAddrs: []
```

Operational benefits of dedicated:
- Raft traffic is observably separable (firewall, metrics, capture).
- The controller's main gRPC port can keep its existing TLS material; Raft
  uses cluster-CA-signed material from the state machine.
- Trivial to firewall: `raft.port` only needs to be reachable peer-to-peer,
  never from clients or daemons. `grpc.port` stays open as today.

## What still wasn't covered (deliberate)

These are real Phase 4 concerns the spike did NOT exercise, intentionally:

- **Lost-volume recovery.** A first attempt at "wipe peer-2's storage, restart
  same node, expect catchup" got stuck because the leader's view of peer-2's
  `nextIndex` is sticky across the wipe — it keeps trying to append from index
  170 to a server that has no log. The fix is a proper remove-then-readd
  cycle, which is a separate question (covered by Phase 11 — recovery
  tooling). The spike pivoted to "fresh peer-4 joins after snapshot" instead,
  which is the canonical Phase 4 join scenario.
- **Leader election under partition.** Multi-peer election works (the spike
  exercises it during cluster startup), but we didn't simulate partitions or
  follower failure during a write. Phase 4 doesn't need this; Phase 8 leases
  do.
- **Snapshot chunking limits.** Default `INSTALL_SNAPSHOT_REQUEST_ELEMENT_LIMIT`
  is 8 and chunk-size is 16MB. Our state machine snapshots are tiny (config +
  members + tokens), so chunking is irrelevant. Re-evaluate if anything large
  ever lands in Raft.
- **TLS hot-rotation.** `GrpcTlsConfig` is set once at `Parameters` build
  time. Rotating the cluster CA means a rolling controller restart. Acceptable
  for v1.1; document the runbook.

## Phase 4 implementation order (suggested)

Based on the spike findings, here's the order I'd write Phase 4:

1. **Cluster CA in the state machine.** Add `clusterFiles` projection to
   `ClusterControlStateMachine`, plus `WriteClusterFile`/`DeleteClusterFile`
   entries. Day-0 bootstrap generates the CA in-memory and writes it via a
   single committed entry.
2. **`ClusterMembership` gRPC service.** Define `controller.proto`'s
   `ClusterMembership` service with `RequestJoin(token, raftAddr, csr)` →
   `(signedCert, caCert, snapshotRef)`. Implementation: validate token's HMAC
   against `seedSecret`, redeem via Raft, sign CSR with cluster CA.
3. **Bootstrap Ratis server rebuild.** Refactor `RaftBootstrap` so the same
   class can either bootstrap a new group (Day 0) or join an existing one
   (Day N). The join path:
   a. Build a temporary `RaftClient` pointing at the join peer's
      `joinAddrs[]`; no TLS.
   b. Call `ClusterMembership.RequestJoin` over our main controller gRPC.
   c. Receive signed cert + CA. Build production `GrpcTlsConfig` from them.
   d. Build `RaftServer` with TLS-enabled `Parameters` and no initial group.
   e. Call `GroupManagementApi.add()` on self to enter the group.
   f. Server receives snapshot from leader (which we know works after this
      spike's fix), catches up via log delta.
4. **Membership-aware `ClusterControlPlane`.** Track current members and
   rebuild the `RaftClient` (with current peer list) whenever an
   `AddMember`/`RemoveMember` apply fires. The current single-peer client
   won't survive a real cluster.

## Spike test as regression

`RatisMultiPeerSpikeTest` stays in the tree under tag `spike`, excluded from
the default test task. Run it explicitly via
`./gradlew :cloud-controller:spikeTest` whenever Ratis is upgraded — its two
tests cover the bulk of Phase 4's correctness assumptions. If either test
regresses after a Ratis bump, dig in before merging.
