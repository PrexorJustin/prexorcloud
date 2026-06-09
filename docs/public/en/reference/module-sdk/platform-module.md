---
title: PlatformModule
description: Controller-side module entrypoint — every lifecycle hook, REST route registration, capability export, and a runnable hello-world, verified against the cloud-api contract and the controller lifecycle state machine.
---

`PlatformModule` is the contract every controller-side module implements. The
host drives it through lifecycle hooks (`onLoad`, `onRegisterRoutes`, `onStart`,
`onStop`, `onUnload`, plus `onUpgrade` and `onReload` for version replacement),
pulls REST routes from `onRegisterRoutes`, pulls exported capability handles from
`capabilityHandles()`, and polls liveness through `healthCheck()`. Every method
has a default implementation — override only what you need.

> If you are coming from a plugin-style SDK, `onStart` / `onStop` are this
> contract's "enable" / "disable": `onStart` runs when the module becomes
> `ACTIVE`, `onStop` runs when it leaves the active set. There are no
> `onEnable` / `onDisable` methods.

The interface lives at
`me.prexorjustin.prexorcloud.api.module.platform.PlatformModule` in the
`cloud-api` artifact. Every hook receives a
[`ModuleContext`](/reference/module-sdk/module-context/) (except
`onRegisterRoutes`, which receives a `RouteRegistrar`).

## What you'll learn

- The exact signature, call site, and failure behavior of each hook.
- The lifecycle state machine the controller runs (`INSTALLED → WAITING →
  ACTIVE → STOPPING → UNLOADED`, plus `RELOADING` and `FAILED`) and where each
  hook fires.
- How `onRegisterRoutes` mounts routes on the controller's HTTP API.
- How `capabilityHandles()` plugs the module into the dependency graph.
- A complete, runnable hello-world: `module.yaml` manifest plus entrypoint.

## Interface

```java
public interface PlatformModule {

    default void onLoad(ModuleContext context) throws Exception {}

    default void onRegisterRoutes(RouteRegistrar registrar) {}

    default void onStart(ModuleContext context) throws Exception {}

    default void onStop(ModuleContext context) throws Exception {}

    default void onUnload(ModuleContext context) throws Exception {}

    default void onUpgrade(ModuleContext context) throws Exception {}

    default void onReload(ModuleContext context) throws Exception {}

    default List<CapabilityHandle<?>> capabilityHandles() {
        return List.of();
    }

    default ModuleHealth healthCheck() {
        return ModuleHealth.unknown();
    }
}
```

Implementation note: the controller-side lifecycle is driven by
`ModuleLifecycleManager` (in the `cloud-modules:runtime` artifact); the
controller process wraps it in `PlatformModuleManager`. The signatures and
call order below are taken from that state machine.

## Lifecycle hooks

### `onLoad`

```java
default void onLoad(ModuleContext context) throws Exception
```

Called once, first, when the host installs the module jar (state
`INSTALLED`). This is the composition root: resolve required capabilities
(`context.requireCapability(...)` / `context.findCapability(...)`), open storage
(`context.requireMongoStorage()`), and construct your collaborators. Do not start
background work here — the module is not yet `ACTIVE` and its declared
capabilities are not yet bound for consumers.

| Parameter | Type | Notes |
|---|---|---|
| `context` | `ModuleContext` | `previousVersion()` is `""` on a fresh install. |

Throwing transitions the module to `FAILED`; `onRegisterRoutes` and `onStart`
are skipped and any routes already recorded for this module are cleared.

```java
@Override
public void onLoad(ModuleContext context) {
    this.repository = new PlaytimeRepository(context.requireMongoStorage());
    this.queryService = new PlaytimeQueryServiceImpl(repository);
}
```

### `onRegisterRoutes`

```java
default void onRegisterRoutes(RouteRegistrar registrar)
```

Register module-owned REST routes. Called once after `onLoad` and before
`onStart`. Routes are mounted under `/api/v1/modules/{moduleId}/<subpath>`,
share the controller's auth and rate-limit middleware, and are dropped
automatically on uninstall, upgrade, and reload. Do not stash the `registrar` —
it records the routes synchronously during this call; a long-lived reference is
useless because re-registration always happens through this hook.

