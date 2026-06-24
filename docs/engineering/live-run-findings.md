# Live-run findings — product fixes to land (durable backlog)

**Why this file exists:** `northstar-plan.md` is a *transient, delete-at-teardown* checklist (see its Part 14).
Many real product bugs were found during the live acceptance run and logged **only** inside it, or fixed as
**uncommitted** working-tree code. Both vanish when the plan is deleted / the tree is reset. This file is the
**durable home** for the open fixes so they survive teardown. When `northstar-plan.md` is finally removed, sweep
any remaining open findings from it into here first, and commit the working-tree fixes.

Status legend: **OPEN** = not yet coded · **CODED (uncommitted)** = fix in the working tree, must be committed ·
**DONE** = committed.

**2026-06-18 fix pass:** the items below were deployed to all 3 fleet controllers and live-validated, then
**committed** (`c5cbfe2` "Live-run fixes…"; CLI batch still parked). The "uncommitted" notes below are HISTORICAL —
everything functional here is on `main`. The session-2 HA-divergence fixes (G1/G2/G3) committed in `311e3e3`, the
runningInstances count fix in `0113c44`. Only the Geyser/Bedrock edition-routing live-validation remains (needs
Floodgate, user-in-loop).

> **⚠️ 2026-06-18 (Part 4 platform-breadth session) — fleet runs an OLD controller jar.** The 3 fleet controllers
> are still on the pre-`c5c80f1` build (no placer==node-owner co-location; the in-flight fixes `c5c80f1`/`4baa42d`
> and the new `94938c9`/`6541bb1` are committed on `main` but **not deployed**). Symptoms seen live: node↔controller
> ownership **flaps on reconnect**, instances wedge at VALIDATION (`INSTANCE_ALREADY_STARTING` → `Node disconnected →
> CRASHED → No eligible node` loop), and base-template **files replicate inconsistently** across controllers. This
> blocked the live RUNNING of Folia (finding #8) and Spigot even though their provisioning artifacts are correct.
> **Access limit this session:** only **ctrl-1 (10.0.0.3)** + **node-frankenstein-1** were reachable (ctrl-2/10.0.0.6,
> ctrl-3/10.0.0.7, node-fra-2/10.0.0.5 refuse SSH), so a fleet-wide rolling redeploy wasn't possible. **To unblock:**
> roll the current `main` controller jar to all 3 cluster members. Paper 1.20 + BungeeCord still reached RUNNING (they
> landed on a controller/node combo that happened to be stable at dispatch time).
>
> **Spigot self-host note:** `spigot-1.21.1.jar` (BuildTools) is served by a `python3 -m http.server 8088` on
> **data-1** (`/root/spigot-host/`), referenced by catalog `SPIGOT 1.21.1` → `http://10.0.0.2:8088/spigot-1.21.1.jar`.
> That server is a throwaway background process; restart it (or re-host the jar) if data-1 reboots.

---

## Fixed this pass (CODED, uncommitted)

### 1. Daemon `JarCache` does not follow HTTP redirects → 0-byte jar → crash-loop — **CODED + DEPLOYED + LIVE-VALIDATED**
- **File:** `java/cloud-daemon/.../daemon/template/JarCache.java` (~line 110).
- **Cause:** `HttpClient.newBuilder()…` omitted `.followRedirects(...)`; Java's `HttpClient` defaults to
  `Redirect.NEVER`. A catalog `downloadUrl` that 302-redirects (geysermc `…/versions/latest/builds/latest/…` alias)
  made the daemon save the **empty 302 body** as a 0-byte `server.jar` → `Invalid or corrupt jarfile` crash-loop.
- **Fix applied:** added `.followRedirects(HttpClient.Redirect.NORMAL)` **and** a response-status check (non-2xx →
  `IOException`, delete the temp file) so an error body never lands as a jar.
- **Validation:** new daemon jar deployed to both nodes; **live-confirmed** — with the catalog pointed back at the
  geysermc *redirect* alias, the daemon downloaded the full **29 MB** Geyser jar (was 0 bytes) and Geyser booted.

### 2. Bundled Geyser extension descriptor incompatible with Geyser 2.10.1 — **CODED**
- **File:** `java/cloud-plugins/proxy/geyser/src/main/resources/extension.yml`.
- **Cause:** the descriptor carried `description:`/`authors:`; Geyser 2.10.1's `GeyserExtensionDescription$Source`
  rejects `description` → `Loaded 0 extension(s)` → no controller integration, instance stuck `STARTING`, no
  authoritative `edition=bedrock` report.
- **Fix applied:** stripped to the current schema (`id/name/main/version/api`). The rebuilt extension jar is bundled
  in the new controller jar (verified). To take effect on a fleet: redeploy the daemon (JarCache fix) **and
  regenerate `base-geyser`** (delete its Mongo template doc + on-disk `templates/base-geyser/files/`).
- **Validation:** descriptor verified inside the rebuilt extension jar bundled in the new controller jar; daemon
  deployed. Live validation was **flaky** (forcing a `base-geyser` regen needs the Mongo doc + controller on-disk
  `templates/base-geyser` + both daemons' `cache/templates/base-geyser` cleared **and** a ctrl-1 restart, then a
  re-provision) — and it's entangled with the deferred Bedrock edition-routing work (which also needs Floodgate), so
  full live validation is folded into that future session. Blocks **Bedrock edition-aware routing**.

### 3. (g) cross-controller `ClusterState` divergence — **CODED + DEPLOYED + LIVE-VALIDATED**
- **Cause:** daemon instance-status updates only reach the **node-owning** controller's `ClusterState`; peers are
  blind (the SSE replay stream feeds *client* SSE, not peer state; peers hydrate Redis only at boot). A peer that
  wins a group lease/leadership re-places a duplicate on a port it can't see is in use → the (g) port-desync.
- **Fix applied (conservative, low-risk):** `ClusterState.reconcileInstancesFromRedis()` — **add-if-missing only**
  from the shared Redis projection (`RedisRuntimeStore.loadInstances()`); never reverts a fresher local state, never
  removes (removeInstance writes shared Redis), no events. Called at the **top of every scheduler tick**
  (`Scheduler.evaluate`) so a controller converges its instance view before planning placements.
- **Validation:** deployed to all 3 controllers; **live-confirmed all three now agree** `survival-lobby
  runningInstances:1` + see edge/hub/lobby (previously the two non-owning controllers showed 0).
- *Not yet covered:* ~~state-convergence (peers keep a stale state for a known instance) and safe removal of
  genuinely-gone instances~~ → **CLOSED by session-2 G2** (`reconcileInstancesFromRedis` now converges state via the
  transition validator + prunes after a 2-tick grace; the owner-write-back hazard is handled by the validator and a
  Redis-safe local-only prune). See the 2026-06-18 (session 2) section below.

### 3b. (g) SECOND FACET — `INSTANCE_ALREADY_RUNNING` ack leaves the instance stuck `SCHEDULED` — **LIKELY MITIGATED by session-2 G3 (confirm)**
- **Update:** session-2 G3 makes the node-owner *adopt* the instance from Redis on the daemon's periodic status and
  drive it to RUNNING, so the stuck-`SCHEDULED` symptom should now clear even though `DaemonCommandAckHandler` still
  swallows the `INSTANCE_ALREADY_RUNNING` ack. Needs a targeted confirm (re-run a storm); the pure root fix (candidate
  (b): daemon re-emits current status on a duplicate start) is small but state-machine-risky — defer unless the
  confirm shows the symptom persists.
- **Confirmed live 2026-06-18** by a simultaneous both-daemon restart (a reschedule storm): the daemon *was*
  running the instances, but each StartInstance redispatch got `ProcessManager: "already exists, ignoring start"`
  and `DaemonCommandAckHandler.handleStartInstanceAck` treats `INSTANCE_ALREADY_RUNNING` as an idempotent replay —
  clears the retry budget and returns **without reconciling state** — so the controller's instance sits `SCHEDULED`
  forever while the daemon runs it, and the scheduler keeps redispatching every ~30 s. This is distinct from the
  cross-controller blindness fixed in #3 (the add-if-missing merge does not unstick it).
- **Fix candidates (state-machine — risky, needs a clean repro + test):** (a) on `INSTANCE_ALREADY_RUNNING` the
  controller reconciles to the daemon's *actual* reported state rather than leaving limbo (don't blindly force
  RUNNING — the daemon enters `processes` at spawn, before plugin-ready); (b) the daemon re-emits the instance's
  current status on a duplicate start so the controller resyncs; (c) don't reassign a port to an already-running
  instance. NOT patched (too risky to blind-fix live).
- **Operational note:** restart daemons **one at a time (rolling)**, never both at once — a simultaneous restart
  stops every instance and the recovery storm reliably triggers this. Recovery from a storm = full control-plane
  stop (controllers + daemons) → kill MC procs → clear `prexor:v1:instance:*` + Mongo `instance_composition_plans`/
  `workflow_*` → start controllers, then daemons → rescale.

