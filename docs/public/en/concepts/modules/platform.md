---
title: Platform Modules
description: Controller-side modules — REST routes, MongoDB and Valkey storage, EventBus subscriptions, capability providers, frontend manifests.
---

A **platform module** is a module that loads in the controller JVM. It is
the model for cluster-wide functionality: a leaderboard service, a
Discord-notification bridge, a custom analytics endpoint, a webhook
fan-out. Anything that needs cluster state, REST endpoints, persistent
storage, or coordination across nodes is a platform module.

## What you'll learn

- The `PlatformModule` contract and the lifecycle hooks the controller
  calls.
- What `ModuleContext` exposes — events, logger, scheduler, HTTP client,
  JSON, storage, capabilities.
- How to register REST routes under `/api/v1/modules/<id>/<sub>`.
- How per-module storage works (MongoDB collections, Valkey keys).
- How frontend manifests ship dashboard pages.

## The contract

Every platform module implements `PlatformModule` from `cloud-api`:

```java
public interface PlatformModule {
    void onLoad(ModuleContext ctx);
    void onStart(ModuleContext ctx);
    void onStop(ModuleContext ctx);
    void onUnload(ModuleContext ctx);
    default void onUpgrade(ModuleContext ctx) {}
    default void onRegisterRoutes(RouteRegistrar reg) {}
    default Set<CapabilityBinding<?>> capabilities() { return Set.of(); }
}
```

The five lifecycle hooks correspond to the [module
lifecycle](/concepts/modules/lifecycle/) FSM:

| Hook | Called when | Used for |
|---|---|---|
| `onLoad` | After the manifest is parsed and the classloader is open | Construct fields, parse module config, register listeners that need only `ctx` |
| `onStart` | After all declared capability dependencies are bound | Subscribe to events, register capability providers, start scheduled work |
| `onStop` | Before deactivation | Stop scheduled work, drain queues |
| `onUnload` | Before classloader close | Final cleanup; release resources that don't follow GC |
| `onUpgrade` | When the module is replaced with a newer version | Migrate per-module storage if the schema changed |

`onRegisterRoutes` and `capabilities()` are not lifecycle methods — they
are read once at activation to wire routes and capability providers into
the controller's registries.

## ModuleContext

`ModuleContext` is the every-module-needs-this object. It is passed to
every lifecycle hook and to anything else the module wants to feed it
into.

```java
public interface ModuleContext {
    PlatformModuleManifest manifest();
    Path jarPath();
    Optional<String> previousVersion();          // present on upgrade
    EventBus events();                           // api.event.EventBus
    Logger logger();                             // SLF4J
    TaskScheduler scheduler();                   // module-scoped scheduled executor
    HttpClient httpClient();                     // shared, configured
    ObjectMapper json();                         // shared, configured
    ModuleHost host();                           // CONTROLLER on platform modules
    ClusterView cluster();                       // read-only ClusterState
    Optional<ModuleDataStore> findMongoStorage();
    Optional<ModuleRedisStorage> findRedisStorage();
    ModuleDataStore requireMongoStorage();
    ModuleRedisStorage requireRedisStorage();
    CapabilityRegistry capabilities();
}
```

The shared `HttpClient` and `ObjectMapper` come from `cloud-common`'s
`HttpClients.defaultClient()` and `ObjectMappers.standard()`. Don't
construct your own — module authors get HTTP/2, sane timeouts, and
ISO-8601 JSON for free.

## REST routes

Modules register routes mounted at
`/api/v1/modules/<moduleId>/<sub>`:

```java
@Override
public void onRegisterRoutes(RouteRegistrar reg) {
    reg.get("/players/top", this::handleTopPlayers);
    reg.post("/sessions/join", this::handleSessionJoin);
}
```

Routes are dispatched by the controller-side `ModuleRouteRegistry`. The
actual Javalin handler is one wildcard per HTTP method —
`RestServer` mounts `GET /api/v1/modules/{moduleId}/<sub>`, `POST`
likewise, etc., and the registry resolves `(moduleId, method, sub)` to
the registered handler.

When a module unloads, its routes drop atomically. There is no "old
route still served by ghost handler" possibility.

### Permission gating

Routes registered by modules are subject to the same RBAC as core
routes. The controller injects a `RequestContext` with the caller's
permissions and the module checks them via
`ctx.requirePermission(...)`. Built-in module-permission constants
(`MODULES_VIEW`, `MODULES_MANAGE`) cover most cases; modules can also
reuse domain permissions like `GROUPS_VIEW`.

```java
private void handleTopPlayers(RequestContext ctx) {
    ctx.requirePermission(Permissions.MODULES_VIEW);
    var top = leaderboard.top(50);
    ctx.json(top);
}
```

See [Security](/concepts/security/) for the full RBAC model.

## Per-module storage

Modules get two storage primitives, both isolated by module id.

### MongoDB-backed document storage

```java
ModuleDataStore data = ctx.requireMongoStorage();
data.insertOne("sessions",
    new Document("playerUuid", uuid).append("joinedAt", Instant.now()));
data.find("sessions", new Query().eq("playerUuid", uuid))
    .forEach(this::process);
```

Collections live under `mod_<moduleId>_<name>` so module ids never
collide. Soft size limits (per-module, configurable) prevent a runaway
module from consuming the whole MongoDB. The controller tracks usage in
`module_storage_metrics`.

Use this for any state that must survive a controller restart.

### Valkey-backed key/value storage

```java
ModuleRedisStorage redis = ctx.requireRedisStorage();
redis.set("leaderboard:top", json, Duration.ofMinutes(5));
String top = redis.get("leaderboard:top");
```

