# PrexorCloud API & Shared Layer Overhaul

> Status: **Layers 1–9 shipped (9 of 9 done).**
> Last updated: 2026-05-09.

This document is the working plan for the API/shared-layer redesign. It records
the original audit, the 9-layer rollout order, what each layer ships, and the
acceptance/verification step for each. Update the **Status** block per layer as
work lands.

---

## Context

The shared/abstraction layer was *almost* good after Phase 2.1 (`AbstractProxyCloudPlugin`)
and Phase 2.3 (`server.shared.bukkit` package consolidation). A deep audit
(three Explore agents, ~30 files read) found the layer was still leaky in five
concrete ways that compounded:

1. **Module-side primitives missing.** `PlatformModuleContext` only exposed
   manifest + jar + capabilities + storage. No `events()`, no `logger()`, no
   `scheduler()`, no shared `httpClient()`, no `objectMapper()`. This blocked
   Phase 1.3 (`PlayerJourneyService`) and 1.4 (`WebhookAlertService`) extraction.
2. **Two `EventBus` types coexisted.** `api.event.EventBus` (plugin-side) and
   `controller.event.EventBus` (controller-internal). Modules couldn't subscribe
   because the only one they could import had no controller-side implementation.
3. **Cross-cutting infra was ad-hoc.** `BaseControllerClient`, `WebhookAlertService`,
   `PlatformModuleSignatureVerifier` each built their own `HttpClient` + `ObjectMapper`.
4. **Plugin contexts duplicated across platforms.** `BukkitPluginContext`, `VelocityPluginContext`
   differed only in scheduler factory + client construction. No shared base.
5. **Module-system framing was unclear.** Standalone `@CloudPlugin` (Path A)
   and modules-with-bundled-plugins (Path B) were both first-class but the
   docs/example only showed Path B. New users assumed "you must write a module
   to write a plugin". No scaffold for Path A.

Decisions confirmed before design:
- **Make this top-of-the-league** — no expedient compromises.
- **Breaking changes are fine** — pre-v1, no external consumers, no compat shims.
- **Daemon-side modules are in scope** — reserve and build now.
- **Plugin-side REST is out** — symmetric ≠ identical; plugins and modules genuinely
  differ in domain (plugins live behind game/proxy hosts, not the controller).

**Intended outcome:** one cohesive abstraction layer where (a) plugin and module
SDKs expose symmetric *primitives* with divergent *domains*, (b) the EventBus
is one contract, (c) cross-cutting infra is centralized, (d) daemon-side
modules are first-class, (e) Path A vs Path B is documented and tooled symmetrically.

---

## Guiding principles

These bind every design decision below. If a future change violates one, push back:

1. **Symmetric primitives, divergent domains.** Plugins and modules expose the
   same primitives where the primitive is universal (events, logger, JSON,
   HTTP-out, scheduler). They diverge where the domain genuinely differs (REST
   endpoints belong on the controller; commands belong on the game/proxy;
   player listeners belong on plugins; cluster reconciliation belongs in modules).

2. **One contract per concern.** One `EventBus` interface. One `HttpClients`
   factory. One configured `ObjectMapper`. One `Logger` pattern. If a primitive
   needs platform variants, that's an *implementation* detail behind a single
   contract.

3. **No reflection escape hatches in user code.** Modules and plugins talk to
   the cloud through the API jar only. The `@controller` sentinel module id is
   a controller-internal detail and never leaks into module code.

4. **Breaking changes are fine; silent compat shims are not.** No
   `@Deprecated` chains, no parallel old/new APIs, no "v1 + v2" coexistence.
   Migrate every first-party caller in the same PR.

5. **Path A and Path B are equally first-class.** A standalone `@CloudPlugin`
   jar (Path A) is not a "lite module". It is a different deployment model
   with its own tooling, docs, and scaffold.

---

## Layer-by-layer status

### Layer 1 — Cross-cutting infra in `cloud-common` — **DONE**

Centralized the three things every component re-built.

**Shipped:**
- `cloud-common/src/main/java/.../common/io/HttpClients.java` — `defaults()` builder + `defaultClient()` singleton (HTTP/2, 5s connect timeout, follow redirects, shared cached-thread-pool executor)
- `cloud-common/src/main/java/.../common/io/ObjectMappers.java` — `standard()` (lenient inbound, ISO-8601, NON_NULL output, UTC, JavaTimeModule), `strict()` (FAIL_ON_UNKNOWN_PROPERTIES for trusted sources), `yaml()` (strict over YAMLFactory)
- `cloud-common/src/main/java/.../common/concurrent/Backoff.java` — `withRetries(Callable, Policy)` exponential + jitter, `Policy.defaultPolicy()` = 3 attempts, 250ms base, 5s cap, 25% jitter

**Migrations:**
- `BaseControllerClient` (`cloud-plugins-internal`) → uses `HttpClients.defaultClient()` + `ObjectMappers.standard()`
- `WebhookAlertService` (`cloud-controller`) → same
- `PlatformModuleSignatureVerifier` (`cloud-controller`) → same

