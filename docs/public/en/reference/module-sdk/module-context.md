---
title: ModuleContext
description: Per-module context handed to lifecycle hooks — manifest, jar path, upgrade hint, host, capabilities, Mongo and Redis storage, EventBus, logger, scheduler, HTTP client, and Jackson ObjectMapper.
---

`ModuleContext` is the single object every `PlatformModule` lifecycle hook receives. It exposes the module's identity, the capabilities it declared as `requires`, persistent storage handles, and the cross-cutting primitives shared with the plugin SDK (events, logger, scheduler, HTTP, JSON).

The interface lives at `me.prexorjustin.prexorcloud.api.module.platform.ModuleContext`. It is implemented by `ControllerModuleContext` in the controller process and `DaemonModuleContext` in each daemon process. Modules that target both hosts branch on `host()`.

## API surface

Every method is listed below. There are no overloads.

```java
public interface ModuleContext {

    // Module identity
    PlatformModuleManifest manifest();
    Path                   jarPath();
    String                 previousVersion();
    default boolean        isUpgrade();
    ModuleHost             host();

    // Capabilities
    <T> Optional<T> findCapability(String capabilityId, Class<T> type);
    <T> T           requireCapability(String capabilityId, Class<T> type);

    // Persistent storage
    Optional<ModuleDataStore>      findMongoStorage();
    ModuleDataStore                requireMongoStorage();
    Optional<PlatformRedisStorage> findRedisStorage();
    PlatformRedisStorage           requireRedisStorage();

    // Cross-cutting primitives (mirror CloudPluginContext)
    EventBus      events();
    Logger        logger();
    TaskScheduler scheduler();
    HttpClient    httpClient();
    ObjectMapper  json();
}
```

## Module identity

### `manifest()`

```java
PlatformModuleManifest manifest();
```

Returns the parsed `module.yaml` for this module. `PlatformModuleManifest` is a record:

```java
record PlatformModuleManifest(
        int manifestVersion,
        String id,
        String version,
        Backend backend,
        Frontend frontend,
        CapabilityDeclaration capabilities,
        ModuleStorageRequest storage,
        List<WorkloadExtensionManifest> extensions,
        List<ModuleHost> hosts)
```

`hosts` defaults to `[CONTROLLER]` when the YAML omits it. `capabilities` defaults to `CapabilityDeclaration.EMPTY`, `storage` to `ModuleStorageRequest.NONE`, `extensions` to an empty list. `CURRENT_MANIFEST_VERSION` is `2`; `MIN_MANIFEST_VERSION` is `1`. See [module.yaml](/reference/module-sdk/module-yaml/).

```java
PlatformModuleManifest m = context.manifest();
context.logger().info("running {} v{} on hosts {}", m.id(), m.version(), m.hosts());
```

### `jarPath()`

```java
Path jarPath();
```

Filesystem path to the module's own jar. Use it to read bundled resources from disk when classloader resource lookup is insufficient (e.g. opening the jar as a `FileSystem`).

### `previousVersion()`

```java
String previousVersion();
```

Empty string `""` on a fresh install; the previous version string when the current hook fires during an upgrade or reload. This is the raw input behind `isUpgrade()` and the value a module diffs inside `onUpgrade` or `onReload` to decide which migration to run.

### `isUpgrade()`

```java
default boolean isUpgrade();
```

Convenience guard, implemented as:

```java
default boolean isUpgrade() {
    String previous = previousVersion();
    return previous != null && !previous.isBlank();
}
```

Returns `true` when `previousVersion()` is non-null and non-blank. The canonical guard for migration logic.

```java
@Override
public void onLoad(ModuleContext context) {
    if (context.isUpgrade()) {
        migrateFrom(context.previousVersion());
    }
}
```

### `host()`

```java
ModuleHost host();
```

Which process hosts this module. `ModuleHost` is an enum:

| Value | Meaning |
|---|---|
| `CONTROLLER` | Controller process. Has Mongo + Redis storage, cluster-wide event bus, REST route registration. |
| `DAEMON` | Daemon process. Has node-local storage (if any), subscribes to controller events through the daemon's gRPC bridge, receives instance lifecycle hooks for instances on this node. |

A class shared between controller- and daemon-side branches on `host()` to pick the correct behaviour for the JVM it is running in.

```java
if (context.host() == ModuleHost.DAEMON) {
    // node-local path
} else {
    // controller path: storage + REST available
}
```

## Capabilities

