# Issue #22 — controller join-lifecycle: the joiner's Raft division won't catch up

**Status: FIXED + LIVE-VALIDATED (1→3 HA restored on the fleet 2026-06-16).** Listener-join → promote
→ in-process restart. Green in `ClusterControlServiceJoinTest` and `RatisMultiPeerSpikeTest` (10/10),
and rejoined ctrl-2 + ctrl-3 live to a 3-member quorum (needed a CA self-heal for a *separate*
pre-existing blocker — see Live validation status). Captured during the v1.1 HA live acceptance run
(see `northstar-plan.md`, Part 8). The original problem statement, root cause, and the tried/reverted
attempts are kept below for history; the implemented fix is in **Resolution** at the end.

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

## Resolution (implemented)

The fix is **A (listener-role join) for the catch-up, plus a one-shot in-process restart for the
promotion** — Ratis 3.1.3 forces the hybrid (see caveats). End to end:

1. **Join as a non-voting LISTENER.** `RaftBootstrap.joinExistingGroup` now marks the joiner's own
   peer with `setStartupRole(LISTENER)` in the `add()` group. A listener starts `RUNNING` straight
   away (Ratis `RaftServerImpl.start` puts a peer that is a listener in its initial conf into the
   running follower-state, not the stuck "initializing" state), and it receives
   `AppendEntries`/`InstallSnapshot` to catch up **without** joining the quorum.
2. **Stage + promote from the leader.** `MembershipReconciler` builds the desired config each pass:
   self and already-voting members stay voters (never demoted on a transient lag); a not-yet-caught-up
   joiner is added to the **listeners** list — which commits immediately against the existing voters,
   so there is no `NOPROGRESS`. Once the listener's commit index has caught up (within a small slack),
   the next pass moves it into the **voters** list. `pendingPromotion` keeps the worker polling on a
   short cadence until every joiner is promoted.
3. **Assume the voting role via an in-process restart.** Ratis 3.1.3 does **not** transition a running
   listener's role when the config promotes it to a voter — it stays a `LISTENER` (and so would not
   vote in an election) until its division restarts. `ClusterControlService.startInJoinMode` therefore
   waits for the promotion to land in its own committed conf (`RaftBootstrap.awaitLocalVoter`, read off
   the **local** division), then tears down the listener division and re-`start()`s it from the
   persisted (now-voting) conf, where it comes up as a voting `FOLLOWER`. The heavy catch-up already
   happened as a listener, so this restart is fast and is the proven-healthy restart-mode path. If the
   promotion does not land within the timeout the node degrades gracefully (stays a caught-up listener,
   serving reads / forwarding writes; becomes a voter on its next restart) rather than failing the join.

The shipped await-not-stamp invariant (`22515f0`) is untouched — the restart never stamps a fresh
identity. `hasKnownLeader()` now also treats `LISTENER` like `FOLLOWER` so a catching-up listener does
not hang the bring-up gate (#19/8B regression guard `followerRestartSeesLeader` stays green).

**Commit-stream fix (found driving the live rejoin).** `ClusterControlStateMachine` kept a single
commit listener, so `ClusterControlService.attachEventBus` — wired *after* the reconciler during full
bootstrap — silently replaced the reconciler's wake. On a live leader the `AddMember` commit from a
join then never woke the reconciler, so the joiner was never staged. The SM now holds a *list* of
commit listeners and fires all. The in-process tests miss this because they don't wire the EventBus;
`ClusterControlPlaneTest.commitListenersCoexist` is the guard.

## Live validation status — PASSED (1→3 HA restored)

Validated on the Hetzner fleet 2026-06-16: rejoined ctrl-2 then ctrl-3 to a clean **3-member HA
quorum** (committed Raft voting set = `[338e744b (ctrl-1), 79a0c054 (ctrl-2), 5e1489a7 (ctrl-3)]`, no
phantom; daemons stayed online). Each joiner came up as a Ratis **LISTENER**, the leader's reconciler
staged it via `setConfiguration(servers, [listener])`, it caught up, was promoted, and restarted
in-process into a voting FOLLOWER — exactly the designed flow.

Driving the rejoin surfaced (and we fixed) a **separate, pre-existing blocker, not #22**: ctrl-1's
on-disk cluster TLS material was signed by an *older* CA (SHA1 `35:9C:…`) than the cluster's
authoritative signing CA in Raft state (SHA1 `CE:08:…`, the one handed to joiners) — a leftover of the
fleet's single-survivor-reset history. A 1-member cluster never does peer-mTLS so it booted fine, but
the leader↔joiner Raft handshake failed `PKIX … signature check failed` and the listener-staging
`setConfiguration` NOPROGRESS'd. Fixed by the **CA self-heal** (`reconcileSelfClusterTls`): on boot, if
the on-disk CA no longer matches the Raft-state CA, re-issue this node's leaf from the Raft-state CA
and restart Raft in-process with consistent material. It is `clusterId`-preserving and touches only the
Raft peer CA (not the daemon-facing `server.p12`/`ca.p12`), so the daemon fleet is unaffected. Guarded
by `ClusterControlServiceJoinTest.selfCaReconcileRealignsStaleOnDiskCa`. On the live ctrl-1 it realigned
`35:9C:…` → `CE:08:…` on the first boot of the new jar, after which both rejoins succeeded.

### Ratis 3.1.3 caveats found along the way

- **`GroupInfoReply.getConf()` is empty over the wire.** `ClientProtoUtils.toGroupInfoReplyProto`
  never sets the `conf` field, so a client-side read of the committed voter/listener split is always
  empty. Per-peer commit progress (`getCommitInfos()`) and role (`getRoleInfoProto()`) *are* shipped.
  So the reconciler reads the voting set from the leader's **local** division, and catch-up from
  `getCommitInfos()`; promotion detection on the joiner reads its **local** conf.
- **No automatic listener → follower transition** on a config change (`changeToFollower` explicitly
  excludes the `LISTENER` role) — hence the restart in step 3.

### Test bar

- `RatisMultiPeerSpikeTest.joinerCatchesUpThroughReconciler` — reproduces the production ordering
  (joiner `add()`, then the real `MembershipReconciler` fires *late*); asserts the joiner syncs and is
  promoted into the committed voting set with no phantom, leader undisrupted.
- `ClusterControlServiceJoinTest.joinThenRestart` — full `startInJoinMode` path (TLS + gRPC); asserts
  the joiner finishes the join as a voting member (FOLLOWER/LEADER), not a stuck listener.
