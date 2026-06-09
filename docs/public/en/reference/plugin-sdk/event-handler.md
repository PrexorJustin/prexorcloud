---
title: EventHandler
description: Subscribing to cluster events from a plugin — the EventBus surface, fluent and direct subscription, filters, custom event types, and which events each platform actually delivers.
---

A plugin subscribes to cluster events through
[`CloudPluginContext.events()`](/reference/plugin-sdk/plugin-context/),
which returns an `EventBus`. The interface mirrors the
[module-side EventBus](/reference/module-sdk/event-bus/), but the plugin-side
bus is a separate, in-JVM instance — it does not bridge to the controller bus.
This page documents the exact API surface and which events each platform
publishes to it.

## Platform support

The plugin SDK — `@CloudPlugin`, `CloudPluginContext`, and `EventBus` — exists
only on the **Bukkit family** (Spigot, Paper, Folia) and the **proxies**
(Velocity, BungeeCord).

`me.prexorjustin.prexorcloud.server.fabric.PrexorCloudFabric` and
`me.prexorjustin.prexorcloud.server.neoforge.PrexorCloudNeoForge` are thin
mods. They register with the controller and report player join/leave and
metrics over the platform's native event API (`ServerPlayConnectionEvents` on
Fabric, `NeoForge.EVENT_BUS` on NeoForge), but they do **not** create a
`CloudApiProvider`, expose a `CloudPluginContext`, or run an `EventBus`. There
is no `events()` surface to subscribe to on Fabric or NeoForge. Write game
logic for those platforms against the mod loader's own event API.

