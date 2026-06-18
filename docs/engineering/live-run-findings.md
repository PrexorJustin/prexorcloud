# Live-run findings — product fixes to land (durable backlog)

**Why this file exists:** `northstar-plan.md` is a *transient, delete-at-teardown* checklist (see its Part 14).
Many real product bugs were found during the live acceptance run and logged **only** inside it, or fixed as
**uncommitted** working-tree code. Both vanish when the plan is deleted / the tree is reset. This file is the
**durable home** for the open fixes so they survive teardown. When `northstar-plan.md` is finally removed, sweep
any remaining open findings from it into here first, and commit the working-tree fixes.

Status legend: **OPEN** = not yet coded · **CODED (uncommitted)** = fix in the working tree, must be committed ·
**DONE** = committed.

**2026-06-18 fix pass:** all the items below are now CODED (uncommitted). (g)/(d)/(h) are deployed to all 3 fleet
controllers and **live-validated**; the daemon `JarCache` fix + Geyser descriptor are built but their live
re-validation is pending a daemon redeploy (it disconnects active players).

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
- *Not yet covered:* state-convergence (peers keep a stale state for a known instance) and safe removal of
  genuinely-gone instances — both need the owner-write-back hazard handled; lower priority since add-if-missing
  removes the duplicate-placement path.

### 3b. (g) SECOND FACET — `INSTANCE_ALREADY_RUNNING` ack leaves the instance stuck `SCHEDULED` — **OPEN**
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