Look up cloud-side capabilities declared as `requires` in the manifest. See [Capability API](/reference/module-sdk/capability-api/) for the `provides` side.

### `findCapability(String, Class<T>)`

```java
<T> Optional<T> findCapability(String capabilityId, Class<T> type);
```

Returns the capability bound to `capabilityId`, cast to `type`, or `Optional.empty()` when the capability is currently unbound (provider absent or not yet activated). Use this for capabilities the module can run without.

```java
PlayerJourneyTracker tracker = context.findCapability(
        PlayerJourneyTracker.CAPABILITY_ID, PlayerJourneyTracker.class)
        .orElse(null);
```

### `requireCapability(String, Class<T>)`

```java
<T> T requireCapability(String capabilityId, Class<T> type);
```

Like `findCapability` but throws if the capability is unbound. Use only for capabilities the module cannot meaningfully run without.

```java
InstanceFileAccess files =
        context.requireCapability("prexor.instance.files", InstanceFileAccess.class);
```

## Persistent storage

Storage handles are present only when the module requested them in its manifest `storage` block, and Mongo is controller-host only. See [Storage API](/reference/module-sdk/storage-api/) for the full `ModuleDataStore` and `PlatformRedisStorage` surfaces.

### `findMongoStorage()`

```java
Optional<ModuleDataStore> findMongoStorage();
```

Empty when the module did not request Mongo storage in its manifest, and on every daemon-host context.

### `requireMongoStorage()`

```java
ModuleDataStore requireMongoStorage();
```

Returns the `ModuleDataStore` directly, or throws when unavailable. `ModuleDataStore` is a document store scoped to the module's collection namespace (`collectionPrefix()`), with `ensureCollection`, `createIndex`, `insertOne`/`insertMany`, `findOne`/`find`/`count`, `updateOne`/`updateMany`/`upsertOne`, `deleteOne`/`deleteMany`, and `withTransaction`.

```java
ModuleDataStore mongo = context.requireMongoStorage();
mongo.ensureCollection("sessions");
```

### `findRedisStorage()`

```java
Optional<PlatformRedisStorage> findRedisStorage();
```

Empty when the module did not request Redis storage.

### `requireRedisStorage()`

```java
PlatformRedisStorage requireRedisStorage();
```

Returns the `PlatformRedisStorage` handle, or throws when unavailable. `PlatformRedisStorage` is a module-scoped key/value surface: `keyPrefix()`, `qualify(key)`, `get`, `set(key, value)`, `set(key, value, ttl)`, `increment`, `decrement`, `delete`. All keys are namespaced under `keyPrefix()`.

```java
PlatformRedisStorage redis = context.requireRedisStorage();
redis.set("last-flush", Instant.now().toString(), Duration.ofHours(1));
```

## Cross-cutting primitives

These five accessors mirror the plugin SDK's `CloudPluginContext` — one contract, two host processes.

### `events()`

```java
EventBus events();
```

Cluster-wide event bus. Modules subscribe to lifecycle events (player join/leave, instance state changes, deployments) and publish their own. The `EventBus` surface:

```java
<T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType);
<T extends CloudEvent> EventSubscription subscribe(Class<T> eventType, EventHandler<T> handler);
EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler);
EventSubscription subscribeAll(EventHandler<CloudEvent> handler);
void publish(CloudEvent event);
```

```java
context.events().on(PlayerConnectedEvent.class)
        .filter(e -> e.group().equals("lobby"))
        .subscribe(e -> context.logger().info("{} joined lobby", e.name()));
```

See [EventBus](/reference/module-sdk/event-bus/) for the full event catalogue and the fluent builder.

### `logger()`

```java
Logger logger();
```

SLF4J `Logger` pre-namespaced as `module:<id>` so module log lines are attributable in mixed-source streams. Use it directly; do not construct your own `LoggerFactory.getLogger(...)`.

```java
context.logger().info("loaded; upgrade={}", context.isUpgrade());
```

### `scheduler()`

```java
TaskScheduler scheduler();
```

Task scheduler for module-owned async work (background reconciliation, periodic flushes, delayed cleanup). The host owns the lifecycle — tasks are cancelled automatically on module stop. `TaskScheduler`:

```java
ScheduledTask schedule(Runnable task);
ScheduledTask scheduleDelayed(Duration delay, Runnable task);
ScheduledTask scheduleAtFixedRate(Duration initialDelay, Duration period, Runnable task);
ScheduledTask scheduleAt(Instant when, Runnable task);
<T> CompletableFuture<T> submit(Callable<T> task);

// legacy aliases (default methods)
ScheduledTask runAsync(Runnable task);                                   // -> schedule
ScheduledTask runDelayed(Duration delay, Runnable task);                 // -> scheduleDelayed
ScheduledTask runAtFixedRate(Duration initialDelay, Duration period,
                             Runnable task);                             // -> scheduleAtFixedRate
```

