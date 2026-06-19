# Control-plane rewrite — fleet validation runbook

**Why this file exists:** the single-writer rewrite (`rewrite/single-writer-control-plane`) has landed every
piece that can be built and tested *locally* — Phases 0.5, 1, 2, 5, and the unit-testable mechanisms of Phase 3.
But "done" here means **implemented + unit/locally-tested + green**, *not* live-validated. Several phases have
**live abort gates** in the plan that can only be exercised on a real multi-controller fleet, and the rule
during the rewrite has been: do not deploy to the live fleet (it will be wiped + rebuilt fresh on this branch).
This file is the turnkey checklist for that fleet session — run the gates in order; each says what PASS looks
like and how to REVERT if it fails. Full design + rationale: `~/.claude/plans/adaptive-painting-yao.md`.

## What is already proven (in code, not on the fleet)

| Phase | Mechanism | Commit | Local proof |
|---|---|---|---|
| 0.5 | RS-mode boot assertion + majority/linearizable concerns | `75596be` | unit + local single-node RS |
| 1 | `MongoLeaderElector` fenced lease + epoch | `ad845b4` | 4 gates green on local RS |
| 2 | ownership = leadership, scheduler gate, convergence gate | `0c34918`,`783d33f` | 797-test suite |
| 3a | additive proto (`leader_grpc_addr`, `epoch`) | `3eacc25` | proto contract |
| 3b/c | epoch fencing — stamp + daemon `STALE_EPOCH` reject | `4f056f6` | unit |
| 3(1)(2) | controller emits `leader_grpc_addr` + `epoch` on HandshakeAck | `7ff35af` | unit (pure decision) |
| 3(3) | daemon redirects to leader (target swap + reconnect) | `8d8b614` | unit (parse/swap) |
| 3(5) | crash-report buffer + at-least-once replay | `dd62edf` | unit (bounded FIFO) |
| 5 | change-stream reactive reconcile + observability metrics | `23481f2`,`5d04b4a` | trigger/resume on local RS |

## Precondition — build + deploy

1. **Build the branch jars** with JDK 25:
   `cd java && JAVA_HOME=$(ls -d ~/.local/jdks/jdk-25*) ./gradlew :cloud-controller:shadowJar :cloud-daemon:shadowJar`
2. **Mongo:** convert data-1 from standalone to a **single-node replica set** (`replSetName` + `rs.initiate()`),
   or stand up a 3-member RS if Mongo HA is wanted. The boot assertion (Phase 0.5) fails fast if Mongo is a bare
   standalone — that is the first gate.
3. **Deploy** to all 3 controllers (heterogeneous fleet): ctrl-1 = docker (`docker restart
   controller-controller-1`); ctrl-2/ctrl-3 = systemd (`systemctl restart prexorcloud-controller.service`); all
   load `/opt/prexorcloud/controller/PrexorCloudController.jar`. Re-auth per session (24h JWT TTL).
4. **Leader detection changed:** leadership is now the Mongo lease, *not* Raft. Find the leader by reading the
   `cluster_leadership` doc (`{_id:"leader", holder, epoch}`) in Mongo, or scrape `prexorcloud.leadership.is_leader`
   (== 1 on the leader). The old "SIGQUIT → `GrpcLogAppender` = Raft leader" trick no longer applies.

## Gates — run in order

### Gate A — Phase 0.5: replica-set mode
Boot a controller against the converted Mongo. **PASS:** boots clean; a change-stream smoke read works against
the deployed topology. **REVERT:** if the RS assertion or smoke test fails, stop and revert the standalone→RS
conversion — do not run controllers expecting RS semantics.

### Gate B — Phase 2: single-writer + convergence (protect the baseline)
**PASS:** exactly one controller holds the lease; only the leader ticks the scheduler; a brand-new group still
places an instance that reaches RUNNING (do **not** regress the `c5c80f1` baseline); after restarting a
controller, pre-existing instances are re-adopted with **no plugin-token 401 loop, no duplicate spawn, no
stuck-SCHEDULED**. **REVERT:** flip the ownership check back to the old `ownsNode` (single gate) — this is the
baseline-known-good behavior.

### Gate C — Phase 3 redirect/fencing (the headline new validation)
This is the part with no local proof — the redirect → reconnect → land-on-new-leader loop.
- **C1 emission (`7ff35af`):** a daemon that handshakes onto a **follower** receives a non-empty
  `leader_grpc_addr`; a daemon on the **leader** receives a non-zero `epoch`.
