---
title: Module SDK
description: Java SDK reference for PrexorCloud platform and daemon modules — entrypoints, ModuleContext, capabilities, storage, REST routes, scheduling, and module.yaml.
---

A module is a backend extension that runs inside the controller (and
optionally inside each daemon). It ships as one shaded jar plus a
`module.yaml` manifest. The host loads the jar in its own JVM and drives
the entrypoint through a lifecycle, handing each hook a
[`ModuleContext`](/reference/module-sdk/module-context/) that exposes
storage, events, capabilities, scheduling, HTTP, and JSON.

All SDK types live under
`me.prexorjustin.prexorcloud.api.module` in the `cloud-api` artifact.

## What you'll learn

- The two entrypoint contracts: `PlatformModule` (controller) and
  `DaemonModule` (per-node).
- The shared `ModuleContext` surface.
- The orthogonal subsystems: events, capabilities, storage, REST,
  scheduling.
- The on-disk `module.yaml` schema.

## SDK pages

| Page | Surface |
|---|---|
| [PlatformModule](/reference/module-sdk/platform-module/) | Controller-side lifecycle, REST registration, capability handles, health. |
| [DaemonModule](/reference/module-sdk/daemon-module/) | Daemon-side lifecycle plus per-instance hooks. |
| [ModuleContext](/reference/module-sdk/module-context/) | Shared context: identity, storage, events, scheduler, HTTP, JSON, capabilities. |
| [EventBus](/reference/module-sdk/event-bus/) | Subscribing to and publishing cluster events. |
| [Capability API](/reference/module-sdk/capability-api/) | The `provides` / `requires` graph and `CapabilityHandle`. |
| [Storage API](/reference/module-sdk/storage-api/) | Mongo `ModuleDataStore` and Redis `PlatformRedisStorage`. |
| [REST routes](/reference/module-sdk/rest-routes/) | `onRegisterRoutes` and the per-module route dispatcher. |
| [module.yaml](/reference/module-sdk/module-yaml/) | Manifest schema. |

## Entrypoints

A module implements one of two contracts depending on which host runs
it. The manifest's `hosts` list picks the host(s); each host listed must
have a matching `backend` entrypoint.

| Contract | Package | Host | Storage | Per-instance hooks |
|---|---|---|---|---|
| `PlatformModule` | `me.prexorjustin.prexorcloud.api.module.platform` | controller | Mongo + Redis | no |
| `DaemonModule` | `me.prexorjustin.prexorcloud.api.module.platform` | daemon (per node) | none — `findMongoStorage()` returns `Optional.empty()` | yes |

A module that needs both sides declares `hosts: [controller, daemon]`,
ships a `PlatformModule` under `backend.controller.entrypoint` and a
`DaemonModule` under `backend.daemon.entrypoint`. The two halves run in
different processes and share no heap state; they communicate through
events forwarded from the controller bus to the daemon.

### PlatformModule lifecycle hooks

All hooks are `default` (no-op) so a module overrides only what it needs.
Every hook except `onRegisterRoutes` receives a `ModuleContext` and may
throw checked exceptions.

| Method | Signature | When |
|---|---|---|
| `onLoad` | `void onLoad(ModuleContext context) throws Exception` | After the jar loads, before routes and `onStart`. Wire up repositories and services here. |
| `onRegisterRoutes` | `void onRegisterRoutes(RouteRegistrar registrar)` | Once, after `onLoad`, before `onStart`. Register REST routes. |
| `onStart` | `void onStart(ModuleContext context) throws Exception` | Module transitions to active. |
| `onStop` | `void onStop(ModuleContext context) throws Exception` | Module is stopping. |
| `onUnload` | `void onUnload(ModuleContext context) throws Exception` | Jar is being unloaded. Release references. |
| `onUpgrade` | `void onUpgrade(ModuleContext context) throws Exception` | A newer version replaced a previous one; `context.previousVersion()` carries the old version. |
| `onReload` | `void onReload(ModuleContext context) throws Exception` | Hot-reload fast path (`reloadable: true`). The **only** hook called on reload — `onStop`/`onUnload` are skipped, so the new instance must re-arm scheduler tasks and rebuild caches itself. |
| `capabilityHandles` | `List<CapabilityHandle<?>> capabilityHandles()` | Polled after activation. Returns the handles this module exports. Default `List.of()`. |
| `healthCheck` | `ModuleHealth healthCheck()` | Polled on a fixed cadence for active modules. Must be cheap and non-blocking. Default `ModuleHealth.unknown()`. |

