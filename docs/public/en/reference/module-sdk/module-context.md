---
title: ModuleContext
description: Per-module context handed to lifecycle hooks — manifest, capabilities, Mongo and Redis storage, EventBus, scheduler, HTTP client, and Jackson ObjectMapper.
---

`ModuleContext` is the single object every lifecycle hook receives.
It exposes the module's identity, the capabilities it declared as
required, persistent storage handles, and the cross-cutting
primitives shared with the plugin SDK (events, logger, scheduler,
HTTP, JSON).

## What you'll learn

- Every method on `ModuleContext`, grouped by purpose.
- Which methods are safe to call inside which lifecycle hook.
- How `host()` lets a dual-host module branch behaviour.

## API surface

The interface lives at
`me.prexorjustin.prexorcloud.api.module.platform.ModuleContext`. It is
implemented by the controller (`ControllerModuleContext`) and each
daemon (`DaemonModuleContext`).

### Module identity

```java
PlatformModuleManifest manifest();
Path                   jarPath();
String                 previousVersion();   // empty on fresh install
boolean                isUpgrade();         // convenience: !previousVersion().isBlank()
ModuleHost             host();              // CONTROLLER or DAEMON
```

`isUpgrade()` is the canonical guard for migration logic in
`onUpgrade`. `host()` lets a class shared between controller- and
daemon-side branch on which JVM it's running in.

### Capabilities

```java
<T> Optional<T> findCapability(String capabilityId, Class<T> type);
<T> T           requireCapability(String capabilityId, Class<T> type);
```

Look up capabilities declared as `requires` in the manifest.
`findCapability` returns empty if the capability is unbound (provider
absent or not yet activated); `requireCapability` throws — use it only
for capabilities the module cannot meaningfully run without.

See [Capability API](/reference/module-sdk/capability-api/) for the
provides side.

### Persistent storage

```java
Optional<ModuleDataStore>      findMongoStorage();
ModuleDataStore                requireMongoStorage();
Optional<PlatformRedisStorage> findRedisStorage();
PlatformRedisStorage           requireRedisStorage();
```

`findMongoStorage()` is empty when the module did not request Mongo
storage in its manifest, and on every daemon-host context. See the
[Storage API](/reference/module-sdk/storage-api/) page for the
`ModuleDataStore` and `PlatformRedisStorage` surfaces.

### Cross-cutting primitives

```java
EventBus       events();
Logger         logger();        // SLF4J
TaskScheduler  scheduler();
HttpClient     httpClient();    // java.net.http
ObjectMapper   json();          // Jackson
```

- **`events()`** — cluster-wide event bus, mirror of the plugin SDK's
  `CloudPluginContext.events()`. See [EventBus](/reference/module-sdk/event-bus/).
- **`logger()`** — SLF4J logger pre-namespaced as `module:<id>` so
  module log lines are attributable in mixed-source streams.
- **`scheduler()`** — task scheduler for background work; tasks are
  cancelled automatically on `onStop`.
- **`httpClient()`** — pre-configured outbound `HttpClient`. Pool is
  shared across modules so per-module connection cost is amortised.
- **`json()`** — standard Jackson `ObjectMapper` (java-time,
  ISO-8601, `NON_NULL` serialisation, lenient on unknown
  properties). Use this for REST payloads, event serialisation,
  anything wire-format. **Do not bring Gson — it's not on the
  classpath.**

## Lifecycle availability

| Hook | Storage | Capabilities | Events | Scheduler |
|---|---|---|---|---|
| `onLoad` | safe (`ensureCollection`) | `findCapability` only — `require*` may fail if provider isn't active yet | subscriptions allowed; **publishing not yet** | `schedule*` queued, run after `onStart` |
| `onUpgrade` | safe | safe | safe | safe |
| `onRegisterRoutes` | n/a — register only, don't run logic | n/a | n/a | n/a |
| `onStart` | safe | safe | safe | safe |
| `onStop` | safe | best-effort | best-effort | tasks already cancelled |
| `onUnload` | unsafe — references being dropped | unsafe | unsafe | n/a |

## Example

```java
public final class StatsAggregatorModule implements PlatformModule {

    @Override
    public void onLoad(ModuleContext context) {
        // Mongo: required by manifest -> use require*.
        ModuleDataStore mongo = context.requireMongoStorage();
        mongo.ensureCollection("sessions");

        // Optional capability: the controller registers PlayerJourneyTracker
        // built-in, but a defensive findCapability keeps tests simple.
        PlayerJourneyTracker tracker = context.findCapability(
                PlayerJourneyTracker.CAPABILITY_ID,
                PlayerJourneyTracker.class).orElse(null);

        // SLF4J logger pre-namespaced as "module:stats-aggregator".
        context.logger().info("loaded; tracker present={}", tracker != null);
    }
}
```

## Next up

- [PlatformModule](/reference/module-sdk/platform-module/) — the
  contract that consumes this context.
- [Storage API](/reference/module-sdk/storage-api/) — Mongo + Redis
  details.
- [EventBus](/reference/module-sdk/event-bus/) — `events()` deep dive.