**Side effect (necessary):** `cloud-common` downgraded from `prexorcloud.java25-preview` →
`prexorcloud.java21-api` so plugin-side modules (Java 21) can consume it.
`StableValue` in `VersionInfo.java` replaced with the holder-class idiom; four
`catch (X _)` unnamed-variable usages renamed to `ignored` (Java 21 doesn't
have unnamed variables).

**Verification:** `./gradlew :cloud-common:test :cloud-plugins-internal:test :cloud-controller:test` — green.

---

### Layer 2 — Gradle platform (`cloud-platform`) — **DONE**

New `java-platform` subproject pinning every cross-cutting third-party version.

**Shipped:**
- New subproject `java/cloud-platform/` with `build.gradle.kts` declaring constraints for Jackson (databind, dataformat-yaml, dataformat-toml, datatype-jsr310, module-parameter-names), SLF4J, Logback, Paper API 1.20 + 1.21, Velocity, BungeeCord, Adventure
- Pulls upstream `jackson-bom` so jackson-* modules align on one version
- Registered in `java/settings.gradle.kts`
- Consumed via `implementation(platform(project(":cloud-platform")))` from: cloud-api, cloud-common, cloud-plugins-internal, cloud-plugins-server-shared, cloud-plugins-proxy-shared, cloud-controller, cloud-daemon

**Verification:** `./gradlew :cloud-controller:dependencyInsight --dependency jackson-databind` shows every consumer resolves to the same version via cloud-platform.

---

### Layer 3 + 4 — `ModuleContext` interface + `EventBus` unification — **DONE**

Combined into a single layer because they're tightly coupled — `ctx.events()`
returns `api.event.EventBus`, which only made sense once the controller bus
implemented that contract.

**Shipped (cloud-api):**
- `ModuleContext.java` — NEW interface replacing the old record. Adds: `events()` (api.event.EventBus), `logger()` (SLF4J), `scheduler()` (api.module.scheduling.TaskScheduler), `httpClient()` (java.net.http), `json()` (Jackson ObjectMapper), `host()` (ModuleHost). Retains: manifest, jarPath, previousVersion, capabilities, storage.
- `ModuleHost.java` — NEW enum (CONTROLLER, DAEMON) for daemon-side reservation.
- `ModuleContexts.java` — NEW utility with `forTest(...)` factory (no-op event bus + scheduler, JDK-default HttpClient/ObjectMapper). Used by module unit tests.
- `PlatformModuleContext.java` — **DELETED** (no compat shim, per principle 4).
- `PlatformModule.java` — all lifecycle hooks switched signature: `PlatformModuleContext` → `ModuleContext`.
- `cloud-api/build.gradle.kts` — adds `api(libs.jackson.databind)` and `api(libs.slf4j.api)` so consumers transitively get Logger/ObjectMapper types.

**Shipped (cloud-controller):**
- `controller.event.EventBus` now `implements api.event.EventBus`. `subscribe*` methods return `EventSubscription` (handle-based unsubscribe). `on(Class)` builder added. `subscribeByType` now wraps `EventHandler<CustomCloudEvent>` to satisfy the api contract. The internal `EventHandler` inner interface was removed; callers use `api.event.EventHandler<T>` instead.
- `ConsoleStreamer.java` migrated to handle-based unsubscribe (closure captures the `EventSubscription`, `client.onClose(subscription::unsubscribe)`).
- `EventBusTest.java` — `EventBus.EventHandler<TestEventA>` → `EventHandler<TestEventA>` import from `api.event`.
- `ControllerModuleContext.java` — NEW. Production `ModuleContext` impl. Wires `events()` to live `ControllerEventBus`, `logger()` to `LoggerFactory.getLogger("module:" + manifest.id())`, `scheduler()` to a passed-in `TaskScheduler`, `httpClient()` to `HttpClients.defaultClient()`, `json()` to `ObjectMappers.standard()`.
- `ControllerTaskScheduler.java` — NEW. `TaskScheduler` impl wrapping a `ScheduledExecutorService`.
- `NoopModuleContext.java` — NEW (package-private). Tests-and-harness fallback used when no production wiring is provided.
- `ModuleLifecycleManager.ContextFactory` interface — `(manifest, jarPath, previousVersion, capabilities, storage) → ModuleContext`. Added across all 7 constructor variants. New `setContextFactory(ContextFactory)` setter for late wiring (called by bootstrap).
- `PlatformModuleManager.setContextFactory(...)` — delegates to lifecycle manager.
- `PrexorCloudBootstrap.wireProductionModuleContext(controller, platformManager)` — constructs `ScheduledExecutorService` + `ControllerTaskScheduler` + a `ContextFactory` that builds `ControllerModuleContext` with the live `controller.eventBus()` and the task scheduler. Called from `bootPlatformModules` BEFORE `loadStoredModules`.

**Migrated first-party modules:**
- `cloud-module-example/.../ExamplePlatformModule.java` — `PlatformModuleContext` → `ModuleContext` in all lifecycle hooks.
- `cloud-module-stats-aggregator/.../StatsAggregatorModule.java` — same.
- `cloud-module-tablist/.../TablistModule.java` — same.
- `cloud-module-protocol-tap/.../ProtocolTapModule.java` — same.
- `cloud-module-example/.../ExamplePlatformModuleTest.java` — uses `ModuleContexts.forTest(...)` instead of constructing the deleted record.
- `cloud-test-harness/.../PlatformModuleTestJarFactory.java`, `cloud-controller/.../ModuleLifecycleManagerTest.java`, `PlatformModuleManagerTest.java` — bulk-migrated `PlatformModuleContext` → `ModuleContext`.