`ScheduledTask` exposes `cancel()` and `isCancelled()`. `cancel()` is a no-op if already cancelled or completed.

```java
ScheduledTask flush = context.scheduler()
        .scheduleAtFixedRate(Duration.ofSeconds(30), Duration.ofMinutes(5), this::flush);
// later, or automatically on stop:
flush.cancel();
```

### `httpClient()`

```java
HttpClient httpClient();
```

Pre-configured outbound `java.net.http.HttpClient` for webhooks, third-party APIs, anything outside the cluster. The connection pool is shared across modules so per-module connection cost is amortised.

```java
HttpResponse<String> res = context.httpClient().send(
        HttpRequest.newBuilder(URI.create(webhookUrl))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .header("content-type", "application/json")
                .build(),
        HttpResponse.BodyHandlers.ofString());
```

### `json()`

```java
ObjectMapper json();
```

Standard Jackson `ObjectMapper`: java-time module registered, ISO-8601 timestamps, `NON_NULL` serialisation, lenient on unknown properties. Use it for REST payloads, event serialisation, anything wire-format. Gson is not on the classpath.

```java
String body = context.json().writeValueAsString(new Payload(count, Instant.now()));
```

## Lifecycle availability

Which accessors are safe inside which `PlatformModule` hook. See [PlatformModule](/reference/module-sdk/platform-module/) for the hook contract; `onRegisterRoutes` takes a `RouteRegistrar`, not a `ModuleContext`.

| Hook | Storage | Capabilities | Events | Scheduler |
|---|---|---|---|---|
| `onLoad` | safe (`ensureCollection`, index setup) | `findCapability` only — `require*` may fail if the provider isn't active yet | subscriptions allowed; publishing not yet | `schedule*` queued, run after `onStart` |
| `onUpgrade` | safe | safe | safe | safe |
| `onRegisterRoutes` | n/a — register routes only, no logic | n/a | n/a | n/a |
| `onStart` | safe | safe | safe | safe |
| `onReload` | safe | safe | safe | safe — re-arm tasks here; the outgoing module is not stopped |
| `onStop` | safe | best-effort | best-effort | tasks already cancelled |
| `onUnload` | unsafe — references being dropped | unsafe | unsafe | n/a |

`onReload` is the only hook the hot-reload fast path calls (controller entrypoint `reloadable: true`); the new instance must re-arm scheduler tasks and rebuild caches itself because the outgoing module never receives `onStop`/`onUnload`.

## Worked example

From the first-party stats-aggregator reference module (`StatsAggregatorModule`). `onLoad` wires storage and an optional capability; the handle is captured into fields and torn down in `onUnload`.

```java
public final class StatsAggregatorModule implements PlatformModule {

    private StatsRepository repository;
    private JourneyEnricher journey;
    private boolean started;

    @Override
    public void onLoad(ModuleContext context) {
        // Mongo required by manifest -> use require*.
        repository = new StatsRepository(context.requireMongoStorage());

        // Optional capability: built-in PlayerJourneyTracker, defensively resolved.
        PlayerJourneyTracker tracker = context.findCapability(
                        PlayerJourneyTracker.CAPABILITY_ID, PlayerJourneyTracker.class)
                .orElse(null);
        journey = new JourneyEnricher(tracker);

        // SLF4J logger pre-namespaced as "module:stats-aggregator".
        context.logger().info("loaded; journey tracker present={}", tracker != null);
    }

    @Override
    public void onStart(ModuleContext context) {
        started = true;
    }

    @Override
    public void onUnload(ModuleContext context) {
        journey = null;
        repository = null;
        started = false;
    }
}
```

## Next up

- [PlatformModule](/reference/module-sdk/platform-module/) — the contract that consumes this context.
- [Storage API](/reference/module-sdk/storage-api/) — `ModuleDataStore` and `PlatformRedisStorage` in full.
- [EventBus](/reference/module-sdk/event-bus/) — `events()` deep dive and event catalogue.
- [Capability API](/reference/module-sdk/capability-api/) — declaring and providing capabilities.
- [module.yaml](/reference/module-sdk/module-yaml/) — the manifest schema returned by `manifest()`.
