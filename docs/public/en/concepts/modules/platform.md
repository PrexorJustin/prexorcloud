---
title: Platform Modules
description: Controller-side modules — entrypoint, lifecycle FSM, REST route registration, capabilities, MongoDB and Redis storage, and frontend manifests.
---

A platform module loads in the Controller JVM and adds cluster-wide functionality: a leaderboard service, a Discord bridge, a custom analytics endpoint, a webhook fan-out. Anything that needs cluster state, REST endpoints, persistent storage, or capability wiring across modules is a platform module.

The same `PlatformModule` contract can also run in a Daemon process. This page covers the controller host (`hosts: [controller]`). The Daemon host is documented separately in [Daemon Modules](/concepts/modules/daemon/).

## What you'll learn

- The `PlatformModule` entrypoint contract and every hook the Controller calls.
- The lifecycle FSM (`INSTALLED → WAITING → ACTIVE → …`) and which hook fires on each transition.
- How REST routes register under `/api/v1/modules/<moduleId>/` and how dispatch works.
- How capabilities are provided and consumed.
- Per-module MongoDB and Redis storage, including namespaces and quotas.
- How frontend manifests ship Dashboard pages.

## The entrypoint contract

Every platform module implements `PlatformModule` from `cloud-api` (`me.prexorjustin.prexorcloud.api.module.platform.PlatformModule`). Every method is `default`, so a module overrides only what it uses.

```java
public interface PlatformModule {
    default void onLoad(ModuleContext context) throws Exception {}
    default void onRegisterRoutes(RouteRegistrar registrar) {}
    default void onStart(ModuleContext context) throws Exception {}
    default void onStop(ModuleContext context) throws Exception {}
    default void onUnload(ModuleContext context) throws Exception {}
    default void onUpgrade(ModuleContext context) throws Exception {}
    default void onReload(ModuleContext context) throws Exception {}
    default List<CapabilityHandle<?>> capabilityHandles() { return List.of(); }
    default ModuleHealth healthCheck() { return ModuleHealth.unknown(); }
}
```

The Controller resolves the class named in the manifest's `backend.controller.entrypoint`, instantiates it once, and drives it through the lifecycle FSM below.

### Lifecycle hooks

