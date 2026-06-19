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

### 9. `disposition=PERMANENT` start-failure is re-dispatched in a tight loop — **DIAGNOSED**
- **Symptom:** the daemon rejects `StartInstance` with `RUNTIME_PROVISION_FAILED disposition=PERMANENT`, yet the
  controller re-dispatches the same (and gap-filled new) instances dozens of times per second.
- **Cause:** the PERMANENT disposition is not honored as a stop-retry signal — placement/recovery re-fires
  immediately (`DaemonCommandAckHandler` logs the PERMANENT reject, then the instance is re-placed). The
  crash-loop detector (threshold 3) doesn't arrest it because these are prepare/provision-stage failures, not
  process crashes.
- **Fix candidate:** treat `disposition=PERMANENT` as a hard stop — quarantine the instance/group and surface the
  error instead of re-dispatching until something external changes (e.g. the catalog is repaired).

### 10. Concurrent `StartInstance` dispatch to one daemon corrupts the gRPC frame — **DIAGNOSED**
- **Symptom:** under the burst re-dispatch above the controller throws
  `io.grpc.StatusRuntimeException: INTERNAL: Failed to frame message` /
  `IndexOutOfBoundsException … PooledUnsafeDirectByteBuf(freed)` → `ServerCallImpl … Cancelling the stream`, and
  the daemon reconnect-storms (a new session every few seconds).
- **Cause:** concurrent `StreamObserver.onNext(...)` on one daemon's `ServerCallStreamObserver` — `NodeSession.send`
  is called from multiple threads (parallel placement / virtual threads) with no per-stream serialization. gRPC
  stream observers are **not** thread-safe for concurrent `onNext`.
- **Fix candidate:** serialize writes per daemon stream (a per-`NodeSession` lock or a single-consumer outbound
  queue around `responseStream.onNext`).
- **Note:** the send path is pre-existing (not introduced by the rewrite), but the single-writer's burst dispatch
  makes it easier to hit. Not seen in steady state — only under the failure-driven re-dispatch storm.

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