- **C2 redirect (`8d8b614`):** that daemon swaps its target and **reconnects to the leader within the
  convergence grace window**; sessions consolidate on the leader (`NodeSessionManager` holds streams only there).
  Watch `prexorcloud.changestream.running` / the leader's session count.
- **C3 fencing (`4f056f6`):** a deposed leader's late commands are rejected daemon-side as `STALE_EPOCH`.
- **C4 crash durability (`dd62edf`):** kill an instance *during* a leader change → the crash is still recorded
  and queryable (buffer replays on reconnect).
- **PASS:** all daemons reconnect to the leader inside the grace window; no false-eviction; crash recorded.
- **REVERT (abort gate):** if daemons don't reconnect inside the grace window, **keep the Redis relay + static
  address** — do **not** proceed to delete the relay (Phase 3 item 6).

### Gate D — Phase 5: reactive reconcile + correlated failover
**PASS:** editing a group/deployment triggers a reconcile faster than the periodic tick; killing the Mongo
primary makes the leader **step down cleanly** (stops issuing commands), the change stream **resumes from its
token**, running games keep running, and control resumes after the election window. With the stream layer
disabled, the periodic floor still catches everything. **REVERT:** disable the stream layer; the floor is
unaffected (that is the invariant).

### Gate E — failover / fencing / partition (plan Verification §)
- **HA failover:** kill the leader → a new leader elects in one Mongo election window; all daemons redirect +
  reconnect; **no duplicate instances, no ownership flap, no stuck-SCHEDULED**; re-run the G1–G4 scale-up.
- **GC-pause fencing:** pause a leader past its lease while a successor takes over → the zombie's daemon
  commands (lower epoch) and lifecycle writes are rejected.
- **Partition split-brain:** isolate the leader from **Mongo** but not its daemons → it cannot renew, so it must
  **step down within `ttl − safetyMargin`** even though it still holds live streams; a successor elects via the
  surviving Mongo quorum; the old leader's late commands are epoch-rejected.
- **Rolling restart under running instances:** restart controllers one at a time with instances RUNNING →
  instances stay RUNNING / are re-adopted, no 401, no re-dispatch loop, no duplicate spawn (the baseline
  regression — must go green).

## Remaining work — only after the gates pass

These are **not yet implemented** and each is gated on the validation above:

1. **Phase 3 (4) — seed-list.** `ControllerConnectionConfig` (`record(host, grpcPort)`) → a seed *list*, so a
   daemon whose configured controller is *down* can still bootstrap and then be redirected to the leader.
   Touches `config/DaemonConfig`, `setup/InteractiveSetup`, and the 6 `config.controller().host()/grpcPort()`
   read sites in `PrexorDaemon.java` (gRPC dial, TLS material fetch, join-token redeem). Needs ≥2 controllers
   (one down) to validate.
2. **Phase 3 (6) — delete the Redis relay** (`RedisEventBridge` routeCommand/routeReply + the 6 pub/sub
   channels). **LAST**, gated on Gate C proven and coupled to Phase 6.
3. **Phase 4 — migrate the 6 Raft state types to Mongo** (dual-write, flag-gated; one-time backfill at cutover,
   tolerating the stuck-STARTING member). Cutover gate = the Mongo read path matches Raft under a live soak.
4. **Phase 6 — remove Redis/Valkey entirely** (re-home ~29 uses per the four-way table). Gate = full suite green
   with Valkey **absent** (not disabled — removed from compose/deps/config).
5. **Phase 7 — delete Ratis + write the ADR.** Irreversible. Gate = the Mongo path has soaked through **≥1 real
   leader failover and ≥1 controller add/rejoin**.

## Observability cheat-sheet (watch these during the gates)

Scrape Prometheus on each controller:
- **Leadership:** `prexorcloud.leadership.is_leader` (1 on the leader), `.epoch`, `.renew_age.millis`
  (proximity to step-down), `.transitions`.
- **Convergence:** `prexorcloud.convergence.observing` (1 during the post-takeover observation phase),
  `.last_observation.millis`.
- **Change streams:** `prexorcloud.changestream.running`, `.changes`, `.full_resyncs`, `.opens`,
  `.last_event_age.millis` (lag).
- **Still missing (add when their phases land):** plugin-token validation source {memory-hit / mongo-readthrough
  / reject} (Phase 6), daemon redirect/reconnect/`STALE_EPOCH` counts (Phase 3 fleet), convergence
  nodes-reported/expected + instances re-adopted/false-evicted.
