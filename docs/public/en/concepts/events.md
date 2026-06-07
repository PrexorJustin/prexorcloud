---
title: Events
description: The EventBus contract, the typed cloud events, the SSE event stream with sequence-based replay, and how to subscribe from daemon-host modules and plugins.
---

PrexorCloud has one in-process event bus inside the Controller. Everything that changes cluster state — a node connecting, an instance crashing, a player joining — is published to that bus as a typed `CloudEvent`. Subscribers fall into three groups:

- **Controller-internal code** reacts to events directly (Redis bridge, crash-loop detector, cluster state).
- **The dashboard** receives every event over a Server-Sent Events (SSE) stream with replay.
- **Modules and Plugins** subscribe through the same `EventBus` interface. Daemon-host modules register interest over the daemon protocol; the Controller forwards matching events back to them.

This page covers the bus contract, every built-in event type, the SSE stream and its replay semantics, and the registration paths for daemon-host code.

## The EventBus contract

The interface is `me.prexorjustin.prexorcloud.api.event.EventBus` in `cloud-api`. The same contract is used by Plugins (via `CloudPluginContext.events()`), by Modules (via `ModuleContext.events()`), and by Controller-internal code. Two in-tree implementations honour it: the Controller bus (`controller/event/EventBus.java`) and the plugin/module-adapter bus (`cloud-plugins/internal/.../CloudEventBusImpl.java`).

| Method | Purpose |
| --- | --- |
| `<T extends CloudEvent> EventSubscriptionBuilder<T> on(Class<T> type)` | Start a fluent subscription you can attach filters to before subscribing. |
| `<T extends CloudEvent> EventSubscription subscribe(Class<T> type, EventHandler<T> handler)` | Subscribe to a typed event with no filter. |
| `EventSubscription subscribeByType(String type, EventHandler<CustomCloudEvent> handler)` | Subscribe by the event's `type()` string. Only `CustomCloudEvent` instances are delivered. |
| `EventSubscription subscribeAll(EventHandler<CloudEvent> handler)` | Catch-all: receive every event regardless of type. |
| `void publish(CloudEvent event)` | Publish an event to all matching subscribers. |

Every subscribe call returns an `EventSubscription`. Hold it and call `unsubscribe()` to stop receiving events. Subscriptions are not garbage-collected for you — a long-lived subscription leaks if you drop the handle.

### CloudEvent

`CloudEvent` is the base interface. It has a single method:

```java
String type();
```

The `type()` string is the dispatch key for `subscribeByType` and the SSE `type` field. Built-in events return a fixed `SCREAMING_SNAKE_CASE` string (for example `PLAYER_CONNECTED`). Custom events use `MODULE:ACTION` format (for example `CHAT:MESSAGE`).

First-class events are Java records in `cloud-api`'s `api.event.events` package. They are immutable and serialize cleanly to JSON.

### EventHandler

`EventHandler<T>` is a functional interface with one method:

```java
void handle(T event);
```

You pass it as a lambda to any of the subscribe methods.

### Fluent subscription

`on(...)` returns an `EventSubscriptionBuilder<T>`. Attach predicate filters, then call `subscribe`:

```java
EventSubscription sub = events.on(PlayerConnectedEvent.class)
        .filter(e -> e.group().equals("lobby"))
        .filter(e -> e.name().startsWith("VIP_"))
        .subscribe(e -> log.info("{} joined lobby", e.name()));
```

Multiple `filter` calls are ANDed. The handler runs only when every predicate passes.

### Direct subscription

When you need no filter:

```java
EventSubscription sub = events.subscribe(PlayerConnectedEvent.class, e -> {
    // handle e
});
// later:
sub.unsubscribe();
```

### Catch-all and custom-type subscription

`subscribeAll` receives every event. It backs the SSE bridge and logging. Use it sparingly — your handler runs for every event on the bus.

```java
events.subscribeAll(e -> log.debug("event {}", e.type()));
```

`subscribeByType` matches the `type()` string and delivers only `CustomCloudEvent` instances:

```java
events.subscribeByType("CHAT:MESSAGE", e -> {
    String text = (String) e.payload().get("text");
});
```

### Publishing custom events

For event types not known at compile time, publish a `CustomCloudEvent`:

