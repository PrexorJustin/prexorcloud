---
title: EventBus
description: Cluster-wide event subscription and publishing API shared by the Module SDK and the Plugin SDK, plus the first-class event type hierarchy.
---

`EventBus` is the pub/sub primitive shared between modules and plugins.
Modules access it through
[`ModuleContext.events()`](/reference/module-sdk/module-context/);
plugins through
[`CloudPluginContext.events()`](/reference/plugin-sdk/plugin-context/).
Both sides program against the same interface,
`me.prexorjustin.prexorcloud.api.event.EventBus`.

This page documents every method on the interface, the event type
hierarchy, the three concrete implementations and how their dispatch
differs, and the full table of first-class event records.

## Type hierarchy

```
CloudEvent (interface)
├── PlayerConnectedEvent, InstanceCrashedEvent, … (record, events sub-package)
└── CustomCloudEvent (record) — runtime-defined module/plugin events
```

Every event is a `CloudEvent`. The interface declares one method:

```java
public interface CloudEvent {
    String type();
}
```

`type()` returns a stable string identifier used for dynamic dispatch
and SSE streaming. First-class events return SCREAMING_SNAKE_CASE
(`"PLAYER_CONNECTED"`); custom events use the `"MODULE:ACTION"`
convention (`"CHAT:MESSAGE"`).