`RouteRegistrar` records one route per call:

```java
void get(String path,    RouteHandler handler);
void post(String path,   RouteHandler handler);
void put(String path,    RouteHandler handler);
void delete(String path, RouteHandler handler);
void patch(String path,  RouteHandler handler);

// Typed body variants (parse the JSON body to bodyType before dispatch;
// a parse failure short-circuits with a 400 envelope, handler not called):
<T> void post(String path,   Class<T> bodyType, TypedRouteHandler<T> handler);
<T> void put(String path,    Class<T> bodyType, TypedRouteHandler<T> handler);
<T> void patch(String path,  Class<T> bodyType, TypedRouteHandler<T> handler);
<T> void delete(String path, Class<T> bodyType, TypedRouteHandler<T> handler);
```

`path` is the in-module subpath; `{name}` segments are path parameters
(`req.pathParam("name")`). A leading `/` is optional (it is normalized in). A
template containing `?` or `#`, or a blank template, throws
`IllegalArgumentException`. See [REST routes](/reference/module-sdk/rest-routes/)
for `RouteHandler`, `ApiRequest`, and `ApiResponse`.

```java
@Override
public void onRegisterRoutes(RouteRegistrar registrar) {
    registrar.get("/players/{uuid}", (req, res) -> {
        UUID uuid = UUID.fromString(req.pathParam("uuid"));
        res.json(queryService.totalMs(uuid));
    });
}
```

The route above answers
`GET /api/v1/modules/example-playtime/players/{uuid}`.

### `onStart`

```java
default void onStart(ModuleContext context) throws Exception
```

Called when the module transitions to `ACTIVE`. By the time `onStart` returns
successfully, the host registers the handles from `capabilityHandles()`,
dependent modules can resolve them, and the REST routes are live. Start
background tasks here — schedule through `context.scheduler()` so the host can
cancel them on stop.