### DaemonModule lifecycle and instance hooks

`DaemonModule` shares `onLoad`/`onStart`/`onStop`/`onUnload`/`onUpgrade`
and `capabilityHandles` with `PlatformModule` (same signatures), and adds
per-instance hooks for instances running on the local node:

| Method | Signature | When |
|---|---|---|
| `onInstanceStarting` | `void onInstanceStarting(InstanceSpec spec) throws Exception` | Pre-launch. Mutate `spec.jvmArgs()` or `spec.env()` to inject flags. Throwing aborts the start. |
| `onInstanceStarted` | `void onInstanceStarted(InstanceHandle handle) throws Exception` | After the process is spawned and the daemon has a PID. |
| `onInstanceStopping` | `void onInstanceStopping(InstanceHandle handle) throws Exception` | Before the daemon stops the process. |
| `onInstanceStopped` | `void onInstanceStopped(InstanceHandle handle, ExitInfo exit) throws Exception` | After the process exits (clean or crashed). |

Daemon capability handles are node-local; cross-node visibility is out of
scope.

## ModuleContext at a glance

`ModuleContext` is the single argument to every lifecycle hook. Full
detail is on the [ModuleContext](/reference/module-sdk/module-context/)
page; the surface:

| Method | Returns | Purpose |
|---|---|---|
| `manifest()` | `PlatformModuleManifest` | The parsed `module.yaml`. |
| `jarPath()` | `Path` | Location of the loaded module jar. |
| `previousVersion()` | `String` | `""` on fresh install; the prior version when upgrading. |
| `isUpgrade()` | `boolean` | `true` when `previousVersion()` is non-blank. |
| `host()` | `ModuleHost` | `CONTROLLER` or `DAEMON`. |
| `findCapability(id, type)` | `Optional<T>` | Resolve a `requires` capability; empty if unbound. |
| `requireCapability(id, type)` | `T` | Resolve or throw. |
| `findMongoStorage()` | `Optional<ModuleDataStore>` | Mongo store if `storage.mongo: true`. |
| `requireMongoStorage()` | `ModuleDataStore` | Mongo store or throw. |
| `findRedisStorage()` | `Optional<PlatformRedisStorage>` | Redis store if `storage.redis: true`. |
| `requireRedisStorage()` | `PlatformRedisStorage` | Redis store or throw. |
| `events()` | `EventBus` | Cluster-wide event bus. |
| `logger()` | `org.slf4j.Logger` | Pre-namespaced `module:<id>`. |
| `scheduler()` | `TaskScheduler` | Module-owned async work; tasks cancelled on stop. |
| `httpClient()` | `java.net.http.HttpClient` | Shared outbound client for webhooks and third-party APIs. |
| `json()` | `com.fasterxml.jackson.databind.ObjectMapper` | java-time, ISO-8601, `NON_NULL`, lenient on unknown properties. |

## Hello-world platform module

A minimal module that owns one Mongo collection, registers one `GET`
route, and logs through SLF4J:

```java
package com.example.hello;

import java.util.Map;

import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;

public final class HelloModule implements PlatformModule {

    private ModuleContext context;

    @Override
    public void onLoad(ModuleContext context) {
        this.context = context;
        context.requireMongoStorage().ensureCollection("greetings");
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar registrar) {
        registrar.get("/greetings", (req, res) -> {
            long count = context.requireMongoStorage().count("greetings", null);
            res.json(Map.of("count", count));
        });
    }

    @Override
    public void onStart(ModuleContext context) {
        context.logger().info("hello module started");
    }
}
```

```yaml
# src/main/module/module.yaml
manifestVersion: 1
id: hello
version: 1.0.0
hosts: [controller]
backend:
  controller:
    entrypoint: com.example.hello.HelloModule
storage:
  mongo: true
```

The route is mounted at `/api/v1/modules/hello/greetings`. The collection
is namespaced under the module's `mod_hello_` prefix
(`ModuleDataStore.collectionPrefix()`).