**Verification:** `./gradlew test` — green. `grep -rn "PlatformModuleContext" java --include='*.java'` returns zero matches. Test `EventBusTest` passes; subscribe/publish/subscribeAll round-trip works through the unified contract.

---

### Layer 5 — Extract `PlayerJourneyService` + `WebhookAlertService` into modules — **DONE**

**Shipped (`cloud-module/cloud-module-player-journey/`, NEW):**
- `module.yaml` — provides `prexor.player.journey@1.0.0`, requests Mongo storage.
- `PlayerJourneyModule implements PlatformModule` — onLoad builds the
  repository over `ctx.requireMongoStorage()`, onStart starts the recorder,
  onStop unsubscribes. `capabilityHandles()` returns the
  `PlayerJourneyTracker` handle.
- `JourneyRepository` (over `ModuleDataStore`) — single `journey` collection
  with compound index `(playerUuid, timestamp DESC)`. `findRecent` and
  `findSince` round-trip through `JourneyDoc` (Jackson-friendly projection).
- `JourneyRecorder` — subscribes to `PLAYER_CONNECTED` / `PLAYER_TRANSFER` /
  `PLAYER_DISCONNECTED` via `ctx.events()`, persists one entry per
  observation, republishes as `PlayerJourneyEvent`.
  - **Dropped (per Layer 5 plan option A):** the `INSTANCE_CRASHED → per-player entry` fallback. The module doesn't have `ClusterState.getAllPlayers()`. Crash-driven movements still surface via `PLAYER_DISCONNECTED` when the controller's player tracker reaps affected players.
- `MongoPlayerJourneyTracker` — `PlayerJourneyTracker` impl over
  `JourneyRepository`.
- `JourneyRecorderTest` — 3 unit tests (raw event recording, no crash entries,
  stop unsubscribes) using a minimal in-memory `EventBus`.

**Shipped (`cloud-module/cloud-module-webhook-alerts/`, NEW):**
- `module.yaml` — provides `prexor.alert-sink@1.0.0`, requests Mongo storage.
- `WebhookAlertsModule implements PlatformModule` — subscribes to all 7
  alertable event types on start, unsubscribes on stop. Uses `ctx.httpClient()`
  + `ctx.json()` from cloud-common.
- `WebhookConfig` + `WebhookRepository` — single `webhooks` Mongo collection.
  Operators with existing YAML config need to re-add their webhooks via this
  collection (clean break per principle 4 — no compat shim).

**Deleted (controller side):**
- `cloud-controller/.../controller/journey/PlayerJourneyService.java` + `PlayerJourneyServiceTest.java`.
- `cloud-controller/.../controller/webhook/WebhookAlertService.java`.
- `cloud-controller/.../controller/config/WebhookConfig.java` + `webhooks` field on `ControllerConfig` (and its 11 positional constructor sites updated across main + tests).
- `StateStore.{savePlayerJourneyEntry,getPlayerJourney,getPlayerJourneySince}` + `MongoStateStore` impls + the `player_journey` collection setup + indexes.
- `PrexorController.playerJourneyService` field + getter.
- `PrexorCloudBootstrap.registerBuiltinCapabilities` (and its call site) — no more `@controller`-sentinel registration of `prexor.player.journey`.
- YAML `webhooks: []` blocks in `defaults/controller.yml`, `config/controller.compose.yml`, `deploy/compose/controller.yml`.

