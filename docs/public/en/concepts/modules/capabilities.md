---
title: Capability Registry
description: How modules expose typed contracts to other modules — providers, consumers, dynamic handles, and the no-classloader-leakage rule.
---

The capability registry is the only mechanism by which modules link to
each other. A capability is a named, typed contract — the cluster's
equivalent of a service interface. Modules **provide** capabilities they
own; modules **consume** capabilities they need. The registry resolves
the wiring at activation time and keeps the binding live as providers
come and go.

## What you'll learn

- The shape of a capability and the rules around naming and types.
- How providers register and how consumers resolve.
- Why handles are dynamic and what that means at runtime.
- How the registry prevents cross-module classloader leakage.
- What lifecycle events it emits and how the dashboard tracks them.

## What a capability is

A capability is a `String` name plus an interface defined in `cloud-api`:

```java
// In cloud-api: define the contract
public interface PlayerJourneyTracker {
    List<PlayerJourneyEvent> getJourney(UUID player, int limit);
    PlayerJourneyEvent getLatest(UUID player);
}
```

Names are dotted by convention — `prexor.player.journey`,
`stats.aggregator.leaderboard`, `webhook.alerts.dispatcher`. The
controller does not enforce a naming scheme; the convention is what
keeps third-party modules from colliding.

**At any moment there is at most one provider for a given capability
name.** Two modules competing for the same name results in the second
provider rejecting at activation time with a clear error.

The interface must live in `cloud-api` (or another type-only jar both
sides compile against). It must not live in either provider's or
consumer's module jar — that would re-create the cross-classloader
problem capabilities exist to avoid.

## Providing a capability

A platform module declares its capability bindings by overriding
`capabilities()`:

```java
public final class StatsAggregatorModule implements PlatformModule {
    private LeaderboardImpl leaderboard;

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
        leaderboard = new LeaderboardImpl(ctx.requireMongoStorage());
    }

    private LeaderboardProvider resolveLeaderboard() {
        return leaderboard;        // never null after onStart
    }
}
```

The supplier (`this::resolveLeaderboard`) is called whenever a consumer
asks for the handle's `get()`. Returning `null` is allowed and means
"not ready"; consumers handle that case explicitly.

A daemon module's `capabilityHandles()` is the equivalent for the
node-local registry. See [Daemon Modules](/concepts/modules/daemon/).

## Consuming a capability

```java
public final class WebhookAlertsModule implements PlatformModule {
    private CapabilityHandle<PlayerJourneyTracker> journey;

    @Override
    public void onStart(ModuleContext ctx) {
        journey = ctx.capabilities().resolve(
            "prexor.player.journey",
            PlayerJourneyTracker.class
        );

        ctx.events().subscribe(InstanceCrashedEvent.class, this::onCrash);
    }

    private void onCrash(InstanceCrashedEvent e) {
        var tracker = journey.get();          // null if no provider
        if (tracker == null) return;
        var recent = tracker.getJourney(e.lastPlayer(), 10);
        // ...
    }
}
```

The handle is **dynamic**:

- When the provider deactivates, `handle.get()` returns `null`. The
  consumer does not throw; it copes.
- When a different provider rebinds (e.g. an upgrade installs a new
  version of the provider), the same handle now returns the new
  instance.
- Consumers do not refetch.

This is the property that lets you upgrade a provider module without
restarting every consumer.

## Declaring dependencies in the manifest

Modules declare their consumed capabilities in their manifest:

```yaml
dependencies:
  capabilities:
    - id: prexor.player.journey
      required: true
    - id: stats.aggregator.leaderboard
      required: false
```

| Required | Effect |
|---|---|
| `true` | The module enters `WAITING` if the capability has no provider; it does not move to `ACTIVE` until one binds. |
| `false` | The module activates regardless; consumer code copes with `handle.get() == null`. |

Required dependencies are the safety net. A module that absolutely needs
the journey tracker shouldn't activate while the journey provider is
absent — better to stay in `WAITING` and surface the dependency in the
dashboard than to half-run.

See [Lifecycle](/concepts/modules/lifecycle/) for the full state
machine, including how `WAITING` resolves on capability events.

## First-party capabilities

Some capabilities ship as bundled first-party modules rather than inside the controller:

- `prexor.player.journey` → `PlayerJourneyTracker` — the Player
  Journey Bus,
  provided by the bundled `cloud-module-player-journey` module.

The module auto-activates on controller start, so consumer modules can resolve
these capabilities on first load.

## Lifecycle events

Three event types fire on capability state changes:

- `CapabilityRegisteredEvent(capabilityId, version, moduleId)`
- `CapabilityProviderChangedEvent(capabilityId, moduleId, fromVersion, toVersion)`
- `CapabilityUnregisteredEvent(capabilityId, moduleId)`