### 6. Controller returns 500 (not 400) on malformed/unknown-field JSON (was follow-up (d)) — **CODED + LIVE-VALIDATED**
- **File:** `RestServer.java` exception handlers. Added a `JsonProcessingException` handler (parent of
  parse/mapping/MismatchedInput) → `400 BAD_REQUEST "Malformed or invalid JSON request body"`, ahead of the generic
  `Exception`→500. **Live-confirmed** a bad body now returns 400.

### 7. Deleting a group orphaned its auto-created group-template (was follow-up (h)) — **CODED + LIVE-VALIDATED**
- **File:** `GroupRoutes.deleteGroup`. Now reaps the same-named auto-template on delete, **guarded** — only when no
  remaining group layers it (`templates`) or inherits it (`parent`). **Live-confirmed** the template is reaped.

---

## Coded but UNCOMMITTED (in the working tree — commit before any reset)

### 4. Orphan instance never stopped on daemon reconnect — **CODED (uncommitted)**
- `DaemonConnectionLifecycle.reconcileInstances` now force-stops a running instance the controller has no record of
  (genuine orphan at handshake), via new `Scheduler.stopOrphanInstanceOnNode(nodeId, instanceId)`. Removed the now-unused
  `verifyNodeOwnership`. Live-validated twice 2026-06-18.

### 5. Orphaned `instance_composition_plans` leak in Mongo — **CODED (uncommitted)**
- `InstanceLifecycleManager.scheduleRemoval` (terminal reaper) now also calls
  `stateStore.deleteInstanceCompositionPlan(id)` (added a `StateStore` ctor dep; `InstanceLifecycleManagerTest`
  updated). The placement coordinator already deleted both together; the reaper forgot the plan, so plans accreted
  and fed the (g) re-dispatch loop. Unit-tested.

Files (4 + 5): `controller/grpc/DaemonConnectionLifecycle.java`, `controller/scheduler/Scheduler.java`,
`controller/lifecycle/InstanceLifecycleManager.java`, `controller/PrexorCloudBootstrap.java`,
`controller/lifecycle/InstanceLifecycleManagerTest.java`. Deployed to all 3 fleet controllers.

### 8. Folia servers get the Paper plugin (which Folia rejects) → no integration — **COMMITTED (`94938c9`); NOT yet deployed to fleet**
- **File:** `java/cloud-controller/.../controller/template/BaseTemplateGenerator.java`.
- **Cause:** `BUNDLED_PLUGINS` is keyed on **config format**. Folia correctly shares the **`paper`** config format
  (server.properties, paper-global.yml, spark disable, bootstrap cache all key on `paper` — see the line-109 comment),
  so the lookup handed a Folia server `PrexorCloudPaperPlugin.jar`. Folia refuses it at load:
  `Could not load plugin 'PrexorCloud v1.0.0' as it is not marked as supporting Folia!` → `Initialized 0 plugins` →
  no controller registration → the instance never signals ready → **wedged in `STARTING`**. The dedicated
  `PrexorCloudFoliaPlugin.jar` (folia-supported, region schedulers) is built + bundled into the controller
  (`cloud-controller/build.gradle.kts:103`) but was **never selected**.
- **Fix applied (committed `94938c9`):** added `BUNDLED_PLUGINS_BY_PLATFORM` (`folia → PrexorCloudFoliaPlugin.jar`)
  and select by **platform first**, falling back to config-format. Controller compiles clean.
- **Live status (2026-06-18):** root cause confirmed from the instance log on node-frankenstein-1. **Full live
  validation BLOCKED by fleet access:** base templates are generated per-controller from each controller's bundled
  plugins, so the fix must reach **all 3 cluster controllers** — but only **ctrl-1 (10.0.0.3)** is SSH-reachable
  this session (ctrl-2/10.0.0.6 + ctrl-3/10.0.0.7 refuse publickey; node-fra-2/10.0.0.5 unreachable too). Hand-patched
  ctrl-1's `base-folia` template (swapped in the Folia jar; the template auto-rehashed), but daemon↔controller
  ownership kept flapping on reconnect and the folia instance landed on node-fra-2 (owned by an unreachable
  controller, still serving the Paper-plugin template) → re-wedged. Forcing it onto node-frankenstein-1 via a 2600 MB
  memory pin landed it there but it then pulled a template with **no plugin jar + eula=false** (post-reconnect it was
  no longer served by ctrl-1). **To close:** deploy the fixed controller jar to all 3 cluster members
  (`scp PrexorCloudController.jar` + restart each, rolling) and regenerate `base-folia` (delete its shared-Mongo doc +
  each controller's on-disk `templates/base-folia/files/` + each daemon's `cache/templates/base-folia`), then
  re-provision a FOLIA group. Catalog entry `FOLIA 1.21.8` is already added. The crash-looping `folia-test` group was
  deleted to stop churn.
- **Also observed — possible second bug (HA template distribution):** `ctrl-1`'s `base-folia` is **complete**
  (`eula.txt`, `server.properties`, `forwarding.secret`, `plugins/PrexorCloudFoliaPlugin.jar`, spark config — verified
  on disk), yet the folia instance on node-frankenstein-1 received an **empty** template (no plugin jar, no
  `eula.txt` → Folia wrote `eula=false` itself → "You need to agree to the EULA" → crash-loop). The `base-folia`
  **Mongo doc is shared**, so a peer controller with the doc but **missing local files** skips regeneration
  (`templateManager.exists()` is Mongo-gated) and serves an incomplete template. By contrast `base-bungeecord` *did*
  propagate (BungeeCord booted on node-fra-2 with the ctrl-1 config fix), so replication is **inconsistent/partial**.
  Worth a dedicated look: base-template file distribution should not depend on which controller first generated it.
  (Hard to fully diagnose this session — 2 of 3 controllers unreachable.)

---

## 2026-06-18 (session 2) — three HA-divergence findings from the 3C scale-up test — **FIXED + LIVE-VALIDATED**

> **▶ ALL THREE FIXED (CODED, uncommitted) + DEPLOYED to all 3 controllers + LIVE-VALIDATED 2026-06-18.**
> Re-ran the exact scale-up that deadlocked: **G1** — `PATCH min=2` on ctrl-1 propagated to all 3 controllers
> within seconds (was stale before); **G3** — the 2nd instance reached `RUNNING` (was stuck `SCHEDULED` forever);
> **G2** — all 3 controllers converged to `running=2`, then on scale-down ctrl-2 logged
> `Pruned instance survival-lobby-2 (gone from shared projection for 2 ticks)` and converged to `running=1`.
> Files: `state/ClusterState.java` (adoptInstanceFromRedis + converge/prune reconcile),
> `state/RedisRuntimeStore.java` (loadInstance), `grpc/DaemonServiceImpl.java` (adopt-on-unknown in
> verifyNodeOwnership), `rest/route/GroupRoutes.java` (publish Group{Created,Updated,Deleted}Event).
> Unit test: `state/ClusterStateRedisReconcileTest` (8 tests, adopt + add/converge/no-clobber/prune). Commit pending.

Found while driving an autonomous orchestration test (`survival-lobby` min 1→2 across zones) on the 3-controller
fleet. The PATCH succeeded (Mongo + the receiving controller) but the group never scaled. Root-causing it surfaced
three distinct, real, code-level HA bugs. None are hacks-fixable live; all need product changes. The fleet was
recovered to a clean baseline (min=1, `survival-lobby-2` + `edge-1` RUNNING) by a rolling restart of all three
controllers — restart is the only thing that reliably reconverges them today (see G1/G2).

**Context that triggers them:** today's restart churn left the cluster with the group-scheduling lease holder
(*placer*) on a **different** controller than the one owning the daemon gRPC streams (*node-owner*). Both daemons
(`node-fra-2`, `node-frankenstein-1`) are owned by ctrl-1 (`338e744b`); the `survival-lobby` Redis group lease was
held by ctrl-2 (`79a0c054`). The fleet looked "healthy" only because its existing instances were placed back when
placer == owner and there were no pending placements.

### G1. Cross-controller `GroupConfig` cache divergence — **FIXED + LIVE-VALIDATED**
- A `PATCH /api/v1/groups/{name}` updates Mongo **and the receiving controller's in-memory `GroupManager` cache only**.
  Peer controllers keep serving their stale cached config indefinitely — no broadcast / cache invalidation.
- **Observed live:** after `PATCH {minInstances:2}` on ctrl-1, `GET groups/survival-lobby` returned `min=2` on
  ctrl-1 but `min=0,max=1,spread=""` on ctrl-3 and (after a later revert to `min=1`) `min=1` on ctrl-1 vs `min=2`
  on **both** ctrl-2 and ctrl-3. Mongo always held the latest. Because the scheduling decision runs on the
  **group-lease holder**, a stale peer holding the lease scales to the wrong target (or not at all).
- **Root:** `Group{Created,Updated,Deleted}Event` were **never published** by the controller — only a test emitted
  one. `RedisEventBridge` already subscribes and forwards them over `CHANNEL_GROUP` (peers `reloadGroup` /
  `removeGroupFromCache` on receipt); the send side was simply never wired.