`onStart` fires only once requirements are satisfied: a module whose manifest
`requires` an absent capability stays in `WAITING` and `onStart` is deferred
until a provider activates (see [Lifecycle states](#lifecycle-states)).

Throwing transitions the module to `FAILED`.

```java
@Override
public void onStart(ModuleContext context) {
    this.flushTask = context.scheduler().scheduleAtFixedRate(
            repository::flush, Duration.ofSeconds(30), Duration.ofSeconds(30));
}
```

### `onStop`

```java
default void onStop(ModuleContext context) throws Exception
```

The counterpart to `onStart`. Called when the module leaves the `ACTIVE` set:
on uninstall, on upgrade of an active module, and when a required capability
disappears (`ACTIVE → STOPPING → WAITING`). Cancel anything `onStart` started.
Tasks scheduled through `context.scheduler()` are cancelled by the host
automatically; cancel everything else here.

Throwing transitions the module to `FAILED`.

```java
@Override
public void onStop(ModuleContext context) {
    if (flushTask != null) {
        flushTask.cancel();
        flushTask = null;
    }
}
```

### `onUnload`

```java
default void onUnload(ModuleContext context) throws Exception
```

The final hook before the host releases the classloader, on uninstall and on
upgrade. `onStop` (if the module was active) always runs before `onUnload`.
Drop references so the outgoing classloader can be collected.

Throwing transitions the module to `FAILED`; the module's routes are cleared
either way.

```java
@Override
public void onUnload(ModuleContext context) {
    this.queryService = null;
    this.repository = null;
}
```

### `onUpgrade`

```java
default void onUpgrade(ModuleContext context) throws Exception
```

Called once on the **new** entrypoint when a replacement jar is installed over
an existing module, immediately after that new entrypoint's `onLoad` and before
its `onRegisterRoutes`. The outgoing version is fully torn down first (`onStop`
if active, then `onUnload`), so by the time `onUpgrade` runs the old instance is
gone. Use it for schema migrations and config rewrites keyed on the previous
version:

```java
@Override
public void onUpgrade(ModuleContext context) {
    if (context.isUpgrade()) {              // previousVersion() is non-blank
        migrations.applyFrom(context.previousVersion());
    }
}
```

`context.previousVersion()` carries the version string being replaced (`""`
on a fresh install); `context.isUpgrade()` is the convenience boolean.

### `onReload`

```java
default void onReload(ModuleContext context) throws Exception
```

Hot-reload hook for the fast `ACTIVE → RELOADING → ACTIVE` path. Called on the
**new** entrypoint when a reload-compatible jar replaces a running module. A jar
is reload-compatible when its controller `entrypoint` declares
`reloadable: true` (manifest schema version 2+) **and** its capability
declaration — both `provides` and `requires` — is identical to the running
version's. Any capability-shape change forces the full `onUpgrade` path instead.

`onReload` is the **only** hook the reload path calls. The outgoing module is
never sent `onStop` or `onUnload`, so the new instance must hand off its own
live state from inside `onReload` — re-arm scheduler tasks, rebuild or re-point
caches. Routes are still cleared and re-registered (`onRegisterRoutes` runs
after `onReload`), because route handlers are classes in the outgoing
classloader and cannot be carried across.

A module that does not implement `onReload` must not set `reloadable: true`: the
default no-op would silently keep stale state. If `onReload` throws, the module
is left `FAILED` with no rollback.

```java
@Override
public void onReload(ModuleContext context) {
    cache.rebuildFrom(repository);          // hand off live state
    this.flushTask = context.scheduler().scheduleAtFixedRate(
            repository::flush, Duration.ofSeconds(30), Duration.ofSeconds(30));
}
```

## Capability export

### `capabilityHandles`

```java
default List<CapabilityHandle<?>> capabilityHandles()
```

Returns the capability handles this module exports for other modules to consume.
The controller calls this after the module reaches `ACTIVE` (during
`onStart`-driven activation, and again on reload/upgrade) and registers each
returned handle in the capability registry. Return `List.of()` (the default)
when the module provides nothing — and when your collaborators are not yet built
(before `onLoad` or after `onUnload`), guard with a null check and return the
empty list rather than risking an NPE.

Each `CapabilityHandle<T>` binds a capability id to a typed value:

```java
public static <T> CapabilityHandle<T> of(String id, Class<T> type, T value);

public String   id();    // the capability id
public Class<T> type();  // the public interface/class consumers resolve against
public T        value(); // the instance; must be an instanceof type
```

`CapabilityHandle.of(...)` enforces its invariants at construction:

| Condition | Result |
|---|---|
| `id` is `null` | `NullPointerException` |
| `id` is blank | `IllegalArgumentException("id must not be blank")` |
| `type` or `value` is `null` | `NullPointerException` |
| `value` is not an instance of `type` | `IllegalArgumentException("handle for '<id>' is not an instance of <type>")` |

Each handle's `id` must match a `capabilities.provides[].id` entry in the
module's `module.yaml`; `type` must be a public interface or class consumers can
legally resolve. See the [Capability API](/reference/module-sdk/capability-api/)
for the resolution side.

```java
public static final String QUERY_CAPABILITY_ID = "example-playtime-query";

@Override
@SuppressWarnings({"rawtypes", "unchecked"})
public List<CapabilityHandle<?>> capabilityHandles() {
    if (queryService == null) {
        return List.of();
    }
    ToLongFunction<UUID> totalPlaytimeQuery = queryService::totalMs;
    return List.of(CapabilityHandle.of(
            QUERY_CAPABILITY_ID, (Class) ToLongFunction.class, totalPlaytimeQuery));
}
```

## Health probe

### `healthCheck`

```java
default ModuleHealth healthCheck()
```

Optional liveness probe. The controller polls this on a fixed cadence for every
`ACTIVE` module and surfaces the latest result over REST
(`GET /api/v1/modules/platform/{id}/health`) and as the
`prexorcloud.module.health` metric. Health is **advisory** — orthogonal to the
lifecycle state — and the controller does not act on it automatically: a module
can be `ACTIVE` yet report `UNHEALTHY` because a backing service is down.

Implementations must be cheap and non-blocking. Check a cached flag or a
last-success timestamp; do not perform a live round-trip on the polling thread.
The poll runs outside the lifecycle lock, so a slow probe cannot stall
install / reconcile / uninstall. A probe that throws is recorded as `UNHEALTHY`.

`ModuleHealth` is a record `(Status status, String detail)` with factories:

```java
ModuleHealth.healthy();              // Status.HEALTHY, ""
ModuleHealth.healthy(String detail); // Status.HEALTHY
ModuleHealth.degraded(String detail);
ModuleHealth.unhealthy(String detail);
ModuleHealth.unknown();              // Status.UNKNOWN, "" — the default
```

| `Status` | Meaning |
|---|---|
| `HEALTHY` | Fully operational. |
| `DEGRADED` | Operational but impaired (running on a fallback, elevated error rate). |
| `UNHEALTHY` | Not operational — a dependency is down or the module cannot serve. |
| `UNKNOWN` | No signal; the module did not override `healthCheck()`. |

The default returns `ModuleHealth.unknown()`, so a module that doesn't opt in
reports `UNKNOWN` rather than a false-positive `HEALTHY`.

```java
@Override
public ModuleHealth healthCheck() {
    if (!started) {
        return ModuleHealth.unhealthy("not started");
    }
    if (repository == null) {
        return ModuleHealth.degraded("storage handle unavailable");
    }
    return ModuleHealth.healthy();
}
```

## Lifecycle states

The controller tracks each module through this state machine
(`ModuleLifecycleManager.ModuleState`):

| State | Meaning |
|---|---|
| `INSTALLED` | Jar loaded; `onLoad` and `onRegisterRoutes` have run. Transient. |
| `WAITING` | Installed but a required capability is unbound; `onStart` deferred. |
| `ACTIVE` | `onStart` succeeded; routes live, capability handles registered. |
| `RELOADING` | Mid hot-reload; only `onReload` runs in this transition. |
| `STOPPING` | Transient, while `onStop` runs. |
| `UNLOADED` | `onStop` (if active) and `onUnload` have run; classloader released. |
| `FAILED` | A hook threw; remaining hooks for that transition are skipped. |

Fresh install (requirements satisfied):

```text
onLoad
  └─ onRegisterRoutes
        └─ onStart                     (state: INSTALLED → ACTIVE)
                                       capabilityHandles() registered
```

Fresh install (a required capability is absent):

```text
onLoad
  └─ onRegisterRoutes                  (state: INSTALLED → WAITING)
        ⋮  provider activates, controller re-reconciles
        └─ onStart                     (state: WAITING → ACTIVE)
```

Required capability disappears, then returns:

```text
onStop                                 (state: ACTIVE → STOPPING → WAITING)
  ⋮  provider returns, controller re-reconciles
onStart                                (state: WAITING → ACTIVE)
```

Uninstall:

```text
onStop                                 (only if ACTIVE; STOPPING)
  └─ onUnload                          (state → UNLOADED, routes cleared)
```

Upgrade (replacement jar, old instance torn down first):

```text
[old]  onStop (if ACTIVE) → onUnload
[new]  onLoad → onUpgrade → onRegisterRoutes → onStart   (→ ACTIVE)
```

Hot-reload (`reloadable: true`, identical capability shape):

```text
[new]  onReload → onRegisterRoutes     (state: ACTIVE → RELOADING → ACTIVE)
       (old instance is never stopped or unloaded)
```

Any hook that throws moves the module to `FAILED` and skips the remaining hooks
for that transition; the module's routes are cleared.

## The module manifest

A platform module ships a `module.yaml` (parsed into `PlatformModuleManifest`).
The hooks above are wired through the `backend.controller.entrypoint`
fully-qualified class name. Key fields:

```yaml
manifestVersion: 1          # 1 or 2; reloadable requires 2
id: example-playtime        # capability ids and route prefix derive from this
version: 1.0.0-SNAPSHOT
hosts: [controller]         # defaults to [controller] when omitted
backend:
  controller:
    entrypoint: me.prexorjustin.prexorcloud.modules.example.platform.ExamplePlatformModule
    # reloadable: true      # manifestVersion 2+; opts into onReload fast path
storage:
  mongo: true               # context.requireMongoStorage() then works
  limits:
    mongoDocuments: 100000
capabilities:
  provides:                 # each id must match a capabilityHandles() handle id
    - id: example-playtime-query
      version: 1.0.0
  requires:                 # absent providers keep the module in WAITING
    - id: prexor.player.journey
      versionRange: ">=1.0.0 <2.0.0"
```

`CURRENT_MANIFEST_VERSION` is `2` and `MIN_MANIFEST_VERSION` is `1`; fields
introduced past their minimum version are rejected by the parser when declared
against an older schema.

## Hello-world

A minimal controller-side module with one REST route and one exported
capability. Two files: the manifest and the entrypoint.

`src/main/module/module.yaml`:

```yaml
manifestVersion: 1
id: hello-world
version: 1.0.0
hosts: [controller]
backend:
  controller:
    entrypoint: com.example.hello.HelloModule
storage:
  mongo: false
capabilities:
  provides:
    - id: hello-greeter
      version: 1.0.0
```

`src/main/java/com/example/hello/HelloModule.java`:

```java
package com.example.hello;

import java.util.List;
import java.util.function.Supplier;

import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleHealth;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;

public final class HelloModule implements PlatformModule {

    public static final String GREETER_CAPABILITY_ID = "hello-greeter";

    private Supplier<String> greeter;
    private boolean started;

    @Override
    public void onLoad(ModuleContext context) {
        // Composition root: build collaborators, resolve capabilities, open storage.
        this.greeter = () -> "hello from " + context.manifest().id();
        context.logger().info("loaded {}", context.manifest().id());
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar registrar) {
        // GET /api/v1/modules/hello-world/greeting
        registrar.get("/greeting", (req, res) -> res.json(greeter.get()));

        // GET /api/v1/modules/hello-world/greeting/{name}
        registrar.get("/greeting/{name}", (req, res) ->
                res.json("hello, " + req.pathParam("name")));
    }

    @Override
    public void onStart(ModuleContext context) {
        this.started = true;                 // module is now ACTIVE; routes live
    }

    @Override
    public void onStop(ModuleContext context) {
        this.started = false;
    }

    @Override
    public void onUnload(ModuleContext context) {
        this.greeter = null;                 // drop references for classloader GC
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<CapabilityHandle<?>> capabilityHandles() {
        if (greeter == null) {
            return List.of();
        }
        return List.of(CapabilityHandle.of(
                GREETER_CAPABILITY_ID, (Class) Supplier.class, greeter));
    }

    @Override
    public ModuleHealth healthCheck() {
        return started ? ModuleHealth.healthy() : ModuleHealth.unhealthy("not started");
    }
}
```

Once installed and `ACTIVE`:

```bash
curl -s http://localhost:8080/api/v1/modules/hello-world/greeting/ada
```

```json
"hello, ada"
```

## Patterns

- **`onLoad` is the composition root.** Build collaborators by constructor
  injection there; keep `onStart` / `onStop` to arming and disarming runtime
  work. The example modules (`example-playtime`, `stats-aggregator`) follow this
  shape.
- **Guard `capabilityHandles()`.** Return `List.of()` when your collaborators
  are null (before `onLoad`, after `onUnload`) so the controller never NPEs
  reading handles around a failed transition.
- **Logging and JSON come from the context.** Use `context.logger()` (SLF4J,
  pre-namespaced `module:<id>`) and `context.json()` (the standard Jackson
  `ObjectMapper`) rather than constructing your own.
- **Do not hold the `RouteRegistrar`.** It records routes synchronously during
  `onRegisterRoutes`; re-registration always flows back through that hook on
  upgrade and reload.

## See also

- [DaemonModule](/reference/module-sdk/daemon-module/) — the sibling contract
  for daemon-hosted modules.
- [ModuleContext](/reference/module-sdk/module-context/) — every service handed
  to the hooks above.
- [Capability API](/reference/module-sdk/capability-api/) — how
  `capabilityHandles()` and `requires` resolve.
- [REST routes](/reference/module-sdk/rest-routes/) — `RouteHandler`,
  `ApiRequest`, `ApiResponse`, typed bodies.
- [Concepts → Platform modules](/concepts/modules/platform/)