| Hook | Called when | Use it for |
|---|---|---|
| `onLoad` | Right after install/upgrade, before routes register | Construct fields, parse module config, prepare storage indexes |
| `onRegisterRoutes` | Once after `onLoad`, before `onStart` | Register module REST routes (see [REST routes](#rest-routes)) |
| `onStart` | When the module enters `ACTIVE` (all required capabilities resolved) | Subscribe to events, register scheduled work, expose capability handles |
| `onStop` | Before leaving `ACTIVE` (uninstall, upgrade, or a required capability went away) | Drain queues, stop scheduled work |
| `onUnload` | Before the classloader closes (uninstall or upgrade) | Final cleanup of resources GC won't reclaim |
| `onUpgrade` | Once on the new entrypoint when a newer jar replaces this module | Migrate per-module storage if the schema changed |
| `onReload` | Once on the new entrypoint on the fast `ACTIVE → RELOADING → ACTIVE` path | Hand off live state — the old instance is never stopped or unloaded |

`onRegisterRoutes`, `capabilityHandles()`, and `healthCheck()` are not lifecycle transitions. Routes are read once after `onLoad`; capability handles are read each time the module activates; `healthCheck()` is polled on a fixed cadence while the module is `ACTIVE`.

Hooks may throw checked exceptions. A throw moves the module to `FAILED` (see the FSM below), and the lifecycle clears any routes it had registered.

## Lifecycle FSM

The Controller-side state machine lives in `ModuleLifecycleManager` (`cloud-modules:runtime`). States:

| State | Meaning |
|---|---|
| `INSTALLED` | Jar loaded, `onLoad` + `onRegisterRoutes` ran, not yet started |
| `WAITING` | Installed but one or more required capabilities are unbound |
| `ACTIVE` | Started and serving — routes live, capabilities exported |
| `RELOADING` | Transient state during the hot-reload fast path |
| `STOPPING` | Transient state while `onStop` runs |
| `UNLOADED` | Uninstalled; classloader released |
| `FAILED` | A lifecycle hook threw; routes cleared, no rollback |

### Transitions

Install (`install`):

1. Resolve storage, store the module as `INSTALLED`.
2. Call `onLoad`, then clear and re-register routes via `onRegisterRoutes`.
3. Reconcile: if all required capabilities are satisfied, call `onStart` and move to `ACTIVE`; otherwise move to `WAITING`.

Reconcile (`reconcile`, run after any capability change):

- `INSTALLED` or `WAITING` and requirements satisfied → `onStart` → `ACTIVE`.
- `INSTALLED` or `WAITING` and requirements unsatisfied → `WAITING`.
- `ACTIVE` and a requirement disappeared → `STOPPING` → `onStop` → `WAITING`.

Upgrade (`upgrade`, replace with a newer jar):

1. If `ACTIVE`: `STOPPING` → `onStop` on the old entrypoint.
2. `onUnload` on the old entrypoint.
3. Clear old routes, store the replacement as `INSTALLED`.
4. `onLoad` → `onUpgrade` → `onRegisterRoutes` on the new entrypoint, with `ModuleContext.previousVersion()` set to the old version.
5. Reconcile as in install.

Reload (`reload`, hot path — see ADR 28):

1. Gated on `reloadCompatible`: the new manifest's controller entrypoint must declare `reloadable: true` **and** its capability declaration (`provides` + `requires`) must be byte-identical to the running version. Any capability-shape change forces the full upgrade path.
2. `ACTIVE → RELOADING`, clear routes, then `onReload` + `onRegisterRoutes` on the new entrypoint → `ACTIVE`. The outgoing module receives neither `onStop` nor `onUnload`, so `onReload` must re-arm scheduler tasks and rebuild caches itself. A module that does not implement `onReload` must not set `reloadable: true`.

Uninstall (`uninstall`):

- If `ACTIVE`: `STOPPING` → `onStop`.
- `onUnload`, clear routes → `UNLOADED`.

Any hook that throws during these transitions moves the module to `FAILED`. The reload path has no rollback; install and upgrade restore the previous binding on failure where one exists.

## ModuleContext

`ModuleContext` is passed to every lifecycle hook. On the controller host it is implemented by `ControllerModuleContext`.

```java
public interface ModuleContext {
    PlatformModuleManifest manifest();
    Path jarPath();
    String previousVersion();          // "" on fresh install, prior version on upgrade
    default boolean isUpgrade();        // previousVersion non-blank
    ModuleHost host();                  // CONTROLLER on the controller host

    <T> Optional<T> findCapability(String capabilityId, Class<T> type);
    <T> T requireCapability(String capabilityId, Class<T> type);

    Optional<ModuleDataStore> findMongoStorage();
    ModuleDataStore requireMongoStorage();
    Optional<PlatformRedisStorage> findRedisStorage();
    PlatformRedisStorage requireRedisStorage();

    EventBus events();
    Logger logger();                    // pre-namespaced "module:<id>"
    TaskScheduler scheduler();          // tasks cancelled on module stop
    HttpClient httpClient();            // shared pool
    ObjectMapper json();                // standard config
}
```

Notes that match the implementation:

- `logger()` returns an SLF4J logger named `module:<id>` so module lines are attributable in mixed log streams.
- `httpClient()` returns the shared `HttpClients.defaultClient()`; `json()` returns `ObjectMappers.standard()` (java-time module, ISO-8601 timestamps, `NON_NULL` serialization, lenient on unknown properties). Don't construct your own.
- `scheduler()` is owned by the host. Tasks you schedule are cancelled automatically when the module stops.
- `previousVersion()` is `""` on a fresh install, never `null`.

## REST routes

Modules register routes in `onRegisterRoutes`. The Controller mounts each module's routes under `/api/v1/modules/<moduleId>/`.

```java
@Override
public void onRegisterRoutes(RouteRegistrar reg) {
    reg.get("/players/top", (req, res) -> {
        var top = leaderboard.top(50);
        res.json(top);
    });
    reg.post("/sessions", CreateSession.class, (req, body, res) -> {
        sessions.create(body);
        res.status(201).json(Map.of("ok", true));
    });
}
```

`RouteRegistrar` exposes `get`, `post`, `put`, `delete`, `patch`. Each takes a path template and a `RouteHandler`:

```java
@FunctionalInterface
public interface RouteHandler {
    void handle(ApiRequest request, ApiResponse response) throws Exception;
}
```

There are also typed overloads — `post(path, bodyType, handler)` (and `put`/`patch`/`delete`) — that parse the JSON body into `bodyType` via `ApiRequest.bodyAs`. A parse failure short-circuits with a `400` JSON envelope `{"error":"invalid json body", "details": "..."}` and the handler never runs.

### Path templates and dispatch

- A path template uses `{param}` placeholders, for example `/players/{uuid}`. A leading slash is optional; the registry normalizes it.
- Templates must not contain `?` or `#`.
- The Controller mounts one wildcard handler per HTTP method at `/api/v1/modules/{moduleId}/<sub>` (`RestServer.registerModuleApiDispatcher`). On each request it resolves `(moduleId, method, subpath)` against `ModuleRouteRegistry`, which walks the module's recorded templates and returns the first segment-count match.
- The module id segment `platform` is reserved (it serves the controller's own platform-module management API). A request to `/api/v1/modules/platform/...` never reaches a module handler and returns `404` if it isn't a known platform route.
- Routes follow the module's lifecycle. They are cleared on uninstall, upgrade, and reload, then re-registered, so a module never leaves a stale handler pointing into a closed classloader. Javalin's route table is never mutated at runtime.

`ApiRequest` exposes `method()`, `path()`, `pathParams()` / `pathParam(name)`, `queryParams()` / `queryParam(name)`, `headers()` / `header(name)`, `body()`, and `bodyAs(type)`. `ApiResponse` exposes `status(code)`, `json(body)`, `text(body)`, and `header(name, value)`.

### Auth, rate limiting, and permissions

Module routes sit behind the controller's standard `/api/v1/*` filter chain: CORS, subnet guard, request-id, IP rate limit, JWT authentication, and per-user rate limit. An unauthenticated request never reaches a module handler.

The module REST surface does **not** carry an RBAC helper. `ApiRequest` exposes the authenticated user id through `userId()` (the `X-User-Id` header injected by the auth middleware); a module that needs finer permission gating reads it and decides for itself. Permission constants like `MODULES_VIEW` / `MODULES_MANAGE` gate the controller's own module-management routes, not module-registered routes.

### Error mapping

The dispatcher maps exceptions thrown by a handler to standard JSON envelopes:

| Thrown | HTTP | Code |
|---|---|---|
| `IllegalArgumentException` | 422 | `VALIDATION_ERROR` |
| `NotFoundException` | 404 | `NOT_FOUND` |
| any other exception | 500 | `INTERNAL_ERROR` |

A typed-body parse failure returns `400` before the handler runs.

## Capabilities

Capabilities let one module expose a typed service that another module resolves at runtime, decoupled from classloaders.

### Providing a capability

Declare it in the manifest (`capabilities.provides`) and return a `CapabilityHandle` from `capabilityHandles()`:

```java
@Override
public List<CapabilityHandle<?>> capabilityHandles() {
    return List.of(
        CapabilityHandle.of("stats-aggregator-leaderboard",
                            LeaderboardService.class,
                            this.leaderboard));
}
```

`CapabilityHandle.of(id, type, value)` enforces `type.isInstance(value)` at construction, so a provider cannot expose a handle no consumer can cast. The id must match a `provides` entry in the manifest; the type must be a public interface or class consumers can resolve against. The Controller reads `capabilityHandles()` each time the module activates and registers them with the capability registry.

### Consuming a capability

Declare it in the manifest (`capabilities.requires`) and resolve it through the context:

```java
@Override
public void onStart(ModuleContext ctx) {
    PlayerJourneyTracker journey =
        ctx.requireCapability("prexor.player.journey", PlayerJourneyTracker.class);
    // ...
}
```

- `requireCapability(id, type)` throws `IllegalStateException` if the capability is unbound. Use it only for capabilities the module cannot run without.
- `findCapability(id, type)` returns `Optional.empty()` when the capability is currently unbound.

A required capability that is unbound holds the module in `WAITING` — `onStart` is not called until every `requires` entry resolves. When a provider deactivates, consumers are reconciled back to `WAITING`. See [Capabilities](/concepts/modules/capabilities/) for the full model.

## Per-module storage

A module requests storage in its manifest (`storage.mongo`, `storage.redis`). The Controller allocates an isolated namespace per module. If a module requests storage that the cluster doesn't provide, activation fails with a clear error.

### MongoDB document storage

```java
ModuleDataStore data = ctx.requireMongoStorage();
data.ensureCollection("sessions");
data.insertOne("sessions", new SessionDoc(uuid, Instant.now()));
List<SessionDoc> recent =
    data.find("sessions", Query.eq("playerUuid", uuid), Sort.desc("joinedAt"), 10, SessionDoc.class);
```

`ModuleDataStore` is a document store scoped to the module. Documents are Jackson-serialized — you pass your own records. Operations include `insertOne`/`insertMany`, `findOne`, `find` (with sort, limit, optional skip), `count`, `updateOne`/`updateMany`, `upsertOne`, `deleteOne`/`deleteMany`, `createIndex`, and `withTransaction` for multi-document transactions.

Collections are physically named `platform_<sanitizedModuleId>_<name>`, so module ids never collide. The module id is lowercased and any character outside `[a-z0-9_]` becomes `_`. `collectionPrefix()` returns this prefix.

If the manifest sets `storage.limits.mongoDocuments`, writes are wrapped in a soft-quota enforcer: `insertOne`, `insertMany`, and inserting `upsertOne` count documents across the module's collections first and throw `StorageQuotaExceededException` when the write would exceed the limit. Reads, updates, and deletes are unaffected.

### Redis (Valkey) key/value storage

```java
PlatformRedisStorage redis = ctx.requireRedisStorage();
redis.set("leaderboard:top", json, Duration.ofMinutes(5));
Optional<String> top = redis.get("leaderboard:top");
long views = redis.increment("views");
```

`PlatformRedisStorage` exposes `get`, `set`, `set` with TTL, `increment`, `decrement`, and `delete`. Keys are automatically prefixed with `prexor:v1:platform:<sanitizedModuleId>:` — you pass the short key, the store qualifies it. `keyPrefix()` returns the prefix.

If `storage.limits.redisKeys` is set, `set`/`increment`/`decrement` on a key that does not already exist count existing keys under the prefix and throw `StorageQuotaExceededException` when adding one would exceed the limit.

Redis storage requires the coordination store. In a single-node deployment without coordination enabled, `findRedisStorage()` returns empty and a module that declares `storage.redis: true` fails to activate (the resolver throws because Redis is not configured). Build against MongoDB storage, or run with coordination.

### Uninstall cleanup

On uninstall the Controller drops every `platform_<id>_*` collection and deletes every `prexor:v1:platform:<id>:*` key for the module, so removing a module reclaims its storage.

## Events

Subscribe to the cluster-wide event bus from `onStart`:

```java
@Override
public void onStart(ModuleContext ctx) {
    ctx.events().subscribe(PlayerJoinEvent.class, this::onPlayerJoin);
    ctx.events().subscribe(InstanceCrashedEvent.class, this::onCrash);
}
```

The `EventBus` is the same contract plugins consume on the workload side. See [Events](/concepts/events/) for the taxonomy.

## Health checks

Override `healthCheck()` for a liveness probe:

```java
@Override
public ModuleHealth healthCheck() {
    return lastFlushOk ? ModuleHealth.healthy() : ModuleHealth.unhealthy("flush backlog");
}
```

The Controller polls `healthCheck()` on every `ACTIVE` module on a fixed cadence, outside the lifecycle lock, and surfaces the latest result over REST at `GET /api/v1/modules/platform/{moduleId}/health` (requires `MODULES_VIEW`) and as the `prexorcloud.module.health` metric. Implementations must be cheap and non-blocking — read a cached flag or a last-success timestamp, never a live round-trip. A check that throws is recorded as `UNHEALTHY`. The default returns `UNKNOWN`, so a module that doesn't opt in is never a false `HEALTHY`.

## Frontend manifest

A module ships Dashboard pages by including `META-INF/frontend/module-frontend.json` in its jar plus the asset files under `META-INF/frontend/`.

```json
{
  "version": 1,
  "displayName": "Stats",
  "entry": "index.js",
  "icon": "bar-chart-3",
  "permissions": [],
  "routes": [
    {
      "path": "/modules/stats-aggregator",
      "component": "StatsLeaderboardPage",
      "title": "Stats",
      "icon": "bar-chart-3",
      "nav": true,
      "navGroup": "Modules",
      "navGroupOrder": 100,
      "adminOnly": false
    }
  ],
  "events": []
}
```

`FrontendManifest` fields: `version` (≥ 1), `displayName` (required), `entry` (required ESM entry asset), `css`, `icon`, `permissions`, `routes`, `events`. Each `FrontendRoute` requires `path`, `component`, and `title`, and optionally carries `icon`, `nav`, `navGroup`, `navGroupOrder`, and `adminOnly`.

`ModuleFrontendManager` extracts the manifest and assets to `modules/data/<name>/_frontend/` on the Controller. Only known asset extensions are extracted (`.js`, `.mjs`, `.css`, `.json`, `.svg`, `.png`, `.jpg`, `.woff`, `.woff2`, `.map`); path-traversal entries are rejected. Assets are served at `/api/v1/modules/{name}/frontend/{filepath}` (requires `MODULES_VIEW`). On uninstall the extracted directory is deleted and the page disappears. An operator can also push a replacement bundle (a zip with the same `META-INF/frontend/` layout) to `POST /api/v1/modules/platform/{moduleId}/frontend/reload` (requires `MODULES_MANAGE`).

## Workload extensions

A module can ship Minecraft-server-side code through workload extensions, declared in the manifest's `extensions` list (`WorkloadExtensionManifest`: `id`, `target`, `activationPolicy`, `conflicts`, `variants`). The Controller's `ExtensionRegistry` resolves which extension applies to which Group based on platform/version/variant matchers, and the decision is folded into the Group's composition plan so the Daemon installs exactly the right artifact. This is covered in [Groups, Instances, and Templates](/concepts/groups-instances-templates/).

## The manifest (`module.yaml`)

A platform module is described by `module.yaml`, parsed into `PlatformModuleManifest`. Current schema is `manifestVersion: 2`; version `1` is still accepted.

```yaml
manifestVersion: 1
id: stats-aggregator
version: 1.0.0
hosts: [controller]
backend:
  controller:
    entrypoint: me.prexorjustin.prexorcloud.modules.stats.platform.StatsAggregatorModule
    # reloadable: true        # manifestVersion 2+, opts into the hot-reload path
frontend:
  sdkVersion: 1
  entry: index.js
storage:
  mongo: true
  limits:
    mongoDocuments: 500000
capabilities:
  requires:
    - id: prexor.player.journey
      versionRange: ">=1.0.0 <2.0.0"
  provides:
    - id: stats-aggregator-leaderboard
      version: 1.0.0
```

| Key | Meaning |
|---|---|
| `manifestVersion` | Schema version (1 or 2) |
| `id` | Module id; used for routes, storage namespaces, capability ownership |
| `version` | Module version (semver) |
| `hosts` | `[controller]`, `[daemon]`, or both. Omitted defaults to `[controller]` |
| `backend.controller.entrypoint` | Fully-qualified `PlatformModule` class for the controller host |
| `backend.controller.reloadable` | `manifestVersion: 2+`, default `false`; opts into hot reload |
| `frontend.sdkVersion` / `frontend.entry` | Dashboard SDK version and entry asset |
| `storage.mongo` / `storage.redis` | Request per-module MongoDB / Redis namespaces |
| `storage.limits.mongoDocuments` / `redisKeys` | Soft write quotas; a limit requires the matching storage |
| `capabilities.provides[]` | `id`, `version`, and (v2) `deprecatedSince`, `removedIn` |
| `capabilities.requires[]` | `id` and `versionRange` that must resolve before `ACTIVE` |
| `extensions[]` | Workload extensions (see above) |

For a host listed in `hosts`, the matching `backend` field must be non-null.

## A minimal module

```java
public final class HelloModule implements PlatformModule {
    private LeaderboardService leaderboard;

    @Override
    public void onLoad(ModuleContext ctx) {
        ctx.logger().info("hello-module loaded, version={}", ctx.manifest().version());
        this.leaderboard = new LeaderboardService(ctx.requireMongoStorage());
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar reg) {
        reg.get("/top", (req, res) -> {
            int limit = req.queryParam("limit").map(Integer::parseInt).orElse(10);
            res.json(leaderboard.top(limit));
        });
    }

    @Override
    public void onStart(ModuleContext ctx) {
        ctx.events().subscribe(PlayerJoinEvent.class, this::onJoin);
    }

    @Override
    public List<CapabilityHandle<?>> capabilityHandles() {
        return List.of(CapabilityHandle.of("hello-leaderboard",
                                            LeaderboardService.class, leaderboard));
    }

    @Override
    public ModuleHealth healthCheck() {
        return leaderboard.isReady() ? ModuleHealth.healthy() : ModuleHealth.unhealthy("not ready");
    }

    private void onJoin(PlayerJoinEvent e) { /* ... */ }
}
```

```yaml
manifestVersion: 1
id: hello-module
version: 0.1.0
hosts: [controller]
backend:
  controller:
    entrypoint: com.example.HelloModule
storage:
  mongo: true
capabilities:
  provides:
    - id: hello-leaderboard
      version: 0.1.0
```

This module installs to `INSTALLED`, runs `onLoad` + `onRegisterRoutes`, then activates straight to `ACTIVE` (it requires no capabilities). Its route serves at `GET /api/v1/modules/hello-module/top`, and its leaderboard is available to other modules as the `hello-leaderboard` capability.

## Where to look in the code

| What | Where |
|---|---|
| Public API every module compiles against | `java/cloud-api/src/main/java/me/prexorjustin/prexorcloud/api/module/` |
| Entrypoint contract | `cloud-api .../module/platform/PlatformModule.java` |
| Module context | `cloud-api .../module/platform/ModuleContext.java`; controller impl `cloud-controller .../module/platform/ControllerModuleContext.java` |
| Manifest record | `cloud-api .../module/platform/PlatformModuleManifest.java` |
| Lifecycle FSM | `cloud-modules:runtime .../modules/runtime/ModuleLifecycleManager.java` |
| Route registry | `cloud-modules:runtime .../modules/runtime/ModuleRouteRegistry.java`; dispatcher `cloud-controller .../rest/RestServer.java` |
| Storage allocation + quotas | `cloud-controller .../module/platform/PlatformModuleStorageManager.java` |
| Frontend extraction | `cloud-controller .../module/ModuleFrontendManager.java` |
| Reference module | `java/cloud-modules/stats-aggregator/` |

## Next up

- [Lifecycle](/concepts/modules/lifecycle/) — the FSM and classloader rules in depth.
- [Capabilities](/concepts/modules/capabilities/) — providers, consumers, version ranges.
- [Daemon Modules](/concepts/modules/daemon/) — the same contract on the host side.
