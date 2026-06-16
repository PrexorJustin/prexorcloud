# Issue #22 — controller join-lifecycle: the joiner's Raft division won't catch up

**Status: open engineering task. Root-caused, partially fixed; the core needs a Ratis-lifecycle
change.** Captured during the v1.1 HA live acceptance run (see `northstar-plan.md`, Part 8). This note
is the scoped hand-off for closing #22 — it records what was proven, what is already fixed, what was
tried and reverted, and the candidate designs with their trade-offs. Nothing in "Candidate fixes" is
implemented yet.

## Problem

Joining a controller to the embedded Ratis quorum is unreliable. The documented bring-up requires a
manual "dance" — start the joiner, restart the joiner, restart the leader — and even then it is
**non-deterministic**: sometimes the joiner's state machine syncs and the member count reaches `N`,
sometimes the joiner hangs at boot and never becomes a healthy follower. This blocks forming (and
re-forming) a multi-controller quorum without hands-on babysitting.

Observed live, repeatedly, on a clean rejoin:

1. Joiner boots in join mode → `ClusterJoinFlow` succeeds (gRPC `RequestJoin` against the leader,
   gets the cluster-CA leaf + peer list) → `RaftBootstrap.startInJoinMode` → `joinExistingGroup`
   (`GroupManagementApi.add(group, true)`) → `Controller ready` (REST up) **but `memberCount: 0`**:
   the joiner's SM has not synced the cluster state.
2. The leader's `MembershipReconciler` repeatedly retries `setConfiguration([…, joiner])` but it makes
   **NOPROGRESS** — the joiner never acknowledges.
3. The leader's `APPEND_ENTRIES` to the joiner are rejected:
   `ServerNotReadyException: <joiner>@group-… is not in [RUNNING]: current state is STARTING`,
   `initializing? true`.
4. The SM-level `AddMember(joiner)` *does* commit on the leader, so the leader's member list shows the
   joiner — but the Ratis **group configuration** does not include it (the `setConfiguration` never
   committed). The result is a **phantom member**: `memberCount` reads `N` while the Ratis group is
   still `N-1`, so the leader keeps committing writes with the smaller quorum. `prexorctl cluster eject
   <phantom-nodeId>` cleans it.

## Root cause

The joiner's Raft **division rejects the leader's `APPEND_ENTRIES`/`InstallSnapshot`** because the
division is stuck `initializing` (not `RUNNING`). With no log/snapshot transfer, the SM never syncs,
so `setConfiguration` (joint consensus) can't reach the new-config majority and never commits.

Why production hits this but the spike test does not:

- `RatisMultiPeerSpikeTest` (passes, `:cloud-controller:spikeTest`) drives `setConfiguration`
  **immediately** after `add()`, from the leader, in the same thread — the joiner's freshly-added
  division acks while it is still healthy.
- Production **defers** `setConfiguration` to the async `MembershipReconciler` (woken by the
  `AddMember` commit, retried on a backoff). By the time it fires, the join-mode division has degraded
  (the "terminated appendEntries executor" state). The reconciler then retries for ~15 s and gives up.

The manual restart "dance" works *sometimes* because restarting the joiner re-creates the division in
**restart mode** (`raft.start`, `isRestart=true`), which `RatisMultiPeerSpikeTest.followerRestartSeesLeader`
shows is a healthy follower division that *can* accept `APPEND_ENTRIES` — but only if the leader
re-runs `setConfiguration` (hence "restart the leader" too), and only if the timing lines up.

## Already fixed (shipped, commit `22515f0`)

A latent **cluster-identity fork** found while investigating: on the dance's restart-mode boot, a
joiner whose SM had not synced would fall into `reconcileClusterIdentity`'s "no meta → stamp fresh
identity" branch and **mint a new `clusterId` + seed**, then hang the boot on the patient write. Two
joiners could fork the cluster's identity. Fixed: `reconcileClusterIdentity` now keys off
`RaftBootstrap.wasFreshBootstrap()` — only a genuine Day-0 boot stamps; a restart/joiner **awaits the
leader's meta** (`awaitClusterMeta`, 30 s) and continues degraded **without stamping**. This removes
the corruption and the stamp-hang, but does **not** make the joiner's division catch up — the boot
then simply waits/degrades instead. Unit-tested in `ClusterControlServiceTest`.

