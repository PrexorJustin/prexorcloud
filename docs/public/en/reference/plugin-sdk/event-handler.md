---
title: EventHandler
description: Subscribing to cluster events from a plugin — fluent and direct subscription, filters, custom event types, and the unsubscribe contract.
---

A plugin subscribes to cluster events through
[`CloudPluginContext.events()`](/reference/plugin-sdk/plugin-context/),
which returns the same `EventBus` modules see. This page documents
the plugin-side patterns; the underlying contract is identical to the
[module-side EventBus](/reference/module-sdk/event-bus/).

## What you'll learn

- The `EventHandler<T>` functional interface.
- The fluent `on(...).filter(...).subscribe(...)` pattern.
- How subscriptions are torn down on plugin disable.

## API surface

### `EventHandler<T>`

```java
package me.prexorjustin.prexorcloud.api.event;

@FunctionalInterface
public interface EventHandler<T extends CloudEvent> {
    void handle(T event);
}
```

Lambda-friendly. Throw checked exceptions only by wrapping them — the
bus catches `RuntimeException` and logs at `WARN` to keep delivery
moving for other subscribers.

### Subscription methods

```java
<T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType);
<T extends CloudEvent> EventSubscription           subscribe(Class<T> eventType, EventHandler<T> handler);
EventSubscription                                  subscribeByType(String type, EventHandler<CustomCloudEvent> handler);
EventSubscription                                  subscribeAll(EventHandler<CloudEvent> handler);
void                                               publish(CloudEvent event);
```

### `EventSubscription`

```java
public interface EventSubscription {
    void unsubscribe();
}
```

Returned by every `subscribe*` call. Hold the handle if you need to
unsubscribe before plugin disable; the bus drops every subscription
owned by the plugin automatically on `onDisable`.

## Patterns

### Filter on the fluent builder

```java
ctx.events().on(PlayerConnectedEvent.class)
            .filter(e -> "lobby".equals(e.group()))
            .subscribe(this::onLobbyJoin);
```

### Direct subscribe + manual unsubscribe

```java
EventSubscription sub = ctx.events().subscribe(PlayerDisconnectedEvent.class, this::onLeave);
// later, e.g. in a /toggle command:
sub.unsubscribe();
```

### Custom event types

For custom event payloads not known at compile time:

```java
ctx.events().subscribeByType("CHAT:MESSAGE", e -> {
    String message = e.payload().get("message").asText();
    // ...
});
```

### Catch-all (rarely needed)

```java
ctx.events().subscribeAll(e -> {
    ctx.logger().fine("event " + e.getClass().getSimpleName());
});
```

Use sparingly — catch-all subscribers are invoked for every published
event in the JVM.

## Publishing

```java
ctx.events().publish(new ChatMessageEvent(player.uniqueId(), message));
```

Events published from a plugin propagate through the daemon → controller
bridge to the controller bus, where module subscribers see them. The
trip is single-hop: there's no plugin → plugin direct delivery
across instances.

## Threading

The `EventBus` is synchronous on the publishing thread. If you publish
from a Bukkit/Folia main-thread event handler, your subscribers run on
the same thread. To do work asynchronously, hand off via
`ctx.scheduler()`:

```java
ctx.events().on(PlayerConnectedEvent.class).subscribe(e -> {
    ctx.scheduler().runAsync(() -> persistVisit(e));
});
```

## Lifecycle contract

| When | What happens |
|---|---|
| Plugin `onEnable` | Subscriptions are registered with the bus, scoped to this plugin. |
| Plugin `onDisable` | The bus drops every subscription owned by the plugin. You don't have to call `unsubscribe()`. |
| Server shutdown | Plugin disable runs first; bus is torn down after all plugins have disabled. |

## Example

A plugin that tracks how often each player joins:

```java
@CloudPlugin(name = "join-counter", version = "1.0.0")
public final class JoinCounterPlugin extends CloudPluginBase {

    private final Map<UUID, Integer> counts = new ConcurrentHashMap<>();

    @Override
    public void onEnable(CloudPluginContext ctx) {
        ctx.events().on(PlayerConnectedEvent.class).subscribe(e -> {
            int total = counts.merge(e.uniqueId(), 1, Integer::sum);
            ctx.players().getPlayer(e.uniqueId()).ifPresent(p ->
                p.sendMessage("Welcome back! Visit #" + total));
        });
    }
}
```

## Next up

- [CloudPluginContext](/reference/plugin-sdk/plugin-context/) — where
  `events()` comes from.
- [Module-side EventBus](/reference/module-sdk/event-bus/) — same
  contract on the controller side.
