---
title: EventBus
description: Cluster-wide event subscription and publishing API used by both the Module SDK and the Plugin SDK.
---

`EventBus` is the cluster-wide pub/sub primitive shared between modules
and plugins. Modules access it through
[`ModuleContext.events()`](/reference/module-sdk/module-context/#cross-cutting-primitives);
plugins through
[`CloudPluginContext.events()`](/reference/plugin-sdk/plugin-context/).
The same type-safe contract serves both.

## What you'll learn

- The fluent and direct subscription forms.
- How catch-all subscribers and custom event types work.
- The contract for forwarding to daemon-host modules.

## API surface

The interface lives at `me.prexorjustin.prexorcloud.api.event.EventBus`.

### Fluent subscription

```java
<T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType);
```

Returns a builder you can attach filters to before subscribing:

```java
events.on(PlayerConnectedEvent.class)
      .filter(e -> e.group().equals("lobby"))
      .subscribe(e -> log.info("{} joined lobby", e.name()));
```

### Direct subscription

```java
<T extends CloudEvent> EventSubscription subscribe(Class<T> eventType, EventHandler<T> handler);
```

When you don't need a filter:

```java
EventSubscription sub = events.subscribe(PlayerConnectedEvent.class, e -> {
    // ...
});
sub.unsubscribe();   // later
```

### Custom-type subscription

```java
EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler);
```

For `CustomCloudEvent` subtypes whose Java class isn't visible at
compile time (e.g. type strings shipped from another module). The
type matcher is exact-string.

### Catch-all

```java
EventSubscription subscribeAll(EventHandler<CloudEvent> handler);
```

Receives every event. Use sparingly — the more catch-alls, the more
work each publish does.

### Publish

```java
void publish(CloudEvent event);
```

Synchronous fan-out to matching subscribers. The bus does not buffer
or coalesce — your handler is invoked on the publishing thread.

## EventHandler

```java
@FunctionalInterface
public interface EventHandler<T extends CloudEvent> {
    void handle(T event);
}
```

Lambda-friendly; throw checked exceptions only by wrapping them — the
host catches `RuntimeException` and logs at `WARN` to keep the bus
moving.

## Daemon forwarding

When a daemon module subscribes to a controller-bus event type, the
daemon registers interest with the controller via the
`EventSubscribe` gRPC message
([daemon-service.proto](/internals/protocol/daemon-service/)). The
controller subscribes its own `EventBus` on first arrival and forwards
matching events to that daemon as `ModuleEvent` messages, which the
daemon-side bus re-publishes locally. Unknown class names are
answered with an `ErrorReport`.

## Example: stats aggregator

```java
public final class SessionAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(SessionAggregator.class);

    private final StatsRepository repo;

    public SessionAggregator(StatsRepository repo) {
        this.repo = repo;
    }

    public void register(EventBus events) {
        events.on(PlayerConnectedEvent.class)
              .subscribe(this::onPlayerConnected);
        events.on(PlayerDisconnectedEvent.class)
              .subscribe(this::onPlayerDisconnected);
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        LOG.info("session start: {} on {}", event.playerId(), event.group());
        repo.recordJoin(event);
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        repo.recordLeave(event);
    }
}
```

The constructor takes the repository as a parameter (constructor
injection); the entrypoint's `onStart` calls `register(context.events())`.

## Next up

- [ModuleContext](/reference/module-sdk/module-context/) — `events()`
  in context.
- [Plugin SDK → EventHandler](/reference/plugin-sdk/event-handler/) —
  same contract on the plugin side.
- [Concepts → Event flow](/concepts/events/) — how events
  cross controller and daemon JVMs.