| Platform | `EventBus` available | Built-in events on the bus |
|---|---|---|
| Paper / Spigot / Folia | yes | none (see [Events delivered to the bus](#events-delivered-to-the-bus)) |
| Velocity | yes | `PlayerConnectedEvent`, `PlayerDisconnectedEvent` |
| BungeeCord | yes | `PlayerConnectedEvent`, `PlayerDisconnectedEvent` |
| Fabric | no | — |
| NeoForge | no | — |

The rest of this page applies to the platforms with an `EventBus`.

## What you'll learn

- The `EventHandler<T>` functional interface and the `EventBus` methods.
- The fluent `on(...).filter(...).subscribe(...)` pattern.
- `CloudEvent`, the built-in event records, and `CustomCloudEvent`.
- Exactly which events reach the bus on each platform — and which do not.
- The exception, threading, and lifecycle contracts.

## API surface

All types are in `me.prexorjustin.prexorcloud.api.event`.

### `EventHandler<T>`

```java
@FunctionalInterface
public interface EventHandler<T extends CloudEvent> {
    void handle(T event);
}
```

Lambda-friendly. The bus catches every `Exception` your handler throws (checked
or unchecked), logs it at `WARN` with the event class name, and continues
delivering to the remaining subscribers. A throwing handler never breaks
delivery for others.

### `CloudEvent`

```java
public interface CloudEvent {
    String type();
}
```

Base type for every event. `type()` is the dispatch key used for SSE streaming
and for `subscribeByType`. Built-in events use `SCREAMING_SNAKE_CASE`
(`"PLAYER_CONNECTED"`); custom events use `"MODULE:ACTION"`
(`"CHAT:MESSAGE"`).

### `EventBus`

```java
<T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType);
<T extends CloudEvent> EventSubscription           subscribe(Class<T> eventType, EventHandler<T> handler);
EventSubscription                                  subscribeByType(String type, EventHandler<CustomCloudEvent> handler);
EventSubscription                                  subscribeAll(EventHandler<CloudEvent> handler);
void                                               publish(CloudEvent event);
```

| Method | Returns | Behavior |
|---|---|---|
| `on(Class<T>)` | `EventSubscriptionBuilder<T>` | Starts a fluent builder. No subscription is registered until you call `.subscribe(...)` on the builder. |
| `subscribe(Class<T>, EventHandler<T>)` | `EventSubscription` | Subscribe without a filter. Exactly `on(eventType).subscribe(handler)`. Dispatch is by **exact class** — a subscription to a supertype receives nothing. |
| `subscribeByType(String, EventHandler<CustomCloudEvent>)` | `EventSubscription` | Subscribe to `CustomCloudEvent` instances whose `type()` equals the string. Only `CustomCloudEvent` is matched this way; built-in event records are not. |
| `subscribeAll(EventHandler<CloudEvent>)` | `EventSubscription` | Catch-all. Invoked for every event published to this bus, of any type. |
| `publish(CloudEvent)` | `void` | Dispatch synchronously on the calling thread to exact-class subscribers, then (for a `CustomCloudEvent`) to `subscribeByType` subscribers, then to catch-all subscribers. Local to this JVM only. |

`subscribe` is dispatched by `event.getClass()`, so a `PlayerConnectedEvent`
reaches subscribers registered with `PlayerConnectedEvent.class` and never
subscribers registered with `CloudEvent.class` (use `subscribeAll` for those).

### `EventSubscriptionBuilder<T>`

```java
EventSubscriptionBuilder<T> filter(Predicate<T> predicate);
EventSubscription           subscribe(EventHandler<T> handler);
```

`filter` attaches a predicate; only events for which it returns `true` reach
the handler. Multiple `filter` calls are **ANDed** — every predicate must pass.
`subscribe` finalizes the registration and returns the handle.

### `EventSubscription`

```java
public interface EventSubscription {
    void unsubscribe();
}
```

Returned by every `subscribe*` call. `unsubscribe()` removes the handler from
the bus; the event is also marked cancelled, so an in-flight `publish` already
iterating the handler list will skip it. There is **no** automatic teardown —
see [Lifecycle contract](#lifecycle-contract).

## Events delivered to the bus

The plugin `EventBus` is fed by exactly two sources:

1. **Proxy player events.** On Velocity and BungeeCord, the proxy's player
   listener publishes a `PlayerConnectedEvent` on post-login and a
   `PlayerDisconnectedEvent` on disconnect. These are the only built-in events
   that the SDK puts on the bus, and they appear **only on proxies**.
2. **Anything you publish.** Events you pass to `publish(...)` reach this same
   in-JVM bus.

On Bukkit-family servers (Paper, Spigot, Folia) the cloud plugin reports player
join/leave directly to the controller and **does not** publish
`PlayerConnectedEvent`/`PlayerDisconnectedEvent` to the bus. A server-side
plugin that subscribes to `PlayerConnectedEvent` receives nothing from the SDK
— hook the platform's own join event (e.g. Bukkit `PlayerJoinEvent`) for
per-server joins, and run network-wide join logic in a **proxy** plugin.

The controller event stream that backs `ctx.cluster()` (instance state, group
changes, metrics) updates the local state cache. It is **not** republished onto
the plugin `EventBus`. To observe cluster state, read the cache through the
cluster view, not through event subscriptions.

### `PlayerConnectedEvent` / `PlayerDisconnectedEvent`

```java
public record PlayerConnectedEvent(UUID uuid, String name, String instanceId, String group)
        implements CloudEvent { /* type() == "PLAYER_CONNECTED" */ }

public record PlayerDisconnectedEvent(UUID uuid, String name, String instanceId, String group)
        implements CloudEvent { /* type() == "PLAYER_DISCONNECTED" */ }
```

Accessors are `uuid()`, `name()`, `instanceId()`, and `group()`. There is no
`uniqueId()` accessor. On a proxy, `instanceId()` and `group()` are the
**proxy's** instance and group (`PluginEnv.instanceId()` /
`PluginEnv.group()`), not the backend the player landed on.

### `CustomCloudEvent`

```java
public record CustomCloudEvent(String type, String source, Map<String, Object> payload, Instant timestamp)
        implements CloudEvent {

    public CustomCloudEvent(String type, String source, Map<String, Object> payload); // timestamp = now()
}
```

| Field | Type | Notes |
|---|---|---|
| `type()` | `String` | `"MODULE:ACTION"` convention, e.g. `"CHAT:MESSAGE"`. Required (non-null). |
| `source()` | `String` | Originator — instance ID, plugin name, etc. Required (non-null). |
| `payload()` | `Map<String, Object>` | Arbitrary data. Defaults to `Map.of()` if `null`. This is a plain map — read values with `payload().get(key)`, not a JSON tree. |
| `timestamp()` | `Instant` | Defaults to `Instant.now()` if `null`. |

## Patterns

### Filter on the fluent builder

```java
ctx.events().on(PlayerConnectedEvent.class)
            .filter(e -> "lobby".equals(e.group()))
            .subscribe(this::onLobbyJoin);
```

### Multiple ANDed filters

```java
ctx.events().on(PlayerConnectedEvent.class)
            .filter(e -> "survival".equals(e.group()))
            .filter(e -> e.name().startsWith("admin_"))
            .subscribe(e -> ctx.logger().info("admin " + e.name() + " joined survival"));
```

Both predicates must pass for the handler to run.

### Direct subscribe and manual unsubscribe

```java
EventSubscription sub = ctx.events().subscribe(PlayerDisconnectedEvent.class, this::onLeave);
// later, e.g. in a /toggle command:
sub.unsubscribe();
```

### Custom event types

For payloads not known at compile time, subscribe by the type string and read
the map:

```java
ctx.events().subscribeByType("CHAT:MESSAGE", e -> {
    Object message = e.payload().get("message");
    if (message != null) {
        ctx.logger().info(e.source() + ": " + message);
    }
});
```

`subscribeByType` matches `CustomCloudEvent` only. Publishing a matching event:

```java
ctx.events().publish(new CustomCloudEvent(
        "CHAT:MESSAGE",
        ctx.self().instanceId(),
        Map.of("message", "hello", "channel", "global")));
```

### Catch-all

```java
ctx.events().subscribeAll(e -> ctx.logger().fine("event " + e.type()));
```

Invoked for every event published to this bus. Use sparingly; it runs on the
publishing thread for every `publish`.

## Publishing

```java
ctx.events().publish(new CustomCloudEvent("VOTIFIER:VOTE", ctx.self().instanceId(), Map.of("player", name)));
```

`publish` dispatches synchronously, in this order, on the calling thread:

1. Exact-class subscribers (`on` / `subscribe`).
2. For a `CustomCloudEvent`, `subscribeByType` subscribers whose string equals
   `event.type()`.
3. Catch-all subscribers (`subscribeAll`).

Delivery is **local to the publishing JVM**. There is no plugin → controller
or plugin → plugin propagation across instances; a `publish` on one server is
not seen by a plugin on another. To send data across the cluster, use the
controller client or a [module](/reference/module-sdk/event-bus/).

## Threading

The bus is synchronous: subscribers run on whichever thread called `publish`.
The proxy `PlayerConnectedEvent`/`PlayerDisconnectedEvent` events are published
from the proxy's network event thread, so your handler runs there. To move work
off that thread, hand off via the scheduler:

```java
ctx.events().on(PlayerConnectedEvent.class).subscribe(e ->
        ctx.scheduler().runAsync(() -> persistVisit(e)));
```

See [`PluginScheduler`](/reference/plugin-sdk/plugin-context/) for the
Folia-safe scheduling contract.

## Lifecycle contract

The `EventBus` is a single instance per server JVM, created when the cloud
plugin enables and shared by every cloud plugin on that instance. It does **not**
scope or track subscriptions by plugin.

| When | What happens |
|---|---|
| Plugin `onEnable` | You register subscriptions explicitly. Nothing is registered for you. |
| Plugin `onDisable` | Nothing is unsubscribed automatically. `onDisable` stops the cloud API's state cache; the bus's handler maps are untouched. Subscriptions you registered keep their handler references alive. |
| Server shutdown | The JVM exits and the bus is discarded with it. |

Hold the `EventSubscription` from each `subscribe*` call and `unsubscribe()` in
your `onDisable` (or whenever the handler is no longer needed). Leaking
subscriptions across a plugin reload keeps stale handlers — and the objects
they capture — registered on the shared bus.

```java
@CloudPlugin(name = "join-counter", version = "1.0.0")
public final class JoinCounterPlugin extends CloudPluginBase {

    private EventSubscription sub;
    private final Map<UUID, Integer> counts = new ConcurrentHashMap<>();

    @Override
    public void onEnable(CloudPluginContext ctx) {
        // Proxy-only: PlayerConnectedEvent is published by the Velocity/BungeeCord listener.
        sub = ctx.events().subscribe(PlayerConnectedEvent.class, e -> {
            int total = counts.merge(e.uuid(), 1, Integer::sum);
            ctx.players().getPlayer(e.uuid())
               .ifPresent(p -> p.sendMessage("Welcome back! Visit #" + total));
        });
    }

    @Override
    public void onDisable() {
        if (sub != null) {
            sub.unsubscribe();
        }
    }
}
```

## Next up

- [CloudPluginContext](/reference/plugin-sdk/plugin-context/) — where
  `events()`, `scheduler()`, and `players()` come from.
- [@CloudPlugin annotation](/reference/plugin-sdk/cloudplugin-annotation/) —
  declaring the plugin and the generated bridge.
- [Module-side EventBus](/reference/module-sdk/event-bus/) — the controller-side
  bus, where cross-cluster events live.