Keys live under `prexor:v1:platform:<moduleId>:`. The contract is "scope
your keys under your module id"; the controller does not enforce it (no
per-key validation), but the prefix is the convention every reference
module follows.

In `development` profile (no coordination store), modules that *request*
Valkey storage via `findRedisStorage()` get an empty `Optional`. Modules
that declare it as required in their manifest fail to activate in
development; build them against the MongoDB-backed store or run in
[`production`](/concepts/cluster-model/) profile.

## Event subscriptions

Modules subscribe to the SSE bus through the in-process `EventBus`:

```java
@Override
public void onStart(ModuleContext ctx) {
    ctx.events().subscribe(InstanceCrashedEvent.class, this::onCrash);
    ctx.events().subscribe(PlayerJoinEvent.class, this::onPlayerJoin);
}
```

Subscriptions are scoped to the module's lifecycle — they are removed
automatically on `onStop`. Modules do not implement their own teardown.

See [Events](/concepts/events/) for the full event taxonomy.

## Capabilities

Modules can both consume and provide capabilities. The full mechanics
are in [Capabilities](/concepts/modules/capabilities/); the short version:

```java
@Override
public Set<CapabilityBinding<?>> capabilities() {
    return Set.of(
        CapabilityBinding.of("stats.aggregator.leaderboard",
                             LeaderboardProvider.class,
                             this::resolveLeaderboard)
    );
}

@Override
public void onStart(ModuleContext ctx) {
    CapabilityHandle<PlayerJourneyTracker> journey =
        ctx.capabilities().resolve("prexor.player.journey",
                                   PlayerJourneyTracker.class);
    this.journey = journey;
}
```

The handle is dynamic. When the provider deactivates, `journey.get()`
returns `null`. When a different provider rebinds, the same handle now
points at the new instance. Consumers do not refetch.

## Workload extensions

Modules can ship Minecraft-server-side code via workload extensions.
The cloud-api defines the manifest shape; the controller's
`ExtensionRegistry` resolves which extension applies to which group
based on platform / version / variant matchers.

```yaml
# module.yaml
extensions:
  - id: stats-aggregator-paper
    targets:
      platform: paper
      versions: ["1.20", "1.21"]
    artifact: extensions/stats-aggregator-paper.jar
```

The decision is hashed into the [composition
plan](/concepts/groups-instances-templates/), so the daemon installs
exactly the right jar — and a hash mismatch (e.g. operator forgot to
upgrade the module on one host) is detected at start time.

## Frontend manifest

Modules can ship dashboard pages. The frontend manifest
(`META-INF/frontend/manifest.json` inside the jar) declares:

- The route path under the dashboard (e.g. `/modules/stats-aggregator`).
- The entry-point asset (a single ESM bundle).
- The capabilities the page consumes (so the dashboard hides pages
  whose capabilities are unavailable).
- The required permissions (so RBAC hides pages from viewers without
  access).

`ModuleFrontendManager` extracts the manifest and asset to the
controller's frontend cache. The dashboard renders the page through
`@prexorcloud/module-sdk`. When the module unloads, the cache directory
is deleted and the dashboard drops the page.

See the [module SDK
reference](/reference/module-sdk/platform-module/) for the full TS API
the dashboard side compiles against.

## A minimal module

```java
public final class HelloModule implements PlatformModule {
    private CapabilityHandle<PlayerJourneyTracker> journey;

    @Override
    public void onLoad(ModuleContext ctx) {
        ctx.logger().info("hello-module loaded, version={}",
                          ctx.manifest().version());
    }

    @Override
    public void onStart(ModuleContext ctx) {
        journey = ctx.capabilities().resolve("prexor.player.journey",
                                             PlayerJourneyTracker.class);
        ctx.events().subscribe(PlayerJoinEvent.class, this::onJoin);
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar reg) {
        reg.get("/recent", ctx -> {
            ctx.requirePermission(Permissions.MODULES_VIEW);
            UUID uuid = UUID.fromString(ctx.queryParam("player"));
            var events = journey.get().getJourney(uuid, 10);
            ctx.json(events);
        });
    }

    private void onJoin(PlayerJoinEvent e) {
        // ...
    }

    @Override public void onStop(ModuleContext ctx) {}
    @Override public void onUnload(ModuleContext ctx) {}
}
```

The accompanying `module.yaml`:

```yaml
manifestVersion: 1
id: hello-module
version: 0.1.0
hosts: [controller]
backend:
  controller:
    entrypoint: com.example.HelloModule
dependencies:
  capabilities:
    - id: prexor.player.journey
      required: true
permissions:
  required: [modules.view]
```

## Where to look in the code

| What | Where |
|---|---|
| Public API every module compiles against | `java/cloud-api/` |
| Lifecycle FSM, classloader tracker | `java/cloud-modules:runtime/.../runtime/` |
| Module REST dispatcher | `controller/module/ModuleRouteRegistry`, `controller/rest/RestServer` |
| Capability registry plus dynamic handles | `controller/capability/CapabilityRegistry` |
| Reference module | `java/cloud-modules/stats-aggregator/` |

## Next up

- [Daemon Modules](/concepts/modules/daemon/) — the host-side contract.
- [Capabilities](/concepts/modules/capabilities/) — providers, consumers,
  dynamic handles.
- [Lifecycle](/concepts/modules/lifecycle/) — the FSM and the classloader
  rules.
- [Module SDK reference](/reference/module-sdk/platform-module/) — the
  full Java + TS surface.
- [module.yaml reference](/reference/module-sdk/module-yaml/) — every manifest
  field.