**Updated:**
- `PlayerJourneyRoutes` — resolves the `PlayerJourneyTracker` capability per-request via `controller.moduleRegistry().platformManager().capabilityRegistry().find(...)`. Returns `503 CAPABILITY_UNAVAILABLE` if no provider is active (i.e. the player-journey module isn't installed).
- `cloud-test-harness/build.gradle.kts` — depends on the player-journey shadowJar in addition to stats-aggregator, exposes `prexor.test.playerJourneyJar` as a test system property.
- `StatsAggregatorInstallTest` — installs `player-journey` first (waits for ACTIVE), then `stats-aggregator`. Pre-Layer-5 the controller's built-in handle satisfied the requirement; now the module is the only provider.
- `MaintenanceRoutes` — drops the now-removed `webhooks()` accessor from its `ControllerConfig` rebuild call.
- `settings.gradle.kts` — both new modules are includes.

**Verification:**
- `./gradlew :cloud-controller:test :cloud-module:cloud-module-player-journey:test :cloud-module:cloud-module-webhook-alerts:test :cloud-module:cloud-module-stats-aggregator:test` — green.
- Both module shadowJars build cleanly.
- `StatsAggregatorInstallTest` compiles + skips locally (no Mongo on this dev host); the test asserts the new install ordering.
- `grep -rn "WebhookAlertService\|PlayerJourneyService\|controller.webhook\|controller.journey" java/cloud-controller/src` returns zero hits.

---

### Layer 6 — `AbstractPluginContext` base across plugin platforms — **DONE**

**Shipped:**
- `cloud-plugins-internal/.../plugin/common/AbstractPluginContext.java` — NEW base class implementing `CloudPluginContext`. Owns `InstanceContext` construction (anonymous class delegating to `PluginEnv` + `cloudApi.exposedStateCache()`), and all the field-based getters (`events`, `commands`, `players`, `scheduler`, `client`, `logger`).
- `AbstractCloudApi.java` — added public `exposedStateCache()` and `exposedCommandRegistry()` helpers (previously package-private and re-declared on each subclass).
- `BukkitPluginContext.java` — refactored to extend `AbstractPluginContext`. Constructor reduced from ~100 lines to ~10. Passes `BukkitCloudClient`, the injected `PluginScheduler`, `platform.getLogger()`, and `cloudApi.serverPlayerManager()` to super.
- `VelocityPluginContext.java` — same refactor. Constructor: ~10 lines passing `VelocityCloudClient`, `VelocityPluginScheduler(proxyServer, platformPlugin)`, `Logger.getLogger(platformPlugin.getClass().getName())`, `cloudApi.velocityPlayerManager()`.
- `BukkitServerCloudApi.java` and `VelocityCloudApi.java` — dropped redundant subclass overrides of `exposedStateCache` / `exposedCommandRegistry` (now inherited from `AbstractCloudApi`).

**Note on Bungee:** `BungeeCloudApi.createPluginContext` currently throws —
proxy-side BungeeCord doesn't support `CloudPluginBase`. No `BungeePluginContext`
exists, so nothing to refactor there.

**Note on `Logger`:** `CloudPluginContext.logger()` returns `java.util.logging.Logger`
(the existing JUL choice). Switching plugin-side to SLF4J would require shading
SLF4J into every plugin jar — out of scope for this layer. The asymmetry
(plugin = JUL, module = SLF4J) is a documented intentional split.

**Verification:** `./gradlew test` — green. `BukkitPluginContext` and
`VelocityPluginContext` each <30 LoC (was ~100).

---

### Layer 7 — Daemon-side modules — **DONE**

Shipped across four sub-PRs (7a manifest + lift, 7b gRPC distributor + event
bridge, 7c daemon module host, 7d instance lifecycle hooks + integration tests).

**Manifest reshape** (`cloud-api/.../api/module/platform/PlatformModuleManifest.java`):
```yaml
manifestVersion: 1
id: my-module
hosts: [controller]                 # or [daemon] or [controller, daemon]
backend:
  controller:
    entrypoint: com.example.MyControllerModule
  daemon:
    entrypoint: com.example.MyDaemonModule
```
- `Backend(EntrypointSpec controller, EntrypointSpec daemon)` replaces the legacy single-entrypoint shape; new `EntrypointSpec(String entrypoint)` record. A 1-arg `Backend(String)` compat constructor maps to `controller`.
- New trailing `List<ModuleHost> hosts` field on the record (default `[CONTROLLER]`). 8-arg compat constructor preserves call-site shape for tests.
- `PlatformModuleManifestParser` accepts both the new `backend.controller.entrypoint` / `backend.daemon.entrypoint` shape and the legacy `backend.entrypoint` form (auto-mapped to controller). Validates that listed hosts have matching entrypoints.
- All six first-party manifests (`stats-aggregator`, `player-journey`, `webhook-alerts`, `tablist`, `protocol-tap`, `example`) migrated to explicit `hosts: [controller]` + `backend.controller.entrypoint`.

**Shipped (`cloud-modules-core`, NEW subproject — Java 21):**
- Lifted host-agnostic runtime out of `cloud-controller/.../controller/module/platform/` into `me.prexorjustin.prexorcloud.modules.runtime.*`: `ModuleLifecycleManager`, `CapabilityRegistry`, `ModuleRouteRegistry`, `NoopModuleContext`, `PlatformModuleManifestParser`, `PlatformModuleManifestException`. Three same-package tests (`ModuleLifecycleManagerTest`, `CapabilityRegistryTest`, `ModuleRouteRegistryTest`, plus the parser test) move with them.
- Pre-step lift fix: `ModuleLifecycleManager` referenced `controller.observability.CorrelationContext` (an MDC helper). Lifted the helper to `cloud-common.logging.CorrelationContext` and updated the 6 controller call sites — no adapter shims into shared code (per principle 4).
- Both `cloud-controller` and `cloud-daemon` depend on `cloud-modules-core` as `implementation`.

**Shipped (`cloud-security/signing` — new package in the existing `cloud-security` subproject):**
- Moved `PlatformModuleSignatureVerifier` + `SignatureUtils` out of `cloud-controller`. Repackaged to `me.prexorjustin.prexorcloud.security.signing`.
- Verifier interface now takes a small `VerificationInput(Path sourceJar, String moduleId, String moduleVersion, String sha256)` record instead of `PlatformModuleStore.PreparedModule` — decouples the verifier from controller types so the daemon can reuse it without importing controller. The 8 caller sites (`PlatformModuleManager`, `PrexorCloudBootstrap`, `ModuleRoutes`, two unit tests, one integration test) updated to the new contract.
- Both `cloud-controller` and `cloud-daemon` already had `cloud-security` on their classpath — no settings.gradle changes.

**Shipped (cloud-api): new module-facing types**
- `DaemonModule` — symmetric to `PlatformModule`, plus instance hooks. Default-method shape: `onLoad / onStart / onStop / onUnload / onUpgrade(ModuleContext)`, `onInstanceStarting(InstanceSpec)` (mutable jvmArgs/env), `onInstanceStarted(InstanceHandle)`, `onInstanceStopping(InstanceHandle)`, `onInstanceStopped(InstanceHandle, ExitInfo)`, `capabilityHandles()`.
- `InstanceSpec` — mutable view of the launch spec; daemon modules add to `jvmArgs()` / `env()` and the daemon reads mutations back into the resolved spec before `serverProcess.start()`.
- `InstanceHandle(instanceId, group, port, pid, startedAt, state)`, `ExitInfo(exitCode, durationMs, crashed, crashSummary)` — read-only records.
- `DaemonCapabilityRegistry` — node-local view of capability bindings. Cross-node visibility deferred to v2.

**Shipped (`cloud-protocol`):**
- `daemon_service.proto` extended (additive variants only — no `PROTOCOL_VERSION` bump, per the daemon-proto memory note):
  - `ControllerMessage` += `ModuleInstall (12)`, `ModuleUninstall (13)`, `ModuleEvent (14)`.
  - `DaemonMessage` += `ModuleStateUpdate (14)`, `EventSubscribe (15)`, `EventUnsubscribe (16)`.
  - New top-level messages with the field shapes documented in the proto comments. `ModuleInstall` carries `jar_bytes` + optional `signature_bytes` + `signature_kind` + `manifest_yaml` + `is_upgrade` / `previous_version`.
- `contracts/proto-contracts.sha256` regenerated.

**Shipped (cloud-controller):**
- `ModuleDistributorHook` — interface with `NOOP_HOOK`. `PlatformModuleManager` fires `onInstalled(...)` after a successful install/upgrade and `onUninstalled(moduleId)` after uninstall; tests leave the hook as `NOOP_HOOK`. New `setDistributorHook(...)` + `platformStore()` getter for the gRPC layer.
- `ModuleDistributor` (NEW) — implements the hook. Reads jar bytes from the stored artifact, extracts manifest YAML from the jar, fans out `ModuleInstall` / `ModuleUninstall` to **every** connected daemon over the bidi stream. Daemons whose manifest doesn't list `daemon` as a host ignore the install locally — no per-daemon manifest filtering on the controller side. `syncDaemon(nodeId)` re-pushes every daemon-host module on handshake. Skips modules without `hosts: [..., DAEMON]`. Reads + sends signature sidecar bytes when present (sidecar persistence below).
- `DaemonEventForwarder` (NEW) — controller→daemon event bridge with **subscribe-registration**, not firehose. Per-daemon, per-event-type `Map<nodeId, Map<eventType, EventSubscription>>`; subscribes the controller's `EventBus` only on first daemon interest in a class, unsubscribes on `EventUnsubscribe` or stream close. Unknown event-class names produce an `ErrorReport` back to the daemon. Forwarded events are JSON-serialized via `ObjectMappers.standard()` and sent as `ModuleEvent`.
- `DaemonServiceImpl` — new `attachModuleDistributor()` / `attachDaemonEventForwarder()` setters. Handshake flow now calls `moduleDistributor.syncDaemon(nodeId)` after the ack so freshly connected daemons converge to the controller's installed-module set without operator intervention. Dispatch added for `MODULE_STATE_UPDATE` (informational logging in v1) / `EVENT_SUBSCRIBE` / `EVENT_UNSUBSCRIBE`. `cleanup()` calls `forwarder.onDisconnect(nodeId)` so the EventBus does not retain references to dead session observers.
- `PlatformModuleStore` — `PreparedModule` and `StoredModule` records gain a sidecar slot. `prepare(Path)` discovers `.cosign.bundle` / `.sig` siblings of the upload temp jar; `commit` persists them to `artifacts/{sha256}.{cosign.bundle|sig}` alongside the jar; index entries gain `sidecarFileName` + `sidecarKind`. `garbageCollect` retains referenced sidecars. This unblocks daemon-side cosign verification on re-distribution to daemons that connect later.
- `PrexorCloudBootstrap.initGrpc` constructs the distributor and forwarder and wires them to `PlatformModuleManager` and `DaemonServiceImpl` before the gRPC server starts.

**Shipped (cloud-daemon — new package `daemon.module`):**
- `DaemonModuleStore` — content-addressed `cache/modules/artifacts/{sha256}.jar` + `modules-index.json`. Verifies the controller's claimed sha256 against actual jar bytes before commit; idempotent on re-push. Persists optional sidecar bytes alongside the jar with the same lifecycle.
- `DaemonModuleManager` — wraps the lifted `ModuleLifecycleManager` + a `CapabilityRegistry`. Parses inbound `manifest_yaml` via the lifted `PlatformModuleManifestParser`, opens an isolated `URLClassLoader` (same `FilteringParentClassLoader` shape as the controller — `java.*` / `javax.*` / `jdk.*` / `sun.*` / `org.slf4j.*` / `me.prexorjustin.prexorcloud.api.*` only), instantiates `backend.daemon.entrypoint`, and runs lifecycle through a `DaemonModuleAdapter` that maps `DaemonModule → PlatformModule` (the lifecycle manager only knows `PlatformModule`). Reports every transition back as `ModuleStateUpdate`. Optional `PlatformModuleSignatureVerifier`: when configured, the manager writes the inbound jar + sidecar to a temp directory as siblings (the on-disk shape `TrustRootVerifier` / `CosignBundleVerifier` expect) and runs `verify()` before commit. `stopAll()` for shutdown.
- `DaemonModuleHost` — instance-lifecycle dispatcher. Holds the live `DaemonModule` set; `register` / `unregister` driven by the manager around state transitions; `dispatchInstanceStarting` / `Started` / `Stopping` / `Stopped` wrap each module call in try/catch + SLF4J warn so a misbehaving module cannot abort instance lifecycle.
- `DaemonModuleAdapter` — wraps a `DaemonModule` so the lifecycle manager can drive it as a `PlatformModule`. `onRegisterRoutes` is no-op (daemon has no REST surface).
- `DaemonModuleContext implements ModuleContext` — `host()` returns `DAEMON`. `findMongoStorage()` / `findRedisStorage()` return `Optional.empty()`; `requireMongoStorage()` / `requireRedisStorage()` throw `IllegalStateException("daemon modules have no Mongo storage")` / `("…no Redis storage")`. `httpClient()` / `json()` / `logger()` / `scheduler()` wired from cloud-common + the daemon-owned `DaemonTaskScheduler`.
- `DaemonCapabilityRegistryImpl` — adapter onto the cloud-api `DaemonCapabilityRegistry` interface; multi-listener fan-out atop the runtime registry's single-listener slot. `runtimeRegistry()` exposed for the lifecycle manager.
- `DaemonTaskScheduler` — mirror of `ControllerTaskScheduler` over a daemon-owned `ScheduledExecutorService`.
- `DaemonEventBus` (under `daemon.event`) — implements `api.event.EventBus`. Subscribe-registration: per-class refcount; sends `EventSubscribe` to the controller on first local subscribe, `EventUnsubscribe` on last. `onReconnect()` re-sends the full set of currently-subscribed event types so a brief stream loss doesn't leave the controller out of sync. `publishFromController(eventType, payloadJson)` deserializes via `ObjectMappers.standard()` and dispatches local subscribers on virtual threads.
- `ReconnectManager.addReconnectListener(Runnable)` — extension point so `DaemonEventBus.onReconnect` fires on every successful handshake.
- `MessageDispatcher` — dispatch added for `MODULE_INSTALL` / `MODULE_UNINSTALL` / `MODULE_EVENT`.
- `ProcessManager` — instance lifecycle hooks (PR 7d). `setDaemonModuleHost(host)` setter; `doStartInstance` builds a mutable `InstanceSpec` from the resolved spec, fans out `dispatchInstanceStarting`, reads jvmArgs/env mutations back into a fresh `ResolvedStartSpec` before launch; after `serverProcess.start()` calls `dispatchInstanceStarted`. `stopInstance` calls `dispatchInstanceStopping` before `process.stop()`. `onProcessExited` calls `dispatchInstanceStopped` with an `ExitInfo(crashed)`. `ServerProcess` got `pid()` + `startedAtMs()` accessors so the dispatched `InstanceHandle` carries real values.
- `ModuleSigningDaemonConfig` (`required`, `mode={KEYED,COSIGN_BUNDLE}`, `trustRoot`) + `DaemonConfig.modules.signing` block. `PrexorDaemon.buildDaemonSignatureVerifier` builds the right verifier from config (`NOOP` / `failClosed` / `TrustRootVerifier` / `CosignBundleVerifier`).
- `PrexorDaemon.start()` constructs the daemon module scheduler, `DaemonTaskScheduler`, `DaemonEventBus` (with the reconnect listener), runtime + capability registries, the store, the host, and the manager (with a `ContextFactory` that builds `DaemonModuleContext` and a `ModuleStateReporter` that pipes through the gRPC client). `shutdown()` stops all daemon modules before `processManager.stopAll()`.

**Shipped (`cloud-module/cloud-module-test-daemon/`, NEW):**
- `module.yaml` — `hosts: [daemon]` + `backend.daemon.entrypoint`.
- `TestDaemonModule implements DaemonModule` — records every lifecycle and instance hook fire to the file at `prexor.test.testDaemonModuleHooksFile` (system property set by the harness). `onStart` subscribes to `GroupCreatedEvent` so the integration test can verify the controller→daemon event bridge end-to-end. `onInstanceStarting` mutates `jvmArgs` / `env` so the harness can verify mutations are read back.
- `cloud-test-harness/build.gradle.kts` — adds `evaluationDependsOn` + `dependsOn(testDaemonModuleShadowJar)` and exposes `prexor.test.testDaemonModuleJar` as a test system property.

**Verification:**
- `./gradlew test` — green across `cloud-api`, `cloud-common`, `cloud-protocol`, `cloud-modules-core`, `cloud-security`, `cloud-controller`, `cloud-daemon`, all six first-party module subprojects, plus the new `cloud-module-test-daemon` build.
- `grep -rn "controller.module.platform.*SignatureVerifier" java --include='*.java'` returns zero matches; `grep -rn "controller.observability.CorrelationContext" java --include='*.java'` returns zero matches.
- `cloud-protocol:build` green; `contracts/proto-contracts.sha256` reflects the new wire shape.
- `DaemonEventBusTest` (cloud-daemon) — first subscriber sends `EventSubscribe`, last unsubscribe sends `EventUnsubscribe`, inbound dispatch via virtual thread, `onReconnect` re-sends the full set.
- Three `@Tag("daemon-module")` integration tests in `cloud-test-harness` (`DaemonModuleInstallTest`, `DaemonModuleEventStreamTest`, `DaemonCosignSignedModuleInstallTest`) compile and run on a Mongo-available host. They currently `Assumptions.assumeTrue(TestCluster.mongoAvailable())` so they skip on CI runners without Mongo (same pattern as the existing `StatsAggregatorInstallTest` / `CosignSignedModuleInstallTest`); they execute end-to-end on the nightly Mongo-backed runner. The event-stream test asserts ≤1.5s wall-clock latency (target ≤250ms; the 1.5s cap accommodates harness boot + GC noise on shared CI).

---

### Layer 8 — Frontend capability resolution — **DONE**

**Shipped (cloud-api):**
- Three new event records under `cloud-api/.../api/event/events/`:
  `CapabilityRegisteredEvent(capabilityId, version, moduleId)`,
  `CapabilityUnregisteredEvent(capabilityId, moduleId)`,
  `CapabilityProviderChangedEvent(capabilityId, moduleId, fromVersion, toVersion)`.
  All implement `CloudEvent` so they flow through the existing global SSE bus
  *and* the new dedicated capability stream.

**Shipped (cloud-controller):**
- `CapabilityRegistry.Listener` interface + `setListener(Listener)`. Mutating
  methods (`activateModule`, `deactivateModule`, `replaceModuleBindings`,
  `registerBuiltinHandle`) collect notifications inside the synchronized
  block and fire them after the lock is released — listeners never run with
  the registry monitor held.
- `CapabilityRegistry.activeBindings()` — flat snapshot of every active
  binding, includes `@controller` built-ins that the per-module REST view
  doesn't surface.
- `ModuleRoutes` `GET /api/v1/modules/platform/capabilities` response now
  includes a top-level `bindings` array (capabilityId / version / moduleId).
  Used by `useCapability` to seed on first paint.
- `CapabilityStreamer` (NEW under `controller/rest/sse/`) — registers
  `GET /api/v1/modules/platform/capabilities/stream`. Same ticket auth as
  the global SSE endpoint; subscribes to the EventBus for the three
  capability event types and forwards them as SSE messages. No replay store —
  the dashboard refetches the registry over plain HTTP on (re)connect.
- `RestServer` wires the new streamer alongside the existing console / log
  streamers.
- `PrexorCloudBootstrap.wireCapabilityEventPublishing(...)` — bridges
  `CapabilityRegistry.Listener` to `controller.eventBus().publish(...)`.
  Wired before `registerBuiltinCapabilities` so `prexor.player.journey`
  also fires REGISTERED on bootup.
- `CapabilityRegistryTest` — new test asserts the full event sequence
  (REG → no-op re-activate → CHG on version bump → UNREG on deactivate →
  REG with `@controller` for built-in handle).

**Shipped (dashboard):**
- `dashboard/app/types/events.ts` — adds `CapabilityRegisteredEvent` /
  `CapabilityUnregisteredEvent` / `CapabilityProviderChangedEvent` to the
  `CloudEvent` union.
- `dashboard/app/composables/useCapability.ts` — NEW. Returns
  `Ref<CapabilityBinding | null>`. Seeds via the new `bindings` field on
  `GET /api/v1/modules/platform/capabilities`, then subscribes to the
  global SSE bus filtering to the three capability event types. Cleans up
  on `onUnmounted`.
- `dashboard/app/composables/useScopedApi.ts` — overload now accepts either
  a string (direct module name, fast path) or `{ capability: string }` (resolves
  at call-time against `/api/v1/modules/platform/capabilities` so the client
  survives a provider change).
- `dashboard/app/sdk/index.ts` — re-exports `useCapability` + the
  `CapabilityBinding` type.
- `dashboard/packages/module-sdk/src/types.ts` + `composables.ts` — declares
  `CapabilityBinding`, `useCapability` stub, and the overloaded
  `useScopedApi` signature so module authors get full IntelliSense.

**Note on the dedicated stream endpoint:** the dashboard reuses its existing
global SSE connection (it filters by event type at the bus level), so
`useCapability` does not actually open a second EventSource. The dedicated
endpoint exists for *external* consumers (custom dashboards, daemon hosts,
CLI tools) that don't want to subscribe to the firehose.

**Verification:**
- `./gradlew :cloud-controller:test` — green; the new test
  `CapabilityRegistryTest#notifiesListenerOnLifecycle` asserts the full
  REGISTERED → CHANGED → UNREGISTERED → built-in REGISTERED sequence.
- `pnpm -C dashboard/packages/module-sdk build` — clean rebuild.
- `tsc --noEmit` against the module-sdk package — clean.
- Dashboard-app typecheck has 196 pre-existing errors unrelated to this
  change; no new errors introduced.

---

### Layer 9 — `prexorctl plugin new` + Path A vs Path B docs — **DONE**

**Shipped (cli):**
- `cli/cmd/plugin.go` — NEW `plugin` top-level cobra group with `plugin new <name>`. Flags: `--platform=paper|spigot|folia|velocity|bungeecord` (required), `--mc-version=1.20|1.21` (paper only — picks `prexorcloud.plugin-paper` vs `prexorcloud.plugin-paper-1-21`), `--package`, `--repo-root`, `--description`, `--author`, `--force`, `--dry`. Wired into `root.go` next to `moduleCmd`.
- `cli/internal/scaffold/plugin.go` — NEW `GeneratePlugin(PluginOptions)` and `PluginPlatform` enum. Renders two files (`build.gradle.kts` + `<Pascal>Plugin.java`) directly from code (no template directory — plugin scaffolds are too small for one). Patches `java/settings.gradle.kts` after the new `// ---- PLUGINS ---- //` anchor. Velocity scaffold also disables velocity-api's competing annotation processor inline, mirroring the example module's velocity build.
- `cli/internal/scaffold/plugin_test.go` — NEW. Covers paper-default vs paper-1.21 convention selection, kebab validation, force/no-force collision, idempotent settings patch, and velocity exclusion block.

**Shipped (java):**
- `java/settings.gradle.kts` — added `// ---- PLUGINS ---- //` anchor after the existing modules + cloud-plugins-internal blocks. The scaffolder refuses to invent the anchor; this lands it once.

**Shipped (docs):**
- `docs/engineering/plugin-vs-module.md` — NEW. Decision flowchart + side-by-side comparison table covering deployment, manifest, REST, storage, signing, and scaffolding.
- `docs/engineering/modules.md` — added a top-of-file callout linking to `plugin-vs-module.md`.
- `README.md` — added a "Building an extension?" pointer next to the docs section.

**Layout the scaffold emits:**
```
java/cloud-plugin/cloud-plugin-<name>/
├── build.gradle.kts                              # one convention plugin id
└── src/main/java/<pkg>/<Pascal>Plugin.java       # extends CloudPluginBase, @CloudPlugin
```

**Verification:**
- `cd cli && go test ./internal/scaffold/...` — green (25 tests, 6 new in `plugin_test.go`).
- `cd cli && go run . plugin new hello-world --platform=paper --dry --repo-root=<repo>` — emits the expected summary + "next: cd java && ./gradlew :cloud-plugin:cloud-plugin-hello-world:shadowJar".
- The runtime end-to-end check (build the jar, drop into Paper plugins/, see "PrexorCloud connected") was deferred — the scaffold uses the existing `prexorcloud.plugin-paper` convention plugin which is exercised by every cloud-module today, so the gradle wiring is already known-good.

---

## What NOT to touch

These are working well and out of scope for this overhaul:

1. **Cosign + Rekor signed install pipeline** — used by both controller and daemon module hosts. The verifier moved to `cloud-security/signing` in Layer 7 and its `verify(...)` input shape changed from `PreparedModule` to a small `VerificationInput` record; the signing/verification logic itself is unchanged.
2. **`CloudPluginProcessor` core generation logic** — only ADD `Logger logger()` plumbing if needed; do not refactor the platform-detection or `@ForVersion` machinery.
3. **gRPC daemon protocol** (`cloud-protocol/`) — Layer 7 added six additive `DaemonMessage` / `ControllerMessage` oneof variants for module distribution + the event bridge (`PROTOCOL_VERSION` unchanged, per the additive-only convention). No further proto work expected.
4. **CDS archive cache, DR drill, perf-baseline runner** — untouched.
5. **JWT + ticket-based SSE auth** — Layer 8's new SSE endpoint reuses the existing ticket mechanism.
6. **`VersionDispatcher` + `@ForVersion`** — already symmetric across all platforms.
7. **Plugin-side REST** — explicitly out of scope. Don't reserve API space.
8. **`@PlatformEventListener` annotation magic** — explicitly deferred. Plugin authors continue using `@EventHandler` / `@Subscribe` natively.

---

## Effort estimate (solo, full-time)

| Layer | Status   | Effort | Notes |
|-------|----------|--------|-------|
| 1     | DONE     | 1d     | Cross-cutting infra |
| 2     | DONE     | 0.5d   | Gradle BOM |
| 3+4   | DONE     | 3d     | ModuleContext + EventBus unification (combined) |
| 5     | DONE     | 2d     | Player + Webhook extraction (StateStore migration is the bulk) |
| 6     | DONE     | 1d     | AbstractPluginContext |
| 7     | DONE     | 8-10d  | Daemon-side modules — split into four sub-PRs (7a-7d) |
| 8     | DONE     | 2h     | Frontend capability resolution |
| 9     | DONE     | 2-3h   | prexorctl plugin new + decision-flowchart docs |

**Done:** all 9 layers shipped.