First-class events live in
`me.prexorjustin.prexorcloud.api.event.events` and are Java records.
Their components are public accessors (`event.group()`,
`event.exitCode()`). See [first-class events](#first-class-events) for
the full list.

### CustomCloudEvent

For events not known at compile time, publish a `CustomCloudEvent`:

```java
public record CustomCloudEvent(
        String type, String source, Map<String, Object> payload, Instant timestamp)
        implements CloudEvent { }
```

| Component | Type | Notes |
|---|---|---|
| `type` | `String` | Required. Non-null. Use `"MODULE:ACTION"` format. |
| `source` | `String` | Required. Non-null. Originator: instance ID, module name, etc. |
| `payload` | `Map<String, Object>` | Arbitrary key-value data. `null` is normalized to `Map.of()`. |
| `timestamp` | `Instant` | When created. `null` is normalized to `Instant.now()`. |

A two-argument convenience constructor defaults the timestamp:

```java
events.publish(new CustomCloudEvent(
        "CHAT:MESSAGE",
        "lobby-1",
        Map.of("player", "Steve", "text", "hi")));
```

Subscribe to custom events by their type string with
[`subscribeByType`](#subscribebytype), not by `Class`.

## Interface methods

### `on`

```java
<T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> eventType);
```

Begin a fluent subscription. Returns an
[`EventSubscriptionBuilder<T>`](#eventsubscriptionbuilder) you attach
filters to before subscribing. Recommended form.

| Parameter | Type | Description |
|---|---|---|
| `eventType` | `Class<T>` | The event class to subscribe to. |

```java
EventSubscription sub = events.on(PlayerConnectedEvent.class)
        .filter(e -> e.group().equals("lobby"))
        .subscribe(e -> LOG.info("{} joined lobby", e.name()));
```

### `subscribe`

```java
<T extends CloudEvent> EventSubscription subscribe(Class<T> eventType, EventHandler<T> handler);
```

Subscribe without a filter. Equivalent to `on(eventType).subscribe(handler)`.

| Parameter | Type | Description |
|---|---|---|
| `eventType` | `Class<T>` | The event class to subscribe to. |
| `handler` | `EventHandler<T>` | Callback invoked for each event. |

Returns an [`EventSubscription`](#eventsubscription) handle.

```java
EventSubscription sub = events.subscribe(InstanceCrashedEvent.class, e ->
        LOG.warn("instance {} crashed exit={} class={}",
                e.instanceId(), e.exitCode(), e.classification()));
sub.unsubscribe();   // stop receiving
```

### `subscribeByType`

```java
EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler);
```

Subscribe to `CustomCloudEvent` instances whose `type()` matches the
given string exactly. Use this for dynamic event types whose Java class
is not visible at compile time. The match is exact-string; there is no
prefix or wildcard matching.

| Parameter | Type | Description |
|---|---|---|
| `type` | `String` | Exact `type()` string, e.g. `"CHAT:MESSAGE"`. |
| `handler` | `EventHandler<CustomCloudEvent>` | Callback for each matching custom event. |

Only `CustomCloudEvent` instances are delivered here — first-class
events never match a `subscribeByType` registration even if their
`type()` string is identical.

```java
EventSubscription sub = events.subscribeByType("VOTIFIER:VOTE", e -> {
    String voter = (String) e.payload().get("username");
    LOG.info("vote from {}", voter);
});
```

### `subscribeAll`

```java
EventSubscription subscribeAll(EventHandler<CloudEvent> handler);
```

Catch-all. The handler receives every published event regardless of
type. Every publish iterates all catch-all handlers, so the cost is
paid on each event — register few.

| Parameter | Type | Description |
|---|---|---|
| `handler` | `EventHandler<CloudEvent>` | Callback invoked for every event. |

```java
EventSubscription sub = events.subscribeAll(e ->
        LOG.debug("event {}", e.type()));
```

### `publish`

```java
void publish(CloudEvent event);
```

Fan out an event to matching subscribers. Dispatch order within a
publish is: class handlers, then type-string handlers (only when the
event is a `CustomCloudEvent`), then catch-all handlers. Threading and
buffering depend on the implementation — see
[dispatch semantics](#dispatch-semantics).

| Parameter | Type | Description |
|---|---|---|
| `event` | `CloudEvent` | The event to publish. |

```java
events.publish(new GroupCreatedEvent("survival"));
```

## EventSubscriptionBuilder

Returned by [`on`](#on). Two methods, fluent.

```java
public interface EventSubscriptionBuilder<T extends CloudEvent> {
    EventSubscriptionBuilder<T> filter(Predicate<T> predicate);
    EventSubscription subscribe(EventHandler<T> handler);
}
```

### `filter`

```java
EventSubscriptionBuilder<T> filter(Predicate<T> predicate);
```

Attach a predicate. Only events for which the predicate returns `true`
reach the handler. Multiple `filter` calls are **ANDed** — all
predicates must pass.

```java
events.on(InstanceStateChangedEvent.class)
        .filter(e -> e.group().equals("survival"))
        .filter(e -> e.newState() == InstanceState.RUNNING)
        .subscribe(e -> LOG.info("{} is up", e.instanceId()));
```

### `subscribe`

```java
EventSubscription subscribe(EventHandler<T> handler);
```

Complete the subscription and start receiving events. Returns the
[`EventSubscription`](#eventsubscription) handle.

## EventHandler

```java
@FunctionalInterface
public interface EventHandler<T extends CloudEvent> {
    void handle(T event);
}
```

A functional interface — pass a lambda or method reference. Exceptions
thrown from `handle` are caught and logged by the bus; they do not
abort the publish or affect other handlers (see
[dispatch semantics](#dispatch-semantics)). There is no checked
exception in the signature; wrap checked exceptions yourself.

## EventSubscription

```java
public interface EventSubscription {
    void unsubscribe();
}
```

The handle returned by every `subscribe*` call. Call `unsubscribe()` to
remove the handler from the bus. Hold the handle for the lifetime you
want the subscription to live; on the daemon bus, dropping the last
subscriber for an event type also unregisters that type from the
controller (see [daemon forwarding](#daemon-forwarding)).

```java
EventSubscription sub = events.subscribe(NodeConnectedEvent.class, this::onNode);
// in onStop():
sub.unsubscribe();
```

## Dispatch semantics

There is one interface, three concrete implementations. They share the
dispatch order (class → type-string → catch-all) and the
catch-exceptions-and-log contract, but differ in threading.

| Implementation | Where | Threading | Handler exception |
|---|---|---|---|
| `controller.event.EventBus` | Controller in-process | Async — each handler forked on a virtual thread via `StructuredTaskScope`; `publish` returns before handlers run | Caught, logged at `ERROR` |
| `DaemonEventBus` | Daemon | Async — each handler dispatched on `Thread.startVirtualThread` | Caught, logged at `WARN` |
| `CloudEventBusImpl` | Plugin platform adapters (Paper/Velocity/Fabric/…) | Synchronous — handlers run on the publishing thread, in order | Caught, logged at `WARN` |

Consequences for handler code, all implementations:

- A handler throwing does not stop the publish or other handlers.
- No buffering or coalescing — there is no queue, retry, or replay.
- On the controller and daemon, do not assume ordering between handlers
  of one publish, and do not assume the handler has run when `publish`
  returns. On the plugin adapter, handlers run synchronously in
  registration order on the caller's thread.
- Keep handlers fast and non-blocking on the plugin adapter; a slow
  handler there blocks the publisher.

## Daemon forwarding

The daemon bus (`DaemonEventBus`) is local pub/sub plus controller
registration. When a daemon module subscribes to a
`Class<? extends CloudEvent>` and no other local subscriber for that
class exists yet, the bus sends an `EventSubscribe` message
([daemon-service.proto](/internals/protocol/daemon-service/)) naming the
fully-qualified class name. The controller-side forwarder then pushes
matching events back as `ModuleEvent` messages, which the daemon bus
deserializes (via the daemon's classloader) and re-publishes locally.

When the last local subscriber for a class unsubscribes, the daemon
sends `EventUnsubscribe` so the controller stops forwarding. On gRPC
reconnect the daemon re-sends `EventSubscribe` for every currently
subscribed class so a stream blip does not desync the forwarder.

Inbound `ModuleEvent` payloads whose class name is not on the daemon's
classpath, or does not implement `CloudEvent`, are logged at `WARN` and
dropped.

## First-class events

All in `me.prexorjustin.prexorcloud.api.event.events`. Each is a record;
the components listed are the public accessors. The `type()` column is
the value returned by `type()` (used for SSE and `subscribeByType` on
the controller bus).

### Player

| Record | `type()` | Components |
|---|---|---|
| `PlayerConnectedEvent` | `PLAYER_CONNECTED` | `UUID uuid, String name, String instanceId, String group` |
| `PlayerDisconnectedEvent` | `PLAYER_DISCONNECTED` | `UUID uuid, String name, String instanceId, String group` |
| `PlayerTransferEvent` | `PLAYER_TRANSFER` | `UUID uuid, String name, String fromInstanceId, String toInstanceId` |
| `PlayerJourneyEvent` | `PLAYER_JOURNEY` | `PlayerJourneyEntry entry` |

### Instance

| Record | `type()` | Components |
|---|---|---|
| `InstanceStateChangedEvent` | `INSTANCE_STATE_CHANGED` | `String instanceId, String group, String nodeId, InstanceState oldState, InstanceState newState` |
| `InstanceCrashedEvent` | `INSTANCE_CRASHED` | `String instanceId, String group, String nodeId, int exitCode, String classification, List<String> logTail, long uptimeMs` |
| `InstanceDrainingEvent` | `INSTANCE_DRAINING` | `String instanceId, String group, String nodeId` |
| `InstanceConsoleOutputEvent` | `INSTANCE_CONSOLE_OUTPUT` | `String instanceId, String line, long timestampMs` |
| `InstanceMetricsUpdatedEvent` | `INSTANCE_METRICS` | `String instanceId, String group, double tps1m, double tps5m, double tps15m, double msptAvg, long heapUsedMb, long heapMaxMb, long gcCollections, long gcTimeMs, int threadCount, int playerCount, int maxPlayers, int worldCount, long totalEntities, long totalChunks, List<WorldSnapshot> worlds, String serverVersion, int pluginCount` |

### Group

| Record | `type()` | Components |
|---|---|---|
| `GroupCreatedEvent` | `GROUP_CREATED` | `String groupName` |
| `GroupUpdatedEvent` | `GROUP_UPDATED` | `String groupName` |
| `GroupDeletedEvent` | `GROUP_DELETED` | `String groupName` |
| `GroupAggregatesUpdatedEvent` | `GROUP_AGGREGATES_UPDATED` | `String groupName, int runningInstances, int totalPlayers` |
| `GroupMaintenanceChangedEvent` | `GROUP_MAINTENANCE_CHANGED` | `String groupName, boolean maintenance, String message` |
| `GroupCrashLoopEvent` | `GROUP_CRASH_LOOP` | `String group, int crashCount, Instant windowStart` |

### Node

| Record | `type()` | Components |
|---|---|---|
| `NodeConnectedEvent` | `NODE_CONNECTED` | `String nodeId, String sessionId, Instant timestamp` |
| `NodeDisconnectedEvent` | `NODE_DISCONNECTED` | `String nodeId, String reason, Instant timestamp` |
| `NodeStatusUpdatedEvent` | `NODE_STATUS` | `String nodeId, double cpuUsage, long usedMemoryMb, long totalMemoryMb, Instant lastHeartbeatAt` |
| `NodeCacheStatusUpdatedEvent` | `NODE_CACHE_STATUS` | `String nodeId, long totalSizeBytes, Instant timestamp` |
| `NodeHeartbeatStaleEvent` | `NODE_HEARTBEAT_STALE` | `String nodeId, int missedPongs, Instant lastHeartbeatAt` |
| `NodeHeartbeatResumedEvent` | `NODE_HEARTBEAT_RESUMED` | `String nodeId, Instant lastHeartbeatAt` |
| `NodeDrainRequestedEvent` | `NODE_DRAIN_REQUESTED` | `String nodeId, boolean shutdownAfterDrain, int drainTimeoutSeconds, String kickMessage, Instant timestamp` |
| `NodeDrainCompletedEvent` | `NODE_DRAIN_COMPLETED` | `String nodeId, Instant timestamp` |

### Deployment

| Record | `type()` | Components |
|---|---|---|
| `DeploymentCreatedEvent` | `DEPLOYMENT_CREATED` | `String groupName, int revision, String strategy` |
| `DeploymentCompletedEvent` | `DEPLOYMENT_COMPLETED` | `String groupName, int revision, String outcome` |

### Module and capability

| Record | `type()` | Components |
|---|---|---|
| `ModuleLoadedEvent` | `MODULE_LOADED` | `String moduleName, boolean hasFrontend` |
| `ModuleUnloadedEvent` | `MODULE_UNLOADED` | `String moduleName` |
| `ModuleFrontendReloadedEvent` | `MODULE_FRONTEND_RELOADED` | `String moduleName, String contentHash` |
| `CapabilityRegisteredEvent` | `CAPABILITY_REGISTERED` | `String capabilityId, String version, String moduleId` |
| `CapabilityUnregisteredEvent` | `CAPABILITY_UNREGISTERED` | `String capabilityId, String moduleId` |
| `CapabilityProviderChangedEvent` | `CAPABILITY_PROVIDER_CHANGED` | `String capabilityId, String moduleId, String fromVersion, String toVersion` |

### Template, cluster, maintenance, choreography

| Record | `type()` | Components |
|---|---|---|
| `TemplateUpdatedEvent` | `TEMPLATE_UPDATED` | `String templateName, String oldHash, String newHash` |
| `ClusterConfigChangedEvent` | `CLUSTER_CONFIG_CHANGED` | `int version, int parentVersion, String mutator, String action` |
| `MaintenanceUpdatedEvent` | `MAINTENANCE_UPDATED` | `boolean globalEnabled, String message` |
| `ChoreographyOverlayActivatedEvent` | `CHOREOGRAPHY_OVERLAY_ACTIVATED` | `String eventName, String group, Instant activeUntil` |
| `ChoreographyOverlayDeactivatedEvent` | `CHOREOGRAPHY_OVERLAY_DEACTIVATED` | `String eventName, String group, String reason` |

## Worked example: session aggregator

A module service that records player sessions. The constructor takes its
dependency (constructor injection); the entrypoint's `onStart` calls
`register(context.events())`.

```java
public final class SessionAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(SessionAggregator.class);

    private final StatsRepository repo;

    public SessionAggregator(StatsRepository repo) {
        this.repo = repo;
    }

    public void register(EventBus events) {
        events.on(PlayerConnectedEvent.class)
                .subscribe(this::onConnected);
        events.on(PlayerDisconnectedEvent.class)
                .filter(e -> e.group().equals("survival"))
                .subscribe(this::onDisconnected);
    }

    private void onConnected(PlayerConnectedEvent event) {
        LOG.info("session start: {} on {}", event.uuid(), event.group());
        repo.recordJoin(event);
    }

    private void onDisconnected(PlayerDisconnectedEvent event) {
        repo.recordLeave(event);
    }
}
```

## See also

- [ModuleContext](/reference/module-sdk/module-context/) — `events()` in
  the module context.
- [Plugin SDK → EventHandler](/reference/plugin-sdk/event-handler/) — the
  same contract on the plugin side.
- [Concepts → Event flow](/concepts/events/) — how events cross the
  controller and daemon JVMs.
