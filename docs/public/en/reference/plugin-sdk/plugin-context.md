---
title: CloudPluginContext
description: Plugin-side context handed to onEnable — exposes events(), players(), commands(), scheduler(), client(), and logger().
---

`CloudPluginContext` is the entry point a plugin receives in
`onEnable(CloudPluginContext ctx)`. It exposes the same shared
primitives modules see (`events()`) plus plugin-specific surfaces:
the local instance's metadata, the on-server player roster, the
command registry, and a Folia-safe scheduler.

## What you'll learn

- Every method on `CloudPluginContext`.
- How the plugin context relates to `ModuleContext`.
- How to read information about the instance the plugin is running on.

## API surface

The interface lives at
`me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext`.

### `self()`

```java
InstanceContext self();
```

Information about this plugin's own server instance — instance id,
group, port, node id, deployment revision. Use this to attribute
events back to the right instance when publishing to the cluster bus.

### `events()`

```java
EventBus events();
```

The same `EventBus` the Module SDK uses — see
[EventBus on the module side](/reference/module-sdk/event-bus/) for
the full surface. Events published from a plugin are visible to
modules on the controller, and vice versa.

### `commands()`

```java
CloudCommandRegistry commands();
```

Slash-command registration. Commands are forwarded to the platform's
native command system — Brigadier on Paper, equivalent on Velocity /
Bungee. See
[Players + Commands](/reference/plugin-sdk/players-and-commands/) for
the builder and annotation paths.

### `players()`

```java
PlayerManager players();
```

Access to players currently online on this instance. See
[Players + Commands](/reference/plugin-sdk/players-and-commands/).

### `scheduler()`

```java
PluginScheduler scheduler();
```

Platform-agnostic, Folia-safe task scheduler. Use this for any work
that must run on the main thread, on a delay, or at a fixed rate —
calling Bukkit's scheduler directly is a footgun on Folia.

### `client()`

```java
CloudClient client();
```

Low-level cloud communication client. Most plugins don't touch this
directly; it's exposed for the rare case you need to drive a custom
gRPC bridge or replay raw events.

### `logger()`

```java
java.util.logging.Logger logger();
```

JUL logger for the plugin. Plugins use JUL because it's the only
logging framework guaranteed to work the same way on Bukkit and
Velocity. Modules — which run inside controller / daemon JVMs — use
SLF4J instead.

## Lifecycle

```java
public abstract class CloudPluginBase {
    public abstract void onEnable(CloudPluginContext ctx);
    public void          onDisable() {}
    public void          onReload(CloudPluginContext ctx) {}

    protected final <T> T adapt(Class<T> type);
    protected final <T> T adapt(Class<T> type, Class<?> container);
    protected final VersionDispatcher versions();
}
```

`onEnable` is called once per server startup; `onDisable` is best-effort
on shutdown. `onReload` is invoked when an operator runs a reload
command — you don't have to override it.

`adapt(...)` is the gateway to `@ForVersion` dispatch — see
[@CloudPlugin Annotation](/reference/plugin-sdk/cloudplugin-annotation/).

## Example

A plugin that broadcasts a message every minute and logs joining
players, using constructor-injected services:

```java
@CloudPlugin(name = "heartbeat", version = "1.0.0")
public final class HeartbeatPlugin extends CloudPluginBase {

    private HeartbeatService service;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.service = new HeartbeatService(
                ctx.players(),
                ctx.logger());

        ctx.scheduler().runAtFixedRate(
                Duration.ofSeconds(0),
                Duration.ofMinutes(1),
                service::broadcast);

        ctx.events().on(PlayerConnectedEvent.class)
                    .subscribe(service::onConnect);

        ctx.logger().info("heartbeat enabled on instance " + ctx.self().instanceId());
    }
}
```

`HeartbeatService` takes `PlayerManager` and `Logger` as constructor
arguments — same constructor-injection rule as the Module SDK.

## Next up

- [EventHandler](/reference/plugin-sdk/event-handler/) — subscribing
  details.
- [Players + Commands](/reference/plugin-sdk/players-and-commands/) —
  the player and command APIs.
- [@CloudPlugin Annotation](/reference/plugin-sdk/cloudplugin-annotation/)
  — `@CloudPlugin` and `@ForVersion`.