(Prior join fixes for context — committed earlier on `ha-enablement`: #19 `awaitLeaderBestEffort`
so a restart into a quorumless group doesn't `System.exit`; #20 SAN issuance tolerant of bad IPv6;
#21 idempotent startup reconcile kick + leader-settle tolerance.)

## Tried and reverted

**Drive `setConfiguration` synchronously from the joiner** in `startInJoinMode`
(`stageSelfIntoRaftGroup`, mirroring the spike's immediacy). It **hangs the boot**: a joiner cannot
acknowledge its own addition until it has caught up, which is precisely what is blocked — so the
synchronous `setConfiguration` (patient client) blocks forever right after `Joined existing group`.
Reverted. The lesson: the joiner cannot bootstrap its own staging; the catch-up has to happen against
a division that can receive entries *before* it is a voting member.

## Candidate fixes

Listed best-first. Each needs an in-test reproduction (see Test plan) before touching the live path.

### A. Listener-role join, then promote (recommended)

Add the joiner to the group first as a Ratis **`LISTENER`** (non-voting) rather than a `FOLLOWER`.
A listener is *designed* to receive `AppendEntries`/`InstallSnapshot` and catch up without
participating in quorum, so it should not get stuck `initializing`. Once it has caught up (its SM has
the latest committed index / cluster meta), a second `setConfiguration` **promotes** it to `FOLLOWER`.

- **Why it should work:** it directly targets the failure ("division won't accept entries because it
  is not a RUNNING voting member"). Listener catch-up is the Ratis-blessed path for adding a member
  that needs to sync a large state machine.
- **Where:** `RaftBootstrap.joinExistingGroup` / the `RequestJoin` handler set the joiner's startup
  role to `LISTENER` (`RaftPeer.Builder.setStartupRole(RaftPeerRole.LISTENER)` and/or a listener in
  the `setConfiguration` peer list); `MembershipReconciler` (or a new join coordinator) promotes
  listener → follower once `commitIndex` catch-up is observed.
- **Risk:** two-phase membership change adds states to reason about; needs care that a promotion
  failure leaves the joiner as a harmless listener, not a phantom.

### B. Automate the proven dance — in-process Raft restart after join

The restart-mode division is healthy (the spike's `followerRestartSeesLeader` proves it). Instead of
asking an operator to `systemctl restart` the joiner, **restart the joiner's Raft server in-process**
right after `add()` + the leader's `setConfiguration`: close the `RaftBootstrap` server and re-create
it via the restart path (`raft.start`, `isRestart=true`), then let the leader's reconciler stage it.
Combine with the shipped await-not-stamp so the restart boot waits for the meta instead of stamping.

- **Why it should work:** it reproduces, automatically and deterministically, the one sequence we know
  converges — without a human in the loop.
- **Risk:** an in-process Raft server teardown/re-init mid-bootstrap is fiddly (open clients, the SM
  instance, TLS material); must be ordered so REST/gRPC don't serve against a half-restarted plane.

### C. Ratis tuning / version

Investigate whether ratis-server `3.1.3` has a known join/catch-up bug fixed upstream, or whether a
config key changes the behavior (e.g. the install-snapshot / "notify-as-listener" thresholds, the
`initializing` timeout). Cheapest to try, least certain to land; do this in parallel as a spike.

## Recommendation

Pursue **A (listener-role join)** as the principled fix; keep **B (in-process restart)** as the
pragmatic fallback that at least makes the existing, proven sequence automatic and deterministic. Run
**C** as a cheap parallel spike. Do not ship any of them to the live HA path until the bug is
reproduced in an automated test (below) and the fix makes that test deterministic.

## Acceptance criteria

- A clean controller join reaches `memberCount: N` on **every** peer **without any manual restart**,
  **10/10 consecutive runs** (the bug is non-deterministic; flakiness is failure).
- No phantom members afterward (`cluster members` == Ratis group == `N`); quorum math is correct.
- A leader kill still re-elects and a follower restart still rejoins (no regression of #19/8B).
- The shipped await-not-stamp invariant holds: a joiner never stamps a fresh `clusterId`.

## Test plan

The current `RatisMultiPeerSpikeTest` **cannot** catch #22 because it drives `setConfiguration`
immediately — add a regression test that **reproduces** the production ordering: `add()` on the
joiner, then a *delayed* leader `setConfiguration` (simulating the async reconciler), and assert the
joiner still catches up. That failing test is the bar the chosen fix must turn green. Then exercise
the full `ClusterControlService.startInJoinMode` path (not just `RaftBootstrap`) under
`ModuleTestHarness`/`TestCluster` so the identity + meta-sync interaction is covered end-to-end.

## References

- Code: `ClusterControlService.startInJoinMode` / `reconcileClusterIdentity` / `awaitClusterMeta`;
  `RaftBootstrap.startInJoinMode` / `joinExistingGroup` / `setConfiguration` / `wasFreshBootstrap`;
  `MembershipReconciler`.
- Tests: `RatisMultiPeerSpikeTest` (`:cloud-controller:spikeTest`), `ClusterControlServiceTest`.
- Live evidence + the full fix/attempt history: `northstar-plan.md` Part 8 (8A notes + the "#22 ROOT
  INVESTIGATION" block under 8E).
- Shipped identity-fork fix: commit `22515f0`.