```java
events.publish(new CustomCloudEvent(
        "CHAT:MESSAGE",                 // type, MODULE:ACTION format
        "lobby-1",                      // source: instance id, module name, etc.
        Map.of("text", "hello", "from", "Steve")));
```

`CustomCloudEvent` is a record with fields `type`, `source`, `payload` (a `Map<String, Object>`), and `timestamp`. A null `payload` defaults to an empty map; a null `timestamp` defaults to `Instant.now()`. A two-argument constructor fills the timestamp for you.

## Dispatch and threading

The Controller's `EventBus` keeps three handler registries: class-based, type-string, and wildcard (catch-all). `publish` resolves all three for each event.

- Handlers run on **virtual threads** using structured concurrency (`StructuredTaskScope`). Every matching handler is forked, then the publishing task joins them.
- `publish` is **fire-and-forget** for the caller: it submits one task to a virtual-thread executor and returns. Handlers run asynchronously.
- A handler that throws is logged and isolated — one failing handler does not stop the others, and the failure does not propagate back to the publisher.
- If an event has no subscribers in any registry, `publish` returns immediately without scheduling work.

The plugin/module-adapter bus (`CloudEventBusImpl`) dispatches **synchronously** on the calling thread and isolates handler exceptions the same way. Filters attached through `on(...).filter(...)` are evaluated before the handler runs.

## Built-in event types

Every type below is a record in `api.event.events` and implements `CloudEvent`. The **Type** column is the `type()` string (and the SSE `type` field). The **Fields** column lists the record components.

### Node lifecycle

| Type | Fields |
| --- | --- |
| `NODE_CONNECTED` | `nodeId`, `sessionId`, `timestamp` |
| `NODE_DISCONNECTED` | `nodeId`, `reason`, `timestamp` |
| `NODE_STATUS` | `nodeId`, `cpuUsage`, `usedMemoryMb`, `totalMemoryMb`, `lastHeartbeatAt` |
| `NODE_HEARTBEAT_STALE` | `nodeId`, `missedPongs`, `lastHeartbeatAt` |
| `NODE_HEARTBEAT_RESUMED` | `nodeId`, `lastHeartbeatAt` |
| `NODE_DRAIN_REQUESTED` | `nodeId`, `shutdownAfterDrain`, `drainTimeoutSeconds`, `kickMessage`, `timestamp` |
| `NODE_DRAIN_COMPLETED` | `nodeId`, `timestamp` |
| `NODE_CACHE_STATUS` | `nodeId`, `totalSizeBytes`, `timestamp` |

### Instance lifecycle

| Type | Fields |
| --- | --- |
| `INSTANCE_STATE_CHANGED` | `instanceId`, `group`, `nodeId`, `oldState`, `newState` |
| `INSTANCE_CRASHED` | `instanceId`, `group`, `nodeId`, `exitCode`, `classification`, `logTail` (list), `uptimeMs` |
| `INSTANCE_DRAINING` | `instanceId`, `group`, `nodeId` |
| `INSTANCE_CONSOLE_OUTPUT` | `instanceId`, `line`, `timestampMs` |
| `INSTANCE_METRICS` | `instanceId`, `group`, `tps1m`, `tps5m`, `tps15m`, `msptAvg`, `heapUsedMb`, `heapMaxMb`, `gcCollections`, `gcTimeMs`, `threadCount`, `playerCount`, `maxPlayers`, `worldCount`, `totalEntities`, `totalChunks`, `worlds` (list of `WorldSnapshot`), `serverVersion`, `pluginCount` |

