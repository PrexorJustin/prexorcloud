---
title: Plugin SDK
description: Java SDK reference for standalone @CloudPlugin jars — single-platform Minecraft / proxy plugins that drop into a server's plugins/ folder and connect to the controller, plus the supported-platform and reference matrix.
---

The Plugin SDK is the Java API for the standalone `@CloudPlugin` path. A
plugin is one shaded jar carrying a `@CloudPlugin` annotation that drops
directly into a server's `plugins/` folder and connects to the controller
through `cloud-api`. All SDK types live under
`me.prexorjustin.prexorcloud.api.plugin` and
`me.prexorjustin.prexorcloud.api.client` in the `cloud-api` artifact.

A plugin is not a [module](/reference/module-sdk/). It has no `module.yaml`,
no frontend, and no per-platform variants. Pick a plugin when you need
in-game or in-proxy behaviour on one platform. Pick a module when you need
cluster-wide state, REST endpoints, dashboard UI, or coordination across
nodes. The full decision guide is in
[Concepts → Plugins vs modules](/concepts/plugins/).

## What you'll learn

- The two integration paths the SDK covers, and which platforms each
  supports.
- The single-class shape of a `@CloudPlugin`.
- The `@ForVersion` / `VersionDispatcher` pattern for serving multiple
  Minecraft versions from one jar.
- The reference matrix: where every public type is documented.

## The two paths

The SDK covers two ways code reaches the controller. They share the
`cloud-api` artifact but differ in what they expose.

### Path A — authored `@CloudPlugin` jars