## module.yaml schema

The manifest is parsed into
`PlatformModuleManifest`. `CURRENT_MANIFEST_VERSION` is `2`;
`MIN_MANIFEST_VERSION` is `1`. Fields introduced past their schema
version (for example `capabilities.provides[].deprecatedSince` requires
v2) are rejected when declared under an older `manifestVersion`.

| Field | Type | Required | Notes |
|---|---|---|---|
| `manifestVersion` | int | yes | `1` or `2`. |
| `id` | string | yes | Module id; namespaces storage, routes, logger. |
| `version` | string | yes | Semver. |
| `hosts` | list of `controller` / `daemon` | no | Defaults to `[controller]` when omitted. |
| `backend.controller.entrypoint` | string | when `controller` is a host | FQCN of a `PlatformModule`. |
| `backend.controller.reloadable` | bool | no | v2+, default `false`. Opts into the `onReload` fast path. |
| `backend.daemon.entrypoint` | string | when `daemon` is a host | FQCN of a `DaemonModule`. |
| `frontend.sdkVersion` | int | no | Dashboard frontend bundle SDK version. |
| `frontend.entry` | string | no | Frontend entry file (e.g. `index.js`). |
| `storage.mongo` | bool | no | Allocate a scoped Mongo namespace. Default `false`. |
| `storage.redis` | bool | no | Allocate a scoped Redis prefix. Default `false`. |
| `storage.limits.mongoDocuments` | long | no | Document cap; requires `mongo: true`. |
| `storage.limits.redisKeys` | long | no | Key cap; requires `redis: true`. |
| `capabilities.provides[]` | `{id, version, deprecatedSince?, removedIn?}` | no | Capabilities offered to other modules. `deprecatedSince`/`removedIn` are v2+. |
| `capabilities.requires[]` | `{id, versionRange}` | no | Must resolve before the module reaches active. |
| `extensions[]` | workload extension | no | In-MC artifacts shipped with the module; see below. |

### Workload extensions

`extensions[]` entries describe in-MC artifacts the module ships
(`WorkloadExtensionManifest`). Each names a `target`
(`RuntimeTarget`, e.g. `server/paper`, `proxy/velocity`,
`server/bedrock-geyser`), an `activation` policy
(`ActivationPolicy`: `explicit-group-attach`, `default-enabled`,
`always`), optional `conflicts`, and one or more `variants`. Each variant
carries `id`, `mcVersionRange`, `runtimeApiVersion`, `artifact`,
`sha256` (use `AUTO` to compute at build time), and `installPath`.

```yaml
extensions:
  - id: example-playtime-paper
    target: server/paper
    activation: explicit-group-attach
    variants:
      - id: example-playtime-paper
        mcVersionRange: "*"
        runtimeApiVersion: 1
        artifact: extensions/server/paper/example-playtime-paper.jar
        sha256: AUTO
        installPath: plugins/
```

## Conventions

- **Logging**: SLF4J only (`org.slf4j.Logger`). Use the pre-namespaced
  `context.logger()`.
- **JSON**: use `context.json()` (Jackson, java-time, ISO-8601,
  `NON_NULL`). No Gson, no hand-rolled serialization.
- **Persistence**: Mongo via `ModuleDataStore`, Redis via
  `PlatformRedisStorage`. Both are scoped per module.
- **DI**: constructor injection only. Build services in `onLoad` and pass
  dependencies in.
- **REST**: register in `onRegisterRoutes`; do not retain the
  `RouteRegistrar`. Routes mount under `/api/v1/modules/{id}/` and are
  dropped on uninstall or upgrade.

## Reference modules

Two first-party modules in the repo exercise the surface end to end:

- `java/cloud-modules/example` (`example-playtime`) — Mongo storage, a
  REST route set, a `ToLongFunction<UUID>` capability handle, a worked
  `healthCheck()`, and per-runtime workload extensions.
- `java/cloud-modules/stats-aggregator` — consumes the
  `prexor.player.journey` capability (`requires`) and provides a
  leaderboard capability.

## Next up

- [PlatformModule](/reference/module-sdk/platform-module/) — start here
  for controller-side logic.
- [Concepts → Modules](/concepts/modules/) — the architecture behind
  these contracts.