All three implement `CloudEvent` so they flow through the global SSE
bus. The dashboard's `useCapability` composable seeds from
`GET /api/v1/modules/platform/capabilities` and live-updates from these
events. There is also a dedicated stream
(`GET /api/v1/modules/platform/capabilities/stream`) for external
consumers that don't want to subscribe to the firehose.

## Why this rule exists

The alternative — modules linking through shared internal classes — is
the classic Minecraft-plugin failure mode. One plugin upgrades a
dependency, an internal class signature changes, every dependent plugin
breaks at runtime with a `NoSuchMethodError`. The reload story is
"restart the whole server."

Capabilities replace that with:

- **One contract per capability.** The interface lives in `cloud-api`;
  it doesn't change without a controller release.
- **No classloader exposure.** Module B never sees Module A's classes
  except through the shared interface in the parent classloader.
- **Dynamic rebinding.** Module A can be replaced without restarting
  Module B.

This is also why `cloud-api` is a deliberately small, deliberately
stable jar. Every type a module can compile against lives there. Adding
to `cloud-api` is a controller release decision, not a module decision.

## How the registry prevents leakage

Each platform module loads in its own `URLClassLoader` whose parent is
the controller's classloader. Modules see `cloud-api` types through the
parent and their own classes through their own loader. The capability
registry caches `Class<?> → Proxy` mappings for dynamic-handle
resolution; this cache is **explicitly cleared** when a provider
deactivates (or rebinds with a manifest dropping the capability), so
neither cached `Class<?>` keys nor proxy classes pin the unloaded
classloader.

`ModuleClassLoaderTracker` wraps each loaded classloader in a
`PhantomReference` and emits four metrics so leak detection is
observable in production:

- `prexorcloud_module_classloader_tracked_total{moduleId}`
- `prexorcloud_module_classloader_collected_total{moduleId}`
- `prexorcloud_module_classloader_leaked{moduleId}` (counter)
- `prexorcloud_module_classloader_pending` (gauge)

`GET /api/v1/modules/platform/leaked-classloaders` returns pending leak
reports for the dashboard. `POST /api/v1/modules/platform/force-cleanup`
runs the tracker's forced-cleanup escalation. Both are gated on
`MODULES_MANAGE`. See [Observability](/operations/monitoring/).

## Versioning

Capability bindings carry a version string (the providing module's
version). Consumers can resolve a specific version range:

```java
CapabilityHandle<PlayerJourneyTracker> handle =
    ctx.capabilities()
       .resolve("prexor.player.journey", PlayerJourneyTracker.class,
                VersionRange.atLeast("1.2.0"));
```

If the active provider's version falls outside the range, the handle
behaves as if no provider were registered (`handle.get()` returns
`null`). When a matching version is installed, the handle binds. This
is the upgrade story: a consumer that needs a method introduced in
provider v1.2 can demand it without a hard install-time error.

## A small but complete example

```java
// In cloud-api
public interface NotifierService {
    void notify(String channel, String message);
}
```

```java
// In a provider module: discord-notifier
public final class DiscordNotifierModule implements PlatformModule {
    private DiscordClient client;

    @Override
    public Set<CapabilityBinding<?>> capabilities() {
        return Set.of(
            CapabilityBinding.of("prexor.notifier",
                                 NotifierService.class,
                                 () -> client)
        );
    }

    @Override
    public void onStart(ModuleContext ctx) {
        client = new DiscordClient(ctx.httpClient(),
                                   ctx.json(),
                                   loadConfig(ctx));
    }
    // ... onStop, onUnload elided
}
```

```java
// In a consumer module: crash-alerter
public final class CrashAlerterModule implements PlatformModule {
    private CapabilityHandle<NotifierService> notifier;

    @Override
    public void onStart(ModuleContext ctx) {
        notifier = ctx.capabilities().resolve("prexor.notifier",
                                               NotifierService.class);
        ctx.events().subscribe(InstanceCrashedEvent.class, this::onCrash);
    }

    private void onCrash(InstanceCrashedEvent e) {
        var n = notifier.get();
        if (n != null) n.notify("ops", "instance " + e.instanceId() + " crashed");
    }
}
```

Two modules. One contract. The consumer keeps working when the provider
is replaced, when its version bumps, when its implementation switches
from Discord to Slack. This is the abstraction the rest of the module
system is built on.

## Next up

- [Platform Modules](/concepts/modules/platform/) — the full
  controller-side contract.
- [Lifecycle](/concepts/modules/lifecycle/) — how capability dependencies
  drive `WAITING ↔ ACTIVE` transitions.
- [Daemon Modules](/concepts/modules/daemon/) — the node-local
  capability registry on the daemon side.
- [Events](/concepts/events/) — capability lifecycle events on the SSE
  bus.
