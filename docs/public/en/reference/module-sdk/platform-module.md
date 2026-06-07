---
title: PlatformModule
description: Controller-side module entrypoint — lifecycle hooks, REST route registration, and capability handles for cross-module integration.
---

`PlatformModule` is the contract every controller-side module
implements. The host calls it through six lifecycle hooks, each
receiving a [`ModuleContext`](/reference/module-sdk/module-context/),
and pulls REST routes and capability handles from two extra hooks.
All methods have default no-op implementations — override only what
you need.

## What you'll learn

- The full lifecycle order: load → register routes → start → stop →
  unload, with `onUpgrade` for version-replacement.
- How capability handles are exported.
- How `onRegisterRoutes` integrates with the controller's HTTP API.

## API surface

The interface lives at
`me.prexorjustin.prexorcloud.api.module.platform.PlatformModule`.

### `onLoad`

```java
default void onLoad(ModuleContext context) throws Exception
```

Called once when the host loads the module jar. Use this to wire up
collaborators, ensure Mongo collections, and resolve required
capabilities. Throwing here aborts the load.

### `onRegisterRoutes`

```java
default void onRegisterRoutes(RouteRegistrar registrar)
```

Register module-owned REST routes. Called once after `onLoad` and
before `onStart`. Routes are mounted under
`/api/v1/modules/{moduleId}/`, share the controller's auth +
rate-limit middleware, and are dropped automatically on uninstall or
upgrade. See [REST Routes](/reference/module-sdk/rest-routes/).

### `onStart`

```java
default void onStart(ModuleContext context) throws Exception
```

The module is now in the `ACTIVE` state — capability handles you
declared are registered, dependents may resolve them, REST routes are
live. Start your background tasks here.

### `onStop`

```java
default void onStop(ModuleContext context) throws Exception
```

Pair of `onStart`. Cancel scheduled tasks; any task scheduled through
`context.scheduler()` is cancelled by the host automatically.

### `onUnload`

```java
default void onUnload(ModuleContext context) throws Exception
```

Final hook before the host releases the classloader. Drop references
to make heap analysis tractable.

### `onUpgrade`

```java
default void onUpgrade(ModuleContext context) throws Exception
```

Called once after `onLoad` when the host detects this is a version
upgrade rather than a fresh install. Inspect `context.previousVersion()`
to drive schema migrations or config rewrites.

### `capabilityHandles`

```java
default List<CapabilityHandle<?>> capabilityHandles()
```

Returns the capability handles this module exports. Each handle's id
must match a `provides` entry in `module.yaml`. Handles are read once
during the activation transition; later mutations are ignored.

## Lifecycle order

```
onLoad
  └─ onUpgrade           (only on version replacement)
        └─ onRegisterRoutes
              └─ onStart                  (state = ACTIVE)
                    ⋮
                    onStop                (state = STOPPING)
                          └─ onUnload
```

A failure in any hook transitions the module to `FAILED` and skips the
remaining hooks for that activation.

## Example

Drawn from `cloud-module-stats-aggregator`:

```java
public final class StatsAggregatorModule implements PlatformModule {

    public static final String LEADERBOARD_CAPABILITY_ID =
            "stats-aggregator-leaderboard";

    private StatsConfig config;
    private StatsRepository repository;
    private LeaderboardService leaderboard;
    private StatsRoutes routes;

    @Override
    public void onLoad(ModuleContext context) {
        this.config = StatsConfig.defaults();
        this.repository = new StatsRepository(context.requireMongoStorage());
        this.leaderboard = new LeaderboardService(repository);
        var tracker = context.findCapability(
                PlayerJourneyTracker.CAPABILITY_ID,
                PlayerJourneyTracker.class).orElse(null);
        var aggregator = new SessionAggregator(repository);
        var journey = new JourneyEnricher(tracker);
        var prometheus = new PrometheusExporter(repository, config.leaderboardSize());
        this.routes = new StatsRoutes(
                repository, aggregator, leaderboard, journey,
                prometheus, config, Clock.systemUTC());
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar registrar) {
        routes.register(registrar);
    }

    @Override
    public List<CapabilityHandle<?>> capabilityHandles() {
        return List.of(CapabilityHandle.of(
                LEADERBOARD_CAPABILITY_ID,
                LeaderboardService.class,
                leaderboard));
    }
}
```

Note the constructor-injection style for `StatsRepository`,
`SessionAggregator`, etc. — the entrypoint's `onLoad` is the
composition root. SLF4J for logging (via `context.logger()` or a
class-private `LoggerFactory.getLogger(...)`); Jackson via
`context.json()`.

## Next up

- [DaemonModule](/reference/module-sdk/daemon-module/) — sibling
  contract for daemon-host modules.
- [Capability API](/reference/module-sdk/capability-api/) — how
  `capabilityHandles()` plugs into the dependency graph.
- [REST Routes](/reference/module-sdk/rest-routes/) — `onRegisterRoutes`
  in detail.
- [Concepts → Platform modules](/concepts/modules/platform/)