- **Fix:** `GroupRoutes` now publishes the corresponding event after each `groupStore` write (after the Mongo write,
  so peers' `reloadGroup` reads the new value). Bridge skips the publisher's own echo (controllerId check), so no
  local reload-loop. **Live-validated** — a PATCH on ctrl-1 reached ctrl-2 + ctrl-3 within seconds.

### G2. `ClusterState` phantom-instance accumulation across controllers — **FIXED + LIVE-VALIDATED**
- In-memory `ClusterState` accretes instances that are STOPPED / long gone; `runningInstances` diverged wildly
  across controllers (live: **1 / 3 / 4** for the same group whose real running count was 1–2). The (g) add-if-missing
  reconcile from Redis (#3 above) **never removes**, so dead entries persist until restart.
- Consequence: the lease-holder's scale math (`evaluateScaleUp`: `currentCount < minInstances`) uses an inflated
  `currentCount` and declines to scale (or, with a deflated count elsewhere, over-places).
- **Fix:** `reconcileInstancesFromRedis` now, beyond add-if-missing, (a) **converges** a known instance's state
  toward Redis via the existing transition validator — a stale read can't move a fresher local state backward, so the
  owner is never clobbered — and (b) **prunes** a local mirror absent from the shared projection for 2 consecutive
  ticks (grace against a transient scan miss), via a Redis-safe local-only removal (never re-deletes the key — avoids
  clobbering a value the owner re-created in a scan race). **Live-validated** — non-owners converged to `running=2`
  then `running=1`; ctrl-2 logged the 2-tick prune.

### G3. Placement deadlock when *placer* ≠ *node-owner* — **FIXED + LIVE-VALIDATED**
- When the group-lease holder (which decides + dispatches `StartInstance`) is a **different** controller than the one
  owning the target node's daemon gRPC stream, a freshly placed instance **deadlocks at `SCHEDULED` forever** even
  though the server process spawns and runs fine:
  - The **placer** has the instance record (SCHEDULED) but never receives status — the daemon streams
    status/console to the **node-owner**, not the placer.
  - The **node-owner** receives the status but has **no record** → logs `handleConsoleOutput: unknown instance …`
    and drops it; the duplicate `StartInstance` redispatch gets `ProcessManager: already exists` →
    `INSTANCE_ALREADY_RUNNING` → controller treats it as idempotent replay (the 3b swallow) → never reconciles.
  - SCHEDULED placements *are* written to Redis (`addInstance` write-through) — corrected from the first analysis —
    but the owner only learns peer-placed instances via `reconcileInstancesFromRedis` on a **scheduler tick**, which
    can stall/wedge; meanwhile the status handler drops the unknown instance outright with no Redis fallback.
- **Observed live:** ctrl-2 (placer) placed `survival-lobby-1` then `-3` then `-4` on ctrl-1-owned nodes; each spawned
  a real Paper process (listening on its port) but stuck `SCHEDULED uptime 0s`; 30 s redispatch loop spawned orphans.
- **Fix (shipped):** the node-owning controller's daemon status/console handler (`verifyNodeOwnership`) now, on an
  unknown instance, **adopts it from the shared Redis projection** (`ClusterState.adoptInstanceFromRedis`, guarded so
  it only adopts a record assigned to the reporting node) before dropping — deterministic, no dependence on the
  wedge-prone reconcile tick. Combined with G2's state-convergence, the placer also converges its view to RUNNING and
  stops the redispatch loop. **Live-validated** — the re-run scale-up reached RUNNING instead of deadlocking; the
  cross-controller adopt path is covered by `ClusterStateRedisReconcileTest`.

### Operational note (session 2)
- Rolling **controller** restarts (one at a time) are safe and are currently the only reliable reconverge for G1/G2
  — quorum holds with the other two. (Distinct from the **daemon** rule: never restart both daemons at once.)
- A residual phantom (ctrl-3 `running=2` vs real 1) can survive a single restart; harmless while `min` is satisfied.

---

## 2026-06-18 (session 3) — Part 4 platform breadth: Paper 1.20.4 + a 4th divergence facet (G4) — **OPEN**

Drove Part 4 (provision each platform to RUNNING). First target Paper 1.20.4 (catalog build 499) surfaced:

### Paper 1.20.4 platform itself — **WORKS**
- The daemon downloaded the jar (PaperMC build 499), warmed the bootstrap cache, and launched. **Confirmed by a
  manual run on the node** (`/opt/prexorcloud/jre/bin/java -jar cache/jars/PAPER/1.20.4/server.jar` with
  `eula=true` + the bundled `PrexorCloudPaperPlugin.jar` in `plugins/`): server reaches **`Done (11.8s)!`**, the
  cloud plugin **loads and enables** ("Loading server plugin PrexorCloud v1.0.0" → "Enabling"). Java 25 (Temurin) runs
  MC 1.20.4 fine — not a Java-version problem. So the platform/download/boot path is good.

### G4. Plugin/workload token rejected by the owner controller under *placer ≠ node-owner* — **FIXED + LIVE-VALIDATED**

> **▶ FIXED via the durable "placer == node-owner" refactor + a token read-through (2026-06-18 session 3).** Re-ran
> Paper 1.20.4 under a real placer≠owner split (placer ctrl-2, node-owner ctrl-1): the instance reached **RUNNING in
> 15s** (was stuck `STARTING` forever), all 3 controllers converged `running=1`, and the plugin log shows **no 401**
> (only a benign 404 for `/api/plugin/networks` — paper120 isn't in a Network). Two changes:
> 1. **Token read-through** (`ClusterState.validatePluginToken` → `RedisRuntimeStore.loadPluginToken` +
>    `WorkloadIdentityRegistry.adopt`): on an in-process miss, hydrate the token from the shared Redis projection
>    before failing — and crucially **stop deleting** a token just because this controller hasn't learned it (the old
>    code did `runtimeStore.removePluginToken` on miss, destroying a peer-issued token). Any controller can now
>    validate any controller's token.
> 2. **placer == node-owner execution** (`NodeMessageDispatcher.ownsNode` + gates in
>    `InstancePlacementCoordinator.doPlaceResolvedInstance` and `RecoveryOrchestrator.reconcileOne`): the placer
>    persists the SCHEDULED record + composition plan but only issues the token + dispatches when it owns the node;
>    otherwise the **node-owner's** `RecoveryOrchestrator` (now gated on `ownsNode`, not the group lease) mints the
>    token and dispatches locally. The plugin connects to the owner, which issued the token → no 401, and the
>    instance lifecycle/status all live on one controller. Unit tests in `ClusterStateRedisReconcileTest`; the group
>    lease still guards the *scaling decision* (how many) — only *execution* moved to the owner.
> **Follow-up (not done):** `StartRetryOrchestrator` still gates execution on the group lease (the token read-through
> covers its 401 correctness; full co-location is a smaller follow-up).
>
> **▶ Group-delete orphan gap — FIXED + LIVE-VALIDATED (2026-06-18 session 3).** `GroupRoutes.deleteGroup` now stops
> the group's running instances (`scheduler.stopInstance`, routes to each node-owner) BEFORE removing the group, so
> they drain STOPPING→STOPPED→reaped instead of orphaning. Live: deleted a RUNNING paper120 group → the instance
> stopped in ~13s and was fully reaped in ~65s (was orphan-forever before).

**(original analysis below)**
### G4. Plugin/workload token rejected by the owner controller under *placer ≠ node-owner* — was OPEN
- **Symptom:** the managed instance never leaves `STARTING` on the control plane (server is RUNNING on the daemon).
  The plugin log shows **HTTP 401 on every controller call** — `Plugin token refresh failed: HTTP 401`, then
  `/api/plugin/networks|groups|instances|metrics|ready` all 401, SSE ticket 401.
- **Root:** same placer≠owner split as G1–G3. The instance was placed by ctrl-3 (held the `paper120` group lease) but
  the daemon injects `CLOUD_CONTROLLER_URL=http://10.0.0.3:8080` = **ctrl-1** (the node-owner), so the plugin talks to
  ctrl-1. The plugin/workload token's lifecycle (issue + sequence/refresh) is tied to the **placer (ctrl-3)**; ctrl-1
  rejects it → 401 → the plugin can never call `/api/plugin/ready`, which is the authoritative `STARTING → RUNNING`
  trigger. (`PluginRoutes.reportPluginReady` also no-ops `updateInstanceState` if the receiving controller doesn't
  know the instance — a second, smaller gap beyond G3's daemon-status adopt; but the 401 short-circuits before that.)
- **Fix direction:** the workload-token path must be cross-controller correct — validate/refresh tokens against the
  shared store (Redis) regardless of which controller issued them, OR co-locate placement with node-ownership so the
  plugin always talks to the controller that owns its lifecycle. The latter (placer == node-owner) is the durable
  architectural fix and would also retire G1/G2/G3's reliance on per-tick reconcile.
- **Note:** this only manifests under a placer≠owner split, which today's heavy controller-restart churn keeps
  re-creating. When placer == owner (the normal healthy state, e.g. the successful G1/G2/G3 validations and all the
  earlier 2D/3B client tests) tokens and ready both work. A rolling restart reconverges the fleet (ctrl-1 reacquires
  the leases it owns the daemons for).

### Part 4 status
Platform-boot is validatable per-platform via the manual-run technique (bypasses the control-plane). Full
control-plane RUNNING for managed instances is **blocked by G4** whenever the fleet is in a placer≠owner split.
Remaining platforms (Folia, Spigot, BungeeCord, Fabric, NeoForge) not yet attempted.

---

## 2026-06-18 — cluster members advertise empty `restAddr`/`gRPCAddr` (cosmetic) — **FIXED (CODED, uncommitted)**

Surfaced from `prexorctl cluster members`: all three controllers show blank `REST ADDR` / `GRPC ADDR`
(and `LABEL` == node id). **Harmless** — `raftAddr` (populated) is the only address the control plane uses;
the only consumer of `restAddr`/`gRPCAddr` is the `/api/v1/cluster/members` serializer (`ClusterMembersRoutes`),
i.e. that very CLI table. Raft, leader election, and replication are unaffected.
- **Cause:** `ClusterControlService.ensureSelfMember()` self-stamped `new Member(nodeId, raftAddr, "", "", nodeId, …)`
  — it only had `selfRaftAddress()` in scope. The Day-0 leader always self-stamps; joiners re-stamped blank during
  the Day-0 rebuild + #22 restart dance (their join-populated record was absent from local state at the time). The
  `RequestJoin` handler already sets these correctly; only the self path was blank.
- **Fix:** added `selfRestAddress()`/`selfGrpcAddress()` (off `config.http()`/`config.grpc()`, mirroring
  `selfRaftAddress()`) and `ensureSelfMember` now stamps them. File: `controller/cluster/ClusterControlService.java`.
- **Not yet redeployed/validated.** The three **live** members won't backfill from this — the `existing.isPresent()`
  branch deliberately preserves stored values; only a fresh join or a member wipe+rejoin repopulates them. New
  clusters/joins will show populated addresses. `LABEL == node id` is **by design** (every `new Member` passes the
  node id as the label) and is left as-is.

---

## 2026-06-19 — surfaced during the single-writer rewrite live validation (DIAGNOSED, not yet fixed)

Both surfaced on a clean fleet rebuilt on `rewrite/single-writer-control-plane`, when a group's instances
crash-looped — the daemon could not provision the server jar because the clean Mongo wipe had emptied the
`catalog` collection, so `runtimeDownloadUrl` resolved blank → `RUNTIME_PROVISION_FAILED disposition=PERMANENT`.
(Operational lesson recorded separately: **a clean Mongo wipe loses the catalog; re-add platform versions with
`prexorctl catalog add` before placing anything.**) The crash-loop exposed two real controller-side defects that
bite under *any* sustained provisioning failure, not just a wiped catalog. See [[project_rewrite_live_validation]].

### 9. `disposition=PERMANENT` start-failure is re-dispatched in a tight loop — **FIXED (committed)**
- **Symptom:** the daemon rejects `StartInstance` with `RUNTIME_PROVISION_FAILED disposition=PERMANENT`, yet the
  controller re-dispatches the same (and gap-filled new) instances dozens of times per second.
- **Cause:** the terminal rejection sets the instance `CRASHED` but **never fed the crash-loop detector** — the
  only `recordCrash` caller was `DaemonCrashEventReceiver` (daemon process-crash reports), and a provision-stage
  failure never reaches the process. So the group stayed below `min`, the desired-state planner gap-filled a new
  instance every tick, and it hit the same PERMANENT failure forever. The crash-loop detector (threshold 3) never
  saw it.
- **Fix:** `DaemonCommandAckHandler` now `recordCrash`es the instance's group on every terminal start failure
  (PERMANENT disposition, or a TRANSIENT one that exhausted its retry budget). Both placement paths
  (`SchedulerDesiredStatePlanner.planGroup`, `Scheduler.scheduleReplacement`) already gate on `isCrashLoopPaused`,
  so after the threshold the group pauses and backs off (60s → exponential, 1h cap, `GroupCrashLoopEvent` emitted),
  arresting the loop until the cooldown/auto-unpause probe — and a truly permanent failure just re-pauses with
  growing backoff. Covered by `DaemonCommandAckHandlerTest` (4 cases).

### 10. Concurrent `StartInstance` dispatch to one daemon corrupts the gRPC frame — **FIXED (committed)**
- **Symptom:** under the burst re-dispatch above the controller throws
  `io.grpc.StatusRuntimeException: INTERNAL: Failed to frame message` /
  `IndexOutOfBoundsException … PooledUnsafeDirectByteBuf(freed)` → `ServerCallImpl … Cancelling the stream`, and
  the daemon reconnect-storms (a new session every few seconds).
- **Cause:** concurrent `StreamObserver.onNext(...)` on one daemon's `ServerCallStreamObserver` — `NodeSession.send`
  is called from multiple threads (parallel placement / virtual threads) with no per-stream serialization. gRPC
  stream observers are **not** thread-safe for concurrent `onNext`.
- **Fix:** `NodeSession.send` (the single outbound chokepoint) now serializes `onNext` on a per-stream monitor
  (`synchronized (responseStream)`), so concurrent sends to one daemon can't interleave. The traceparent stamping
  stays outside the lock (it reads the calling thread's context).
- **Note:** the send path is pre-existing (not introduced by the rewrite), but the single-writer's burst dispatch
  makes it easier to hit. Fix #9 also removes the storm that surfaced it. Not seen in steady state — only under the
  failure-driven re-dispatch storm.

---

## Sweep-before-teardown checklist

`northstar-plan.md` still holds **many earlier-session findings** that are either deployed-but-uncommitted code or
plan-only notes — e.g. the v1.0.1 live batch (#1–#16: `DialTCPFromURI`, cosign `--bundle`, `mongo:8` kernel-bump,
plugin-token refresh-grace, crash persistence, log-file appender, role reconcile, cross-node proxy routing, …),
the HA 8E fix batch, and follow-ups (a–k) including **controller returns 500 (not 400) on malformed/unknown-field
JSON**. Before `git rm docs/engineering/northstar-plan.md` (Part 14):
1. Move any still-open findings from the plan into this file.
2. Commit the working-tree fixes (today's #4/#5 above, plus the broader uncommitted batch the plan references).
3. Then delete the plan.

---

## 2026-06-20 — single-writer control-plane rewrite, Phase 7 live validation (branch `rewrite/single-writer-control-plane`)

Deployed the pure-Mongo controller jar (Ratis deleted) to the 3-controller fleet and validated:

- **DONE — Mongo-register join.** ctrl-2 + ctrl-3 joined the live cluster by registering directly in Mongo (token
  redeem + member upsert), with **no** gRPC handshake and **no** mTLS bootstrap. This retires the
  `TLSV1_ALERT_CERTIFICATE_REQUIRED` blocker that defeated every prior multi-controller attempt. 3-member cluster
  formed cleanly.
- **DONE — single-writer election + epoch fencing.** Rolling controllers triggered clean failovers (one leader at a
  time, `epoch` advanced monotonically 4→5→…→8, no split-brain). Running game survived every controller roll
  (Gate E baseline).
- **DONE (committed) — three fixes found + landed live:**
  - `b261099` NaN leadership/SSE Prometheus gauges (Micrometer weak-ref GC — retain a strong ref).
  - `dc1d83f` members advertised the `0.0.0.0` bind host; advertise the routable `raft.host` so a redirect target is dialable.
  - `4931a24` daemon-redirect leadership was wired in `initScheduler` (before `daemonService` exists) so it never ran;
    moved to `initGrpc`. The follower now correctly emits `Redirecting daemon … to leader at <routable>:9090`.
- **DONE (committed `0da4dba`) + DEPLOYED + PROVEN — daemon-facing mTLS CA now = the shared cluster CA.**
  Root cause: each controller had its own daemon-facing CA (`config/security/ca.p12`, signing the `SERVER_KEYSTORE`
  server cert + daemon client certs), separate from the shared *cluster* CA in Mongo — so after a redirect fires the
  daemon got `UNAVAILABLE: io exception` (client SSL handler) dialing a different controller. Fix: `initSecurity` now
  loads the daemon-facing CA from the shared cluster CA (`cluster_files`, `loadClusterCa()`) and re-issues the server
  cert from it whenever the persisted one doesn't chain (`serverCertChainsTo`). **Deployed jar `4a9bbe8e` to all 3
  controllers + re-enrolled node-frankenstein-1** (its old per-controller cert was untrusted; cleared `node.p12`/`ca.pem`,
  fresh `pxr_` token, re-bootstrap). Result: the daemon's trust root is now `CN=PrexorCloud Cluster CA` and it
  **completes mTLS handshake with a controller that is NOT its dial target** (handshaked with ctrl-3 after a redirect
  from ctrl-1) — the `io exception` is gone. Cross-controller mTLS works.
- **DONE (committed `a11967d`) + DEPLOYED + PROVEN — daemon redirect-flap fixed.** Once mTLS succeeded, a daemon that
  landed on a leader via redirect churned ~1 connect+handshake+`Channel shutdownNow`/sec. Root cause: `redirectToLeader`
  calls `scheduleReconnect()`; the resulting `connect()` `shutdownNow()`s the still-healthy old channel, whose `onError`
  ("Channel shutdownNow invoked") ran `handleDisconnect → scheduleReconnect` again — a phantom reconnect that then kills
  the freshly-connected leader channel, looping forever. Fix: stamp each `connect()` with a generation id and ignore
  `onError`/`onCompleted` from a superseded channel (our own teardown). Deployed daemon jar `e72ca033` to
  node-frankenstein-1 and **proven live**: rolled the leader → daemon redirected to the new leader (ctrl-3) and held a
  STABLE session (1 connect, 0 shutdowns over 40s), ONLINE + managed. **Full HA daemon-failover now works end-to-end.**
- **🐛 NEW OPEN (ops gotcha) — a node whose daemon can't reconnect stays "ONLINE" in clusterState forever**, blocking
  `DELETE /nodes/{id}` and `POST /admin/tokens` (both gate on `clusterState.getNode().isPresent()`) with 409. The
  heartbeat-miss never fires (no session to miss) and deleting the runtime key `prexor:v1:node:<id>` from Valkey isn't
  enough — the controller only rebuilds clusterState from the store on restart. To re-enroll a daemon after a CA change
  we had to: delete the Valkey key **and** restart the controller, then deregister + mint. Consider evicting stale
  no-session nodes from clusterState on heartbeat-miss.
- **Fleet left:** 3-member cluster, **ctrl-1 leader** (the controller the daemon trusts), ctrl-2/ctrl-3 followers,
  node-frankenstein-1 ONLINE + managed, game running, gauges healthy. Admin pw on ctrl-1 at
  `cat /opt/prexorcloud/controller/config/.initial-admin-password`; cadmin/`Clstr-Admin-2026-xZ9q` for cluster.manage.

---

## 2026-06-20 (later) — daemon controller seed list + transparent failover (committed `b770ab7`)

- **DONE (committed `b770ab7`) + PROVEN — daemon controller seed list.** A daemon had one static `controller.host`;
  if that controller was down it could neither bootstrap (`System.exit(1)`) nor reconnect (it re-dialed the one dead
  address forever — the redirect only helps once *some* controller answers). Now: `controller.endpoints` in
  daemon.yml, the cluster advertises its live members in `HandshakeAck.controller_grpc_addrs`, and the daemon caches
  them to `config/known-controllers.json`. The daemon rotates off a dead target to a live seed → redirect → leader.
  Race-safe (single `volatile ControllerEndpoint target`, gen-guarded `onNext`). The Go CLI (`prexorctl setup`, the
  primary install path) writes the seed list and sweeps controller REST URLs for join-token redemption. **Proven
  live:** stopped the leader → daemon rotated to a survivor → redirected to the new leader → stable; fresh-bootstrap
  via cache when the only configured seed was a dead address.
- **DONE (committed `b770ab7`) + PROVEN — a leader change no longer restarts running servers.** The now-working
  failover exposed this: `DaemonConnectionLifecycle.handleHandshake` ran `reconcileInstances` UNCONDITIONALLY (no
  leadership/convergence gate) before the redirect decision, and `stopOrphanInstanceOnNode` force-killed any reported
  instance the controller's (cold) in-memory state didn't know. So a daemon transiently handshaking a FOLLOWER during
  rotation had its game killed; the new leader then saw it missing → marked CRASHED → rescheduled (lobby-2/lobby-3
  port churn). Fix (right invariant: daemon is ground truth; controller adopts, never reflexively kills): (1)
  instance reconciliation is **leader-only**; (2) an unknown reported-running instance whose group exists is
  **adopted**, not killed — only a gone-group instance is reaped; (3) a known instance whose record went terminal
  (CRASHED/STOPPED) while the daemon still runs it is **resurrected** (the normal status update is rejected by
  `InstanceTransitionValidator`; `addInstance` overwrites). Policy extracted as tested static `decideReportedInstance`.
  **Proven live:** rolled the leader twice — game **PID survived continuously**, log shows `adopting running instance
  lobby-2`, no force-stop/CRASHED/reschedule.
- **DONE (committed `61632e6`) + PROVEN — adopt/resurrect re-derives RUNNING (stuck-STARTING loop fixed).**
  Corrected diagnosis (the first "re-adoption readiness probe" guess was wrong): **the daemon never reports RUNNING.**
  `ServerProcess` only tracks SCHEDULED→PREPARING→STARTING; readiness is the in-server plugin's **one-shot**
  `POST /api/plugin/ready` to the *controller* (`PluginRoutes` → `clusterState.updateInstanceState(RUNNING)`), so
  `RunningInstance.state` is always STARTING for a live instance. In clean ops fine (the controller holds RUNNING and
  the daemon's STARTING is rejected by the RUNNING→STARTING transition guard). But the new adopt/resurrect path wrote
  `running.getState()` = STARTING via `addInstance` (overwrites, bypassing the guard) → an adopted instance was pinned
  STARTING forever (the one-shot /ready already fired) → `RecoveryOrchestrator` re-dispatched every ~30s
  (`INSTANCE_ALREADY_RUNNING`). Fix: adopt/resurrect re-derives `InstanceState.RUNNING` (we only adopt an instance the
  daemon reports alive whose record we lost — it's a running server). Proven live: a cold-elected leader logged
  `adopting running instance lobby-1` then **zero** re-dispatch churn; game pid unchanged.
- **DONE — renewable readiness (the one-shot `/ready` is now re-asserted every heartbeat).** Picked the
  plugin-re-asserts design over daemon-detects: the daemon structurally *cannot* know readiness (it only sees
  process-alive — `ServerProcess` never emits RUNNING), so the in-server workload is the only authoritative source.
  Clean separation: **existence/placement is the daemon's authority** (renewed via handshake `running_instances` →
  adopt), **readiness is the workload's** (renewed via `/ready`). No new timer or persistent connection needed — both
  plugins already heartbeat metrics on a scheduler, so they now re-call `reportReady()` on that same cadence
  (`AbstractCloudPlugin` server = 10s; `AbstractProxyCloudPlugin` proxy = 30s). Controller side: both `/api/plugin/ready`
  and `/api/proxy/ready` now route through `ClusterState#renewInstanceReadiness`, which promotes **only** from the
  pre-ready states (`SCHEDULED`/`PREPARING`/`STARTING → RUNNING`) and is a deliberate no-op for `RUNNING` (already
  ready), `DRAINING`/`STOPPING` (must never un-drain — and the validator *does* allow `DRAINING→RUNNING`, so a repeating
  ping through the raw setter would be a footgun), and terminal `STOPPED`/`CRASHED` (resurrection stays the adopt path's
  job — it holds the node/port to rebuild the record). The adopt→RUNNING heuristic is kept as the immediate fast-path on
  failover; renewable readiness is the authoritative backstop that continuously *validates* the guess and self-heals two
  failure classes the heuristic didn't: (1) a **lost one-shot `/ready`** (network blip at boot) no longer pins an
  instance at STARTING — the next heartbeat promotes it; (2) post-Phase-6 cold leaders self-correct from truth instead
  of relying on the guess being right. Covered by `ClusterStateReadinessTest` (7 cases incl. the DRAINING/STOPPING/
  terminal no-ops). Not yet validated live.
- **Fleet left clean:** ctrl-1 leader, ctrl-2/ctrl-3 followers, all controllers on the failover-fix jar, daemon
  node-frankenstein-1 on the seed-list jar with a 3-seed config + populated `known-controllers.json` + fresh
  `lobby-2` RUNNING.

---

## 2026-06-21 — single-writer correctness review (NOT a live run; design+code audit) — **OPEN**

Surfaced while reviewing the single-writer model after the Phase-6 (3e) lease→leadership collapse. The daemon
command path is properly epoch-fenced (`NodeMessageDispatcher` stamps `leadership.currentEpoch()`,
`MessageDispatcher.acceptEpoch` rejects stale-epoch at the daemon), so the *catastrophic* outcome — two leaders
causing physical side effects (double-spawn, double-port, duplicate process) — is prevented. These two findings are
the **two surfaces that are NOT yet fenced**: REST reads and Mongo state writes. Neither is a data-plane safety bug;
both are control-plane correctness gaps. Priority-0 prerequisite for the whole election argument: **run a real
3-member replica set across 3 zones** (the controller enforces RS *mode* at `PrexorCloudBootstrap.java:577`, but the
shipped compose has no `--replSet` and docs bless a single-member RS — single-member gives CAS but no majority
quorum, so partition-safety is off). A 3-node RS does **not** subsume finding #12 — they're orthogonal.

### 11. Followers serve REST reads from a frozen in-memory view, with no guard or redirect — **fix-A DONE (`cde80b8`)**

> **▶ Fix-A landed 2026-06-21 (Phase 6 3e):** `LeaderRedirectMiddleware` — a non-leader now returns `307`
> to the leader's REST address (resolved from `Member.restAddr` via the leadership holder), the HTTP analog
> of the daemon handshake redirect. Health/ready/metrics exempt; no known leader → `503 Retry-After`. So
> followers no longer silently serve stale/empty app data — the "safe by luck" invariant is now enforced.
> The plugin-token read-through is now Mongo-backed too (3f, `6e4d0ee`: `ClusterState` →
> `MongoWorkloadTokenStore`, `hydratePluginTokenFromStore`), so a cold leader reads a still-valid token
> back instead of 401-ing. Remaining: only (fix-B) a real Mongo-backed follower *read* path + consistency
> classification, and only if the leader becomes a throughput bottleneck. Original analysis below.

- **Symptom (latent):** a dashboard/CLI/plugin that reaches a **non-leader** controller gets a `200 OK` built from
  stale (today) or empty (after Phase 3f) instance/node/metrics data. Silent-wrong, not an error. Only "safe by luck"
  today because clients happen to reach the leader (CLI seed-list / dashboard pointed at the leader) — nothing enforces it.
- **Evidence:** the `rest/` layer has **zero** `isLeader`/redirect/`307`/`Location` logic (the daemon path *does*
  redirect followers via `DaemonConnectionLifecycle.applyLeadership` → `leader_grpc_addr`; REST has no analog). Hot
  reads come from the in-memory view (`InstanceRoutes`/`NodeRoutes` → `controller.clusterState().getAllInstances()/
  getInstance()/getNode()/getInstanceMetrics()`). That view is **frozen on a follower**: `clusterState.hydrate(...)`
  runs once at boot (`PrexorCloudBootstrap.java:366`); the only refresh, `reconcileInstancesFromRedis()`, is called
  **only** from the leader-gated `Scheduler.evaluate()` (`Scheduler.java:279`); and followers redirect daemons away so
  they hold no sessions → no heartbeat updates. A few reads *are* follower-consistent (Mongo-backed: composition plan,
  console history, registered nodes via `stateStore.*`).
- **Trajectory:** today = silently *stale* (boot snapshot from the shared `RedisRuntimeStore`); after **Phase 3f**
  drops the node/instance/player projection, the follower loses its hydration source → silently *empty*.
- **Also:** plugin-token validation on a follower works *today* only via the Redis read-through
  (`ClusterState.validatePluginToken` → `hydratePluginTokenFromRedis`, `ClusterState.java:498/528`). After Phase 6 this
  must be repointed to `MongoWorkloadTokenStore` **on followers too** (non-null store) or the 401-on-follower returns.
- **Fix direction:** (A) **near-term, cheap, correct** — a follower returns `421 Misdirected Request` (or `307` to the
  leader's REST addr), the HTTP analog of the daemon redirect; makes the wrong-read impossible. (B) **scaling, later** —
  give followers a real Mongo-backed read path (`majority`/causal read concern) and classify endpoints
  linearizable-vs-eventual; **do not do (B) without (A)** — until followers read from Mongo they must not answer reads.

### 12. `MongoStateStore` state writes are not epoch-fenced (deposed-leader write window) — **OPEN**
- **Symptom:** during a failover-overlap window (e.g. the old leader GC-pauses past the 15s TTL, a new leader acquires,
  the old leader wakes and writes before re-checking `isLeader()` — a concrete TOCTOU exists in
  `InstancePlacementCoordinator`, which persists to Mongo before a later leadership re-check), a **deposed** leader can
  last-writer-wins-clobber instance/deployment/node/**intent** docs in Mongo. Bounded control-plane state divergence
  (flapping, stale records, a new leader acting on a stale workflow intent) — **not** two leaders acting on the world
  (the daemon epoch fence prevents that).
- **Evidence:** `MongoStateStore` writes filter only on `seqId`/`_id`, no epoch reference across ~7 write paths
  (~`:282,:332,:367,:624`). `reservePlacement` (`ClusterState.java:114` → `nodeRegistry.reservePlacement`) is an
  **in-memory atomic CAS** — it guards the *intra-leader* concurrent-placement race (the earlier double-port fix), but
  it is **not** epoch-aware: a deposed leader reserving against its own stale `NodeRegistry` is not blocked, so ports
  are **not** covered by a cross-leader fence.
- **Residual-risk concentration:** running-state docs get scrubbed by daemon re-announce + `instance_id` natural-key
  idempotency; **workflow-intent docs (deployment-in-progress, start-retry, drain, healing) have no daemon-truth source**
  and are the soft target — a stale intent re-enters the physical world via the *valid new* leader (whose command is
  correctly epoch-stamped, just wrong).
- **Fix direction (stamp/txn hybrid):**
  - *Tier 1 (default, cheap, self-healing):* route every state mutation through one helper that stamps
    `ownerEpoch = leadership.currentEpoch()`; the new leader's reconcile treats any doc with `ownerEpoch < myEpoch` as
    non-authoritative and supersedes it. Fits the existing level-triggered/daemon-truth model; no per-write txn.
  - *Tier 2 (destructive/irreversible writes + intents):* wrap in a Mongo transaction that asserts the leadership doc
    still reads `{holder:me, epoch:e}` in-session and aborts on mismatch — rejects the deposed write at write time.
    Apply to terminal transitions, port release, deployment commit, and the intent docs Tier 1 can't safely converge.
  - Orthogonal to the 3-node-RS prerequisite: quorum stops two lease grants; it does **not** stop a deposed leader
    writing LWW to the one true primary it still has a valid connection to.

---

## Rewrite branch (`rewrite/single-writer-control-plane`) — 2026-06-21 B-track failover gate

### Plugin token 401-loop during the new-leader *adopt gap* on failover — **DONE (committed `52f617c`, validated live 0×401)**
- **Fix (chosen: read-through-ish / grace):** `WorkloadIdentityRegistry.instanceLive` now accepts a still-durable,
  unexpired plugin token for an *absent* instance while the controller is in its post-takeover convergence window
  (`ConvergenceGate.isObserving()`, wired via `ClusterState.setPostTakeoverGraceSupplier`). Strict again the moment the
  daemon reports (observation ends + instance is live); a *known* terminal instance is still rejected; the window is
  bounded by the convergence grace. **Live re-test:** stop the leader under a running Paper instance → **0×401**
  (was ~14), only 2 transient 503s in the sub-second no-leader instant. Unit test
  `WorkloadIdentityRegistryTest.adoptGapGraceAcceptsAbsentInstanceOnlyDuringConvergence`.

- **Found:** failover gate (kill the leader under a running Paper instance) on `rewrite/single-writer-control-plane`,
  with the new leader-following plugin + `CLOUD_CONTROLLER_SEEDS` injection live.
- **Symptom:** for ~10 s after a leader change the in-server plugin's controller calls (`/api/plugin/ready`,
  `/api/plugin/metrics`, token refresh, the SSE state-stream ticket) get **HTTP 401** from the *new* leader, then
  recover cleanly (0×401 thereafter). The game server keeps running; only the plugin↔controller sync degrades.
- **Root cause (live-traced):** recovery lands in the *same second* as
  `DaemonConnectionLifecycle - reconcileInstances: adopting running instance lobby-1` (17:03:14), 9 s after
  `MongoLeaderElector - Acquired leadership epoch=7` (17:03:05). So plugin-token validation on the new leader is gated
  on the instance being present in its **live (daemon-reported) ClusterState**, not on the **Mongo `plugin_tokens`**
  doc where the token is already persisted (the steady-state 401-fix). Until the daemon re-asserts the instance
  (≈ convergence-observation / adopt time) the token is unknown to the new leader.
- **Not** the bf8d2e7 bug (bearer stripped on a cross-host 307): that is fixed — with the seed list (committed
  `99006cb`) + the leader-following client, the plugin *reaches* the new leader and recovers on its own instead of
  stranding on the dead one. This residual only **delays full recovery by one adopt cycle**.
- **Fix options (user's call — security tradeoff):**
  - *(a) read-through:* validate a plugin token against the Mongo `plugin_tokens` doc even before the instance is
    adopted (token is already durable there). Closes the window; accepts a token for an instance the new leader hasn't
    yet seen reported (spoofing surface if a token leaks).
  - *(b) prime on takeover:* bulk-load `plugin_tokens` into the new leader's validation cache at leadership
    acquisition, so adoption isn't a prerequisite for auth.
  - *(c) accept it:* ~10 s of self-healing plugin-sync degradation per failover, no player impact — document & move on.
- **Repro:** running Paper instance (new plugin) → `docker stop`/kill the leader → watch the instance `latest.log`
  for the `BaseControllerClient … HTTP 401` burst that ends exactly at the daemon's `Connected to controller` + the
  leader's `adopting running instance` line.

---

## Rewrite branch — 2026-06-22 Phase-6 (Redis/Valkey removed) HA validation

### Daemon stranded on the demoted ex-leader after a *Mongo-partition* failover — **FIXED + validated live (uncommitted)**
- **Found:** running the Gate-E partition split-brain test on the Redis-free fleet — isolate the **leader** from
  **Mongo only** (`iptables ... -d <mongo>:27017 -j DROP`), leaving its daemon gRPC streams intact.
- **Symptom:** the ex-leader self-fences correctly (`prexorcloud.leadership.is_leader → 0` within `ttl − safetyMargin`,
  local guard, no Mongo round-trip) and a successor elects (epoch bumps), **but the daemon stays glued to the demoted
  ex-leader** because its TCP stream never broke. The daemon only redirects on a *handshake*, and a live stream
  doesn't re-handshake. Result: the daemon keeps heartbeating a **follower**, the **real leader sees the node
  `OFFLINE`/`DISCONNECTED`** and its service list is empty — the node is unmanaged by the control plane (the game
  keeps running locally, but the leader can't observe or command it).
- **Why it surfaced now:** Phase-6 deleted the cross-controller relay (`RedisEventBridge`, slice `3e-G`) — a follower
  can no longer relay commands for a daemon attached to it. Pre-Phase-6 the relay masked this; post-Phase-6 the daemon
  MUST be on the leader. Contrast the **crash** failover (`docker stop` leader): the stream closes immediately → daemon
  redirects in ~3 s (works). And the **GC-pause** case (SIGSTOP): the stream eventually times out (~30–40 s) → daemon
  redirects (works, just slow). Only the **partition** case (ex-leader JVM healthy, only Mongo cut) strands the daemon
  indefinitely, because nothing ever breaks the stream.
- **Root cause:** on leadership loss (`MongoLeaderElector` `onLost` / step-down) the controller does **not** close /
  reset its daemon sessions. A demoted leader should drop its `NodeSession`s so each daemon reconnects → handshakes →
  gets `leader_grpc_addr` for the new leader.
- **Fix (done, jar `b49ed08b`):** the leadership-lost hook now closes all daemon streams.
  `NodeSession.disconnect(reason)` terminates the stream with `UNAVAILABLE` (synchronized on the same monitor as
  `send`); `NodeSessionManager.disconnectAll(reason)` closes every active session; bootstrap's `onLost()` calls it.
  The elector already calls `demote()` (→ `onLost`) eagerly once the local guard trips (or on the renew rejection
  when the partition heals), so the streams get released on demotion — daemons then reconnect, re-handshake, and the
  now-follower redirects them to the new leader. Makes partition-failover behave like crash-failover for daemons.
  Unit tests: `NodeSessionManagerTest.disconnectAllTerminatesEveryStream` / `...OnEmptyManagerIsNoOp`.
- **Validated live (2026-06-22):** partition the leader (ctrl-1) from Mongo > TTL → successor ctrl-3 elects (ep15);
  on heal ctrl-1 logs `Relinquished leadership` then `NodeSessionManager - Closed 1 daemon session(s) — leadership
  lost`; the daemon receives `UNAVAILABLE: leadership lost — reconnect to new leader`, reconnects, is redirected
  `to leader at 10.0.0.7`, and lands on ctrl-3 in ~6s — **no manual restart**. New leader then shows the node
  `ONLINE` and lobby-2 `RUNNING`. Game PID untouched throughout.
- **Timing caveat (not fixed, low priority):** under a pure black-hole partition (iptables DROP, no RST) the
  leader's blocked Mongo renew doesn't throw until the partition heals, so `demote()`/`onLost` (and thus the stream
  close) fires on *heal*, not at the ~`ttl−safetyMargin` guard trip. Recovery is automatic either way; to also redirect
  *during* a long black-hole, give the elector's lease collection a CSOT `withTimeout(...)` (~renewInterval) so the
  renew fails fast and the existing eager-demote path runs — deferred (needs tuning/soak to avoid spurious demotes on
  normal Mongo latency blips). A connection-reset partition or Mongo failover throws promptly and recovers at the guard.
- **Earlier manual recovery (pre-fix, for the record):** restart the follower the daemon was stuck on → stream breaks
  → daemon redirects to the leader. No longer needed.

### Things that PASSED this session (for the record)
- **Phase-6 with Valkey *stopped*:** control plane fully healthy with `prexor-data-valkey-1` down (Mongo-only) —
  leadership stable, 0 errors, game RUNNING, **0×401** plugin-token validation (leader-memory + Mongo read-through).
- **Crash failover (Gate E):** kill leader → successor elects (epoch++), daemon redirects via a follower's
  `leader_grpc_addr`, re-adopts the game, 0×401; controller failover leaves the game PID untouched.
- **GC-pause fence:** SIGSTOP the leader past its lease → successor elects; on SIGCONT the zombie self-fences
  (`Lease renew rejected — lost leadership`) and does not reclaim — single leader throughout.
- **Local monotonic guard (split-brain safety):** under a Mongo partition the leader reports `is_leader 0` at
  `renew_age ≈ 14.6 s` (> `ttl − safetyMargin`) **before** the lease TTL (15 s) lets a successor in → no two-leader
  window. Epoch was strictly monotonic (9→13) with a single holder at every step across 5 leadership changes.

---

## 2026-06-23 — DYNAMIC scale-down live test: daemon outbound-stream not serialized — **FIXED + validated live**

Drove a live DYNAMIC scale-down test on the Redis-free fleet (ctrl-1 leader, single daemon node-frankenstein-1).

### Correction: the suspected "scale-down gates on uptimeMs which is always 0" bug does **not** exist
- A prior session concluded DYNAMIC scale-down was permanently broken because `ScalingEvaluator.evaluateScaleDown`
  gates on `InstanceInfo.uptimeMs / 1000 > scaleDownAfterSeconds` and the instances reported `uptimeMs:0`.
- **Re-verified live: `InstanceInfo.uptimeMs` populates correctly** (a freshly scaled-up instance read `291363` ms,
  matching its ~5 min age; a long-runner read ~9.2 h matching its process `etime`). The earlier `uptimeMs:0` was a
  **greedy-regex parse artifact** against the list endpoint, compounded by a ~60 s warmup (the in-server plugin's
  first metrics carrying non-zero uptime hadn't arrived yet). **Scale-down works**: dropping `min` reaped down to
  `min` exactly as designed. No code change for this — it was a measurement error.

### The real bug: daemon's outbound gRPC stream (`DaemonGrpcClient.trySend`) is not serialized — **FIXED + validated live**
- **Symptom:** every scale-down `StopInstance` was followed ~2 s later by `DaemonGrpcClient - Connection error:
  CANCELLED: Failed to stream message` (daemon side) / `DaemonServiceImpl - Stream error … CANCELLED: client
  cancelled` (controller side). The controller then marked **every** instance on that node CRASHED (collateral — not
  just the one being stopped), the daemon reconnected (~3 s, same PID — process never died), `RecoveryOrchestrator`
  re-placed the reaped instance, and DYNAMIC scale-down trimmed it again → a **60 s flap** that never converged.
- **Root cause:** `DaemonGrpcClient.trySend` calls `stream.onNext(message)` with **no synchronization**
  (`cloud-daemon/.../grpc/DaemonGrpcClient.java:385`). Every sender funnels through it from independent threads —
  `sendConsoleOutput` (a per-instance console-capture virtual thread), `sendInstanceStatus`, `sendPong` (heartbeat),
  `sendCrashReport`. A gRPC `StreamObserver` is **not** thread-safe for concurrent `onNext`; an instance stop fires a
  burst of concurrent sends (shutdown-console spam + status + heartbeat) that interleave and corrupt the outbound
  frame → `CANCELLED` → control stream torn down. This is the **exact daemon-side mirror of committed controller fix
  #10** (`NodeSession.send` per-stream `synchronized`), which was never applied to the daemon.
- **Fix:** wrap `stream.onNext(message)` in `synchronized (stream)` inside `trySend` (synchronize on the stream object
  so a reconnect's fresh stream gets its own monitor). The handshake send in `connect()` is left as-is — it runs
  before `state == CONNECTED`, and `trySend` short-circuits while not CONNECTED, so no steady-state sender races it.
  Unit test `DaemonGrpcClientSendSerializationTest.concurrentSendsDoNotInterleaveOnNext` (8 threads × 40 sends through
  a concurrency-detecting `StreamObserver`); confirmed it **fails without** the fix and passes with it.
- **Validated live (2026-06-23):** built + deployed the fixed daemon jar (`md5 a0734787`) to node-frankenstein-1
  (stop→swap→start), scaled lobby to 3, then `min=1`. Scale-down reaped **3→1 gracefully** (RUNNING→STOPPING→STOPPED),
  settled at exactly 1 and stayed there. Controller log showed only the two intended `Scaling down … stopping …`
  lines — **zero** `client cancelled`, **zero** `disconnected -- marking … CRASHED`, **zero** re-adopt/flap.
  Scale-up to 3 was also clean (no cancels). ⚠️ **Deployed jar is UNCOMMITTED on the fleet** until the next rebuilt
  daemon jar is rolled (only node-frankenstein-1 carries it; node-fra-2 is on the old jar).

### Secondary — a brief control-stream blip marked **all** of a node's instances CRASHED — **FIXED + validated live**
- `InstanceLifecycleManager` marked every instance on a node CRASHED the instant its gRPC session dropped, with no
  grace. A ~2–3 s reconnect (transient stream error, leader redirect, brief network blip) therefore crashed healthy,
  still-running instances; they recovered via re-adopt/re-place, but it was needless churn and the amplifier that
  turned the `trySend` cancel into a sustained flap.
- **Fix:** `onNodeDisconnected` no longer crashes synchronously — it defers `NODE_DISCONNECT_GRACE_SECONDS` (20 s) via
  the existing `cleanupExecutor`, then `markNodeInstancesCrashedIfStillGone` re-checks: if the node is back in
  `ClusterState` (it re-registers + re-adopts its `running_instances` on handshake) the instances are left running;
  only if the node is *still* gone are they marked CRASHED (→ healing). Re-checks leadership at fire time (a failover
  during the grace hands ownership to the new leader). Tests: `InstanceLifecycleManagerTest` (defer / reconnect-skip /
  still-gone-crash). The daemon is ground truth that the process is alive, so a blip no longer fakes a crash.
- **Validated live (2026-06-23):** `ss -K` reset the daemon→leader gRPC socket → controller logged
  `deferring CRASHED marking for 20s`, the daemon reconnected in ~5 s, and at +20 s `reconnected within grace —
  leaving its instances running` fired — **zero** `marking … CRASHED`, the instance stayed RUNNING, game PID
  unchanged. (A node that stays down past the grace is still correctly crashed + healed.)

### 2026-06-23 (later) — deep gRPC hardening: single guarded writer + flow control + keepalive — **DONE + validated live**
A full audit of the gRPC layer (not just the known-weak daemon send) found the same defect class still live on the
controller, plus two transport-config gaps. All fixed via one shared component and validated live.
- **Controller had the same concurrent-`onNext` race.** `NodeSession.send` synchronized the daemon stream, but
  `DaemonConnectionLifecycle` (handshake ack / pre-warm) and `DaemonTemplateRequestHandler` (template chunks, on their
  **own virtual thread**) wrote the same `responseObserver` directly, bypassing that lock. A template response racing a
  `StartInstance` command (both happen during provisioning) corrupts the frame → the identical CANCELLED/flap. The
  "single outbound chokepoint" premise of fix #10 was simply false.
- **Fix — `GuardedStreamWriter<T>` (cloud-protocol).** One wrapper owns the raw observer privately, so a concurrent
  `onNext` is impossible to reintroduce at any call site. It also adds **gRPC flow control**: writes only while the
  transport `isReady()`, otherwise a **bounded queue** (`STREAM_WRITER_QUEUE_CAPACITY`, drains on the on-ready
  callback) — best-effort console is shed under backpressure, commands/status never. Broadened the catch to cover
  `IllegalStateException` (half-closed call), not just `StatusRuntimeException`. Wired on both sides: controller wraps
  the observer once in `DaemonServiceImpl.connect` (handshake/pre-warm/template/NodeSession all route through it);
  daemon restructured to `ClientResponseObserver.beforeStart` to obtain a flow-controlled `ClientCallStreamObserver`,
  console via `offer` (droppable), everything else via `send`. Unit tests: `GuardedStreamWriterTest` (6 — concurrency,
  flow-control gating, drop policy, terminal/failure, plain-observer degrade) + `DaemonGrpcClientSendSerializationTest`.
- **Keepalive gaps.** Server set `keepAliveTime`/`permitKeepAliveWithoutCalls` but **not `permitKeepAliveTime`** —
  default minimum is 5 min, so the daemon's 30s pings were only spared a `GOAWAY too_many_pings` because data frames
  kept resetting the counter (a quiet, instance-less node would eventually be culled). Added
  `permitKeepAliveTime(15s)`. Daemon channel added `keepAliveWithoutCalls(true)` so a frozen/black-holed leader is
  detected on the keepalive (~40s) rather than the OS TCP timeout. (`MAX_MESSAGE_SIZE`=100 MB and the bootstrap unary
  deadline were already fine.)
- **Validated live (2026-06-23):** rolled the new controller jar to all 3 (followers→leader; clean failover
  ctrl-1→ctrl-2 epoch16→17) and the new daemon jar to node-frankenstein-1 (handshake via the writer, heartbeats
  flowing). Ran a provisioning storm (scale 1→3 = concurrent template-fetch + command writes) then scale-down
  (3→1, console burst): both clean, instances reached RUNNING / drained gracefully, and the controller log had
  **zero** `client cancelled` / `Failed to stream` / `disconnected -- marking CRASHED` across the whole window.
  ⚠️ node-fra-2 still on the old daemon jar (one managed node validated); roll it when reactivated.

### 2026-06-24 — finding #12: Tier-1 epoch fence on authority-sensitive Mongo writes — **DONE + validated live**
The single-writer store relied on the leadership lease + local monotonic guard alone; a deposed leader that hadn't
yet noticed it lost the lease could still issue a last-writer-wins Mongo write and clobber the new leader's state.
This adds a storage-layer fencing token as defense-in-depth behind the (already-live) local guard.
- **Mechanism (`MongoStateStore`).** Every authority-sensitive write is stamped with the writing leader's fencing
  `ownerEpoch` (`= leadership.currentEpoch()`, wired in bootstrap to `MongoLeaderElector::currentEpoch`) and applied
  only when the stored doc carries no epoch (legacy / first write) or `ownerEpoch <= mine`. Three helpers —
  `fencedReplace` (conditional replace; on no-match either insert-if-absent or drop-if-out-epoched via the unique
  `_id`), `fencedUpdate` (in-place, narrowed filter + stamp), `fencedDelete`. **Fail-soft**: a rejected write is
  logged + counted (`fencedWriteRejections`), never thrown, and `epoch <= 0` disables the fence entirely
  (single-controller / bootstrap window / `alwaysLeader`'s fixed `1L` all pass via `<=`). The live leader always holds
  the highest epoch, so it never rejects its own writes — no wedge.
- **Scope (the reconciler's durable work-queue).** Workflow intents (transfer/drain/healing/start-retry + deletes),
  composition plans (+delete), deployment FSM (`updateDeploymentState`/`Progress`), node registry
  (`registerNode`/`deleteRegisteredNode`). **Left plain:** audit, console, crashes, templates, shares, prefs,
  `updateNodeLastSeen` (heartbeat), `createDeployment` (unique-indexed insert). Instance RUNNING/CRASHED state isn't in
  this store (in-memory + daemon-authoritative), so it's out of scope by design. `ownerEpoch` is additive metadata —
  no reader touches it, no migration needed (legacy unstamped docs are writable).
- **Footgun handled.** The epoch source (`cluster_leadership`) is a `cluster_*` collection the backup excludes, so a
  restore could reset the epoch below stamped state docs. Fail-soft turns that into *visible skipped writes + a loud
  WARN*, not a frozen control plane (operational follow-up: restore must also advance the lease epoch past the restored
  `ownerEpoch` high-water).
- **Tests.** `MongoStateStoreFenceIT` (4 — replace/delete/update fence, fence-disabled + legacy + insert paths). Skips
  without a local Mongo; **ran 4/4 against a throwaway `mongo:8` on data-1** before deploy (validates the real-MongoDB
  semantics: `matchedCount==0`→insert→dup-key, `exists(false)` legacy match). `:cloud-controller:test` green.
- **Committed** `a899943`. Bundled the **3-seed Mongo URI**
  (`mongodb://10.0.0.2:27017,10.0.0.6:27017,10.0.0.7:27017/prexorcloud?replicaSet=rs0`) into the controller deploy so a
  cold controller restart can bootstrap off any RS member (driver already auto-discovered for failover; this fixes the
  cold-start single-seed gap).
- **Validated live (2026-06-24):** rolled jar `a899943` to all 3 controllers (followers→leader). Fence **armed +
  stamping** — the deployed leader stamped `nodes.ownerEpoch` on daemon re-enroll; **zero spurious rejections** in
  steady state; the running lobby instance (PID 1533327) was **adopted, never cycled**, through the whole rolling
  deploy. Forced the reject path through a **real failover**: pre-stamped the node doc `ownerEpoch=9999`, bounced the
  leader → ctrl-3 acquired epoch 19 → its `registerNode` (epoch 19) was **fenced** (`Fenced write rejected in nodes
  ... myEpoch=19 ... dropping the stale write`) while the **same enroll still adopted lobby-2** — fail-soft proven
  end-to-end. Restored the node doc to `ownerEpoch=19`. Fleet left: leader **ctrl-3 epoch 19**, RS 3-member healthy,
  all controllers on `a899943`.
- ⚠️ Tier-2 (transactional fence for destructive multi-doc writes via `runInTransaction`) deferred — Tier-1 covers the
  clobber class. node-fra-2 still on the old daemon jar (unaffected by this controller-only change).