You write a `CloudPluginBase` subclass annotated with `@CloudPlugin`. The
`cloud-api` annotation processor generates the platform descriptor and a
bridge class that boots your plugin into the host's lifecycle and hands it a
[`CloudPluginContext`](#cloudplugincontext). This is the full SDK surface:
events, players, commands, scheduler, and the low-level client.

`prexorctl plugin new` scaffolds this path. It supports five platforms:

| `--platform` | Host | Descriptor generated |
|---|---|---|
| `paper` | Paper / Spigot-API server | `plugin.yml` |
| `spigot` | Spigot server | `plugin.yml` |
| `folia` | Folia server | `plugin.yml` |
| `velocity` | Velocity proxy | `velocity-plugin.json` |
| `bungeecord` | BungeeCord / Waterfall proxy | `bungee.yml` |

### Path B — first-party telemetry integrations

For hosts that do not run Bukkit or Velocity plugins, PrexorCloud ships
first-party integrations that register the process with the controller and
report player join/leave plus a metrics snapshot. They use the
platform-agnostic `ServerControllerClient` (server side) or
`AbstractProxyCloudPlugin` (proxy side) directly over each host's native
event API — they are not `@CloudPlugin` jars and do not expose
`CloudPluginContext`. You do not author these; they are part of the cloud's
default install.

| Integration | Host | Entry point | Edition reported |
|---|---|---|---|
| Fabric server mod | Fabric dedicated server | `PrexorCloudFabric` (`DedicatedServerModInitializer`) | java |
| NeoForge server mod | NeoForge dedicated server | `PrexorCloudNeoForge` (`@Mod("prexorcloud")`) | java |
| Geyser sidecar | Geyser extension | `PrexorCloudGeyser` (`Extension`) | bedrock |

The Geyser sidecar registers the Geyser process with the controller as a
proxy instance and reports every Bedrock session as `edition=bedrock` —
authoritative even when Floodgate is not in use. Geyser is a Bedrock↔Java
protocol translator, not a server-list proxy, so it does not route by
server list.

All Path B integrations gate on `CLOUD_INSTANCE_ID`: a host launched
without it (not managed by the cloud) is detected and left untouched.

## Supported platforms

| Platform | Kind | Path | SDK surface |
|---|---|---|---|
| Paper | server | A | full `CloudPluginContext` |
| Spigot | server | A | full `CloudPluginContext` |
| Folia | server | A | full `CloudPluginContext` (Folia-safe scheduler) |
| Velocity | proxy | A | full `CloudPluginContext` |
| BungeeCord | proxy | A | full `CloudPluginContext` |
| Fabric | server | B | telemetry only (no `CloudPluginContext`) |
| NeoForge | server | B | telemetry only (no `CloudPluginContext`) |
| Geyser | proxy sidecar | B | telemetry only, `edition=bedrock` |

## Hello-world plugin (Path A)

```java
package me.prexorjustin.prexorcloud.plugins.welcome;

import me.prexorjustin.prexorcloud.api.event.events.PlayerConnectedEvent;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;

@CloudPlugin(
    name = "welcome",
    version = "1.0.0",
    authors = {"PrexorCloud"})
public final class WelcomePlugin extends CloudPluginBase {

    @Override
    public void onEnable(CloudPluginContext ctx) {
        ctx.events().on(PlayerConnectedEvent.class).subscribe(e ->
            ctx.players().getPlayer(e.uuid())
               .ifPresent(p -> p.sendMessage("Welcome, " + p.name() + "!")));
        ctx.logger().info("welcome plugin enabled");
    }
}
```

Scaffold and build it:

```bash
prexorctl plugin new welcome --platform paper --mc-version 1.21
cd java && ./gradlew :cloud-plugin:cloud-plugin-welcome:shadowJar
```

```
→ new plugin: welcome
  platform  paper (prexorcloud.plugin-paper)
  package   me.prexorjustin.prexorcloud.plugins.welcome
  dest      java/cloud-plugin/cloud-plugin-welcome
  files     3
  ✓ patched settings.gradle.kts (+1 include)

next:
  cd java && ./gradlew :cloud-plugin:cloud-plugin-welcome:shadowJar
```

Drop the shaded jar from `build/libs/` into a server's `plugins/` folder.
The annotation processor generates the right platform descriptor
(`plugin.yml` for Paper / Spigot / Folia, `velocity-plugin.json` for
Velocity, `bungee.yml` for BungeeCord) plus a bridge class that boots the
plugin into the host lifecycle.

## The `@CloudPlugin` contract

`@CloudPlugin`
(`me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin`,
`@Target(TYPE)`, `@Retention(RUNTIME)`) marks a `CloudPluginBase` subclass.

| Element | Type | Default | Meaning |
|---|---|---|---|
| `name()` | `String` | required | Plugin name written into the descriptor. |
| `version()` | `String` | required | Plugin version. |
| `description()` | `String` | `""` | Descriptor description. |
| `authors()` | `String[]` | `{}` | Author list. |
| `dependencies()` | `String[]` | `{}` | Hard deps loaded before this plugin. `PrexorCloud` is added automatically. |
| `softDependencies()` | `String[]` | `{}` | Soft deps loaded before this plugin if present. |
| `apiVersion()` | `String` | `"1.21"` | Bukkit/Paper `api-version`. Ignored on non-Bukkit platforms. When `@ForVersion` annotations are present the processor uses whichever is lower: this value or the lowest inferred `@ForVersion(min=...)`. |

`CloudPluginBase` is the platform-agnostic base class (it does not extend
`JavaPlugin`). The lifecycle hooks:

| Method | Signature | When |
|---|---|---|
| `onEnable` | `abstract void onEnable(CloudPluginContext ctx)` | On plugin enable. Required. |
| `onDisable` | `void onDisable()` | On plugin disable. Optional; default no-op. |
| `onReload` | `void onReload(CloudPluginContext ctx)` | On reload. Optional; default no-op. |
| `adapt` | `protected <T> T adapt(Class<T> type)` | Resolve the best `@ForVersion` nested class of `type`. |
| `adapt` | `protected <T> T adapt(Class<T> type, Class<?> container)` | Resolve the best `@ForVersion` nested class of `container` implementing `type`. |
| `versions` | `protected VersionDispatcher versions()` | The live `VersionDispatcher` for ad-hoc checks. |

`adapt(...)` throws `IllegalStateException` if called before the generated
bridge has initialized the dispatcher (it is ready by `onEnable`).

## `CloudPluginContext`

The handle passed to `onEnable` / `onReload`. Entry point to every Cloud
API.

| Accessor | Returns | Page |
|---|---|---|
| `self()` | `InstanceContext` | This plugin's own instance (id, group, node). |
| `events()` | `EventBus` | Cluster event subscription. |
| `commands()` | `CloudCommandRegistry` | Command registration (builder + annotation). |
| `players()` | `PlayerManager` | Players online on this instance. |
| `scheduler()` | `PluginScheduler` | Folia-safe task scheduler. |
| `client()` | `CloudClient` | Low-level cloud communication client. |
| `logger()` | `java.util.logging.Logger` | JUL logger. |

`PlayerManager` exposes `Optional<CloudPlayer> getPlayer(UUID)`,
`Optional<CloudPlayer> getPlayer(String name)`,
`Collection<CloudPlayer> onlinePlayers()`, and `int onlineCount()`. A
`CloudPlayer` carries `uniqueId()`, `name()`, `currentInstanceId()`,
`currentGroup()`, `sendMessage(String)`, `kick(String)`, and the transfer
methods `CompletableFuture<Boolean> transfer(String targetGroup)` and
`CompletableFuture<Boolean> transferTo(String targetInstanceId)`.

## Subscribing to events

The `EventBus` from `ctx.events()` offers a fluent builder and a direct
form:

```java
// Fluent: filter before subscribing.
ctx.events().on(PlayerConnectedEvent.class)
    .filter(e -> e.group().equals("lobby"))
    .subscribe(e -> ctx.logger().info(e.name() + " joined lobby"));

// Direct: returns a handle for cancellation.
EventSubscription sub =
    ctx.events().subscribe(PlayerConnectedEvent.class, e -> { /* ... */ });
sub.unsubscribe();
```

`PlayerConnectedEvent` is a record with accessors `uuid()`, `name()`,
`instanceId()`, `group()`, and `type()` returning `"PLAYER_CONNECTED"`.
Subscribe in `onEnable`; the bus tracks subscriptions per plugin and
cancels them on disable.

## Versioning across Minecraft releases

A single jar can serve multiple Minecraft versions through `@ForVersion`
(`me.prexorjustin.prexorcloud.api.client.version.ForVersion`):

```java
public interface WelcomeHandler {

    @ForVersion(min = "1.21")
    class Modern implements WelcomeHandler { /* 1.21+ APIs */ }

    @ForVersion(min = "1.17", max = "1.20")
    class Legacy implements WelcomeHandler { /* 1.17–1.20 APIs */ }

    @ForVersion(fallback = true)
    class Default implements WelcomeHandler { /* unknown future versions */ }
}

WelcomeHandler handler = adapt(WelcomeHandler.class);
```

`@ForVersion` elements: `min()` (inclusive, default `""`), `max()`
(inclusive, default `""` = unbounded), `fallback()` (default `false`). When
`fallback = true`, `min` and `max` are ignored; at most one fallback per
container.

`adapt(...)` delegates to `VersionDispatcher`, which:

1. collects every `@ForVersion` nested class whose range covers the running
   server;
2. picks the one with the highest `min` (greedy best-fit);
3. falls back to the `@ForVersion(fallback = true)` class if no range
   matches;
4. throws `UnsupportedOperationException` listing covered ranges if neither
   exists.

Every decision is logged at `FINE` via JUL. `VersionDispatcher` also offers
`major()`, `minor()`, `versionString()`, `atLeast(String)`,
`atMost(String)`, and `matches(String min, String max)` for ad-hoc checks
via `versions()`.

## Reference matrix

| Page | Surface |
|---|---|
| [CloudPluginContext](/reference/plugin-sdk/plugin-context/) | Top-level handle — `self`, `events`, `commands`, `players`, `scheduler`, `client`, `logger`. |
| [Events](/reference/plugin-sdk/event-handler/) | `EventBus`, `EventSubscriptionBuilder`, the event catalogue. |
| [Players and commands](/reference/plugin-sdk/players-and-commands/) | `PlayerManager`, `CloudPlayer`, the command registry (builder + annotation paths). |
| [@CloudPlugin annotation](/reference/plugin-sdk/cloudplugin-annotation/) | `@CloudPlugin`, `@ForVersion`, `VersionDispatcher`. |

## Conventions

- Logging: `java.util.logging.Logger` via `ctx.logger()`. JUL is the lowest
  common denominator across Bukkit and Velocity; modules use SLF4J, plugins
  use JUL. Path B telemetry mods log through SLF4J under the `PrexorCloud`
  logger name, matching their host.
- Threading: never call platform APIs off the main thread (Folia excepted).
  Use `ctx.scheduler()` to bounce work back; it is Folia-safe.
- Events: subscribe in `onEnable`. Unsubscribe is automatic on disable —
  the bus tracks subscriptions per plugin.
- Environment: managed JVMs receive `CLOUD_*` variables
  (`CLOUD_INSTANCE_ID`, `CLOUD_GROUP`, `CLOUD_NODE_ID`,
  `CLOUD_CONTROLLER_HOST`, `CLOUD_CONTROLLER_PORT`, `CLOUD_PLUGIN_TOKEN`).
  `PluginEnv.isCloudManaged()` returns `false` when `CLOUD_INSTANCE_ID` is
  unset; Path B integrations skip registration in that case.

## Next up

- [CloudPluginContext](/reference/plugin-sdk/plugin-context/) — start here.
- [@CloudPlugin annotation](/reference/plugin-sdk/cloudplugin-annotation/) —
  annotation reference.
- [Concepts → Plugins vs modules](/concepts/plugins/) — when to pick a
  plugin over a module.