`oldState` and `newState` on `INSTANCE_STATE_CHANGED` are `InstanceState` enum values (`SCHEDULED`, `PREPARING`, `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, `CRASHED`, `DRAINING`). `WorldSnapshot` on `INSTANCE_METRICS` carries `name`, `environment`, `entityCount`, `chunkCount`, `playerCount`.

`INSTANCE_CONSOLE_OUTPUT` is **not** forwarded on the general SSE event stream — it is high-volume and streamed per-Instance by the console streamer (see [Other SSE streams](#other-sse-streams)).

### Group lifecycle

| Type | Fields |
| --- | --- |
| `GROUP_CREATED` | `groupName` |
| `GROUP_UPDATED` | `groupName` |
| `GROUP_DELETED` | `groupName` |
| `GROUP_AGGREGATES_UPDATED` | `groupName`, `runningInstances`, `totalPlayers` |
| `GROUP_MAINTENANCE_CHANGED` | `groupName`, `maintenance`, `message` |
| `GROUP_CRASH_LOOP` | `group`, `crashCount`, `windowStart` |

### Deployment

| Type | Fields |
| --- | --- |
| `DEPLOYMENT_CREATED` | `groupName`, `revision`, `strategy` |
| `DEPLOYMENT_COMPLETED` | `groupName`, `revision`, `outcome` |

### Players

| Type | Fields |
| --- | --- |
| `PLAYER_CONNECTED` | `uuid`, `name`, `instanceId`, `group` |
| `PLAYER_DISCONNECTED` | `uuid`, `name`, `instanceId`, `group` |
| `PLAYER_TRANSFER` | `uuid`, `name`, `fromInstanceId`, `toInstanceId` |
| `PLAYER_JOURNEY` | `entry` (a `PlayerJourneyEntry`) |

### Templates and maintenance

| Type | Fields |
| --- | --- |
| `TEMPLATE_UPDATED` | `templateName`, `oldHash`, `newHash` |
| `MAINTENANCE_UPDATED` | `globalEnabled`, `message` |

### Modules and capabilities

| Type | Fields |
| --- | --- |
| `MODULE_LOADED` | `moduleName`, `hasFrontend` |
| `MODULE_UNLOADED` | `moduleName` |
| `MODULE_FRONTEND_RELOADED` | `moduleName`, `contentHash` |
| `CAPABILITY_REGISTERED` | `capabilityId`, `version`, `moduleId` |
| `CAPABILITY_UNREGISTERED` | `capabilityId`, `moduleId` |
| `CAPABILITY_PROVIDER_CHANGED` | `capabilityId`, `moduleId`, `fromVersion`, `toVersion` |

### Cluster and choreography

| Type | Fields |
| --- | --- |
| `CLUSTER_CONFIG_CHANGED` | `version`, `parentVersion`, `mutator`, `action` |
| `CHOREOGRAPHY_OVERLAY_ACTIVATED` | `eventName`, `group`, `activeUntil` |
| `CHOREOGRAPHY_OVERLAY_DEACTIVATED` | `eventName`, `group`, `reason` |

## The SSE event stream

The Controller bridges the bus to browser clients over Server-Sent Events at:

```
GET /api/v1/events/stream
```

`SseEventStreamer` calls `subscribeAll` once at startup and forwards every event (except `INSTANCE_CONSOLE_OUTPUT`) to all connected clients. New event types — including custom module events — are forwarded automatically with no per-type wiring.

### Event envelope

Each event is serialized to JSON via the standard `ObjectMapper`, then two fields are added:

- `type` — the event's `type()` string.
- `sequence` — a monotonic `long` assigned by the replay store at forward time.

The envelope is sent as an SSE `message` event whose `id:` field is the sequence. Example frame:

```
event: message
id: 4711
data: {"instanceId":"lobby-1","group":"lobby","oldState":"STARTING","newState":"RUNNING","type":"INSTANCE_STATE_CHANGED","sequence":4711}
```

On connect the server first sends a `connected` event carrying the current `latestSequence`:

```
event: connected
data: {"message":"Connected to event stream","latestSequence":4710}
```

### Authentication: SSE tickets

The stream does not accept a JWT in the URL — it would leak into history, proxy logs, and the `Referer` header. Browsers also cannot set an `Authorization` header on an `EventSource`. Clients exchange a JWT for a short-lived ticket first:

```
POST /api/v1/events/ticket        (Authorization: Bearer <jwt>)
→ 200 {"ticket":"<opaque-token>"}
```

Then open the stream with the ticket as a query parameter:

```
GET /api/v1/events/stream?ticket=<opaque-token>
```

Ticket properties:

- **Single-use.** Validating a ticket consumes it. Each stream connection needs a fresh ticket.
- **Short-lived.** Tickets expire 30 seconds after issue.
- **Opaque.** 24 random bytes, URL-safe Base64, no padding.

If the ticket is missing, unknown, expired, or already consumed, the server sends an `error` event with `{"message":"Unauthorized"}` and closes the connection. The `/api/v1/events/ticket` endpoint itself requires a valid JWT; issuing a ticket records the caller's username and role.

With Redis configured, tickets are stored in Redis (`SETEX` with the TTL) so any Controller in an HA cluster can validate a ticket issued by another. Without Redis they live in an in-process map.

Worked example with `curl`:

```bash
# 1. Exchange a JWT for a 30-second ticket.
TICKET=$(curl -s -X POST -H "Authorization: Bearer $JWT" \
    "$CONTROLLER/api/v1/events/ticket" | jq -r .ticket)

# 2. Stream live events.
curl -N "$CONTROLLER/api/v1/events/stream?ticket=$TICKET"
```

### Connection limit

At most **100** concurrent SSE clients are accepted. Beyond that, new connections receive an `error` event with `{"message":"Too many connections"}` and are closed, and the Controller logs a warning.

### Replay and resumption

The stream is resumable. The server keeps a bounded buffer of recent envelopes — the **replay store** — with capacity **2048** events. On reconnect a client tells the server the last sequence it saw, and the server replays everything after it.

A client signals its position two ways (the query parameter wins if both are present):

- Query parameter `lastSequence=<n>`.
- Standard SSE header `Last-Event-ID: <n>` — the browser sends this automatically from the last `id:` it received.

Reconnect flow:

1. Open `GET /api/v1/events/stream?ticket=...&lastSequence=4710`.
2. The server sends `connected` with the current `latestSequence`.
3. The server replays every buffered envelope with `sequence > 4710`, in order.
4. The server then streams new events live.

**Resync required.** If the requested position is older than the oldest buffered event — the client fell too far behind and events were evicted — the server cannot replay the gap. Instead of silently dropping events it sends a synthetic `RESYNC_REQUIRED` message:

```
event: message
id: 5000
data: {"type":"RESYNC_REQUIRED","lastSequence":10,"earliestSequence":2953,"latestSequence":5000,"timestamp":"2026-06-07T12:00:00Z"}
```

On `RESYNC_REQUIRED`, refetch current state from the REST API — the incremental view is stale — and continue from `latestSequence`. A first-time client with no `lastSequence` connects cleanly and starts at the live edge; it is told to resync only if it claims a position the buffer can no longer cover. The full state model is always reachable via REST; the SSE stream is the delta channel.

### Replay store backends

The replay store has two implementations, selected by whether Redis is wired:

| Backend | Sequence source | Buffer |
| --- | --- | --- |
| In-memory (no Redis) | `AtomicLong` counter | `ArrayDeque`, trimmed to 2048 |
| Redis | `INCR` on the sequence key | Redis stream, `XADD ... MAXLEN 2048` (exact trim) |

The Redis backend (keys `SSE_SEQUENCE` and `SSE_REPLAY`) shares sequence numbers and replay history across an HA cluster, so a client can disconnect from one Controller and resume against another. The in-memory backend is per-process and is lost on Controller restart.

### Other SSE streams

The general event stream is not the only SSE endpoint. Alongside it the Controller registers:

- A **console stream** for `INSTANCE_CONSOLE_OUTPUT` (per-Instance, excluded from the general stream).
- A **capability stream** for capability-registry changes.
- Optional **controller-log** and **daemon-log** streams when those streamers are enabled.

All use the same ticket-based authentication.

### Dashboard client

The dashboard opens a single shared `EventSource` through the `useSseEventBus` composable. It performs the ticket exchange, passes `lastSequence` when reconnecting, persists the last sequence it saw, and applies any per-event filtering on the client. Stores and components share that one connection rather than opening their own.

## Subscribing from a daemon-host module

A Module running on a Daemon — out of the Controller process — cannot subscribe to the Controller bus directly. It registers interest over the daemon protocol, and the Controller forwards matching events back across the session.

### Protocol

Two daemon-to-controller messages drive this (`daemon_service.proto`):

```proto
message EventSubscribe   { repeated string event_types = 1; }
message EventUnsubscribe { repeated string event_types = 1; }
```

`event_types` are **fully qualified Java class names**, for example `me.prexorjustin.prexorcloud.api.event.events.GroupCreatedEvent` — not the `type()` string. On `EventSubscribe`, the Controller's `DaemonEventForwarder` subscribes the Controller bus to that class and forwards future matching events to the daemon as:

```proto
message ModuleEvent {
  string event_type   = 1;  // fully-qualified Java class name of the CloudEvent
  bytes  payload_json = 2;  // Jackson-serialized event payload
}
```

The forwarder keeps a per-node map of `eventType → EventSubscription`. Each forwarded event is serialized with the standard `ObjectMapper` and delivered over the daemon's response stream. An unknown class name is answered with an error report. The daemon may live-unsubscribe with `EventUnsubscribe`; on daemon disconnect the Controller cleans up all of that node's subscriptions automatically.

Most module authors do not touch the proto directly — they call `ModuleContext.events()` and the host runtime translates subscriptions into `EventSubscribe` messages.

## Subscribing from a plugin

Plugins on Minecraft servers and proxies subscribe through `CloudPluginContext.events()`, which returns the same `EventBus` interface backed by `CloudEventBusImpl`. The contract is identical to the module path: `on(...).filter(...).subscribe(...)`, `subscribe`, `subscribeByType`, `subscribeAll`, `publish`. Handler exceptions are caught and logged per handler.

```java
public void onEnable(CloudPluginContext ctx) {
    ctx.events().on(PlayerTransferEvent.class)
            .filter(e -> e.toInstanceId().startsWith("minigame-"))
            .subscribe(e -> getLogger().info(e.name() + " entered a minigame"));
}
```

## Cross-controller propagation

In an HA cluster, `RedisEventBridge` republishes a defined subset of bus events to Redis Pub/Sub so peer Controllers can reconcile their local state. It bridges:

- Node: `NODE_CONNECTED`, `NODE_DISCONNECTED`, `NODE_STATUS`.
- Instance: `INSTANCE_STATE_CHANGED`.
- Player: `PLAYER_CONNECTED`, `PLAYER_DISCONNECTED`.
- Group: `GROUP_CREATED`, `GROUP_UPDATED`, `GROUP_DELETED`.

Each event is wrapped with the publishing Controller's id; a Controller skips envelopes it published itself (loop prevention). This bridge keeps cluster state consistent across Controllers; it is separate from the SSE replay path, which serves browser clients. Only the set above is republished — those are the events other Controllers act on to update their caches. Without Redis, each Controller process has its own bus and no cross-controller fanout.

## Worked example: webhook on crash

A controller-side Module that subscribes to crashes and fires a webhook on each:

```java
public final class CrashWebhookModule implements PlatformModule {

    private EventSubscription crashSub;
    private EventSubscription maintSub;

    @Override
    public void onStart(ModuleContext ctx) {
        crashSub = ctx.events().on(InstanceCrashedEvent.class)
                .filter(e -> e.group().equals("survival"))
                .subscribe(this::onCrash);

        maintSub = ctx.events().subscribe(
                GroupMaintenanceChangedEvent.class, this::onMaintenance);
    }

    private void onCrash(InstanceCrashedEvent e) {
        webhook.send("ops",
            "Instance " + e.instanceId() + " crashed"
            + " (exit " + e.exitCode() + ", " + e.classification() + ")");
    }

    private void onMaintenance(GroupMaintenanceChangedEvent e) {
        webhook.send("ops",
            "Group " + e.groupName()
            + (e.maintenance() ? " entered" : " left")
            + " maintenance: " + e.message());
    }

    @Override
    public void onStop(ModuleContext ctx) {
        if (crashSub != null) crashSub.unsubscribe();
        if (maintSub != null) maintSub.unsubscribe();
    }
}
```

Unsubscribe in `onStop`. Subscriptions are not cleaned up for you; dropping the handle without unsubscribing leaks the handler.

## Quick reference

| Concern | Value |
| --- | --- |
| Stream endpoint | `GET /api/v1/events/stream` |
| Ticket endpoint | `POST /api/v1/events/ticket` |
| Stream auth | single-use `ticket` query param |
| Ticket TTL | 30 seconds |
| Max concurrent SSE clients | 100 |
| Replay buffer capacity | 2048 events |
| Resume parameter | `lastSequence` query param or `Last-Event-ID` header |
| Replay-too-old signal | `RESYNC_REQUIRED` message |
| Excluded from stream | `INSTANCE_CONSOLE_OUTPUT` |
| Module subscribe wire format | fully-qualified class name via `EventSubscribe` |
| Forwarded event to module | `ModuleEvent { event_type, payload_json }` |
