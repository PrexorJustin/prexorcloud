---
title: CloudPluginContext
description: The plugin-side context handed to onEnable — self(), events(), commands(), players(), scheduler(), client(), logger(), plus the instance, transfer, and cluster-read surfaces they expose.
---

`CloudPluginContext` is the entry point a plugin receives in
`onEnable(CloudPluginContext ctx)`. It exposes the shared event bus, the
local instance's self-info, the on-server player roster, the command
registry, a Folia-safe scheduler, and the low-level `CloudClient` used to
mark readiness, transfer players, and fetch instance views.

This page documents every method on `CloudPluginContext` and the types it
hands back: `InstanceContext`, `CloudClient`, `InstanceView`,
`InstanceState`, `PluginScheduler`, and `ScheduledTask`. It also documents
the cluster-read surface (groups, instances, networks, players) and the
workload-token model used by the internal controller client.

## What you'll learn

- Every method on `CloudPluginContext`, with signature and return type.
- The shape of `InstanceContext` and the `InstanceView` snapshot.
- The full `CloudClient` surface: readiness, transfers, instance fetch,
  crash reporting.
- The `PluginScheduler` / `ScheduledTask` contract.
- How groups, instances, and networks are read, and how workload tokens
  rotate.

## Interfaces and where they live

| Type | Package |
|---|---|
| `CloudPluginContext` | `me.prexorjustin.prexorcloud.api.plugin` |
| `CloudPluginBase` | `me.prexorjustin.prexorcloud.api.plugin` |
| `InstanceContext` | `me.prexorjustin.prexorcloud.api.plugin` |
| `PluginScheduler` | `me.prexorjustin.prexorcloud.api.plugin` |
| `CloudClient` | `me.prexorjustin.prexorcloud.api.client` |
| `TransferResult` | `me.prexorjustin.prexorcloud.api.client` |
| `InstanceView` | `me.prexorjustin.prexorcloud.api.domain` |
| `InstanceState` | `me.prexorjustin.prexorcloud.api.domain` |
| `NetworkComposition` | `me.prexorjustin.prexorcloud.api.domain` |
| `ScheduledTask` | `me.prexorjustin.prexorcloud.api` |

## `CloudPluginContext`

```java
public interface CloudPluginContext {
    InstanceContext      self();
    EventBus             events();
    CloudCommandRegistry commands();
    PlayerManager        players();
    PluginScheduler      scheduler();
    CloudClient          client();
    java.util.logging.Logger logger();
}
```

### `self()`

```java
InstanceContext self();
```

Self-info for this plugin's own server instance — instance id, group,
node id, port, and a live cluster snapshot. Use it to attribute events
back to the right instance when publishing to the cluster bus. See
[`InstanceContext`](#instancecontext) below.

### `events()`

```java
EventBus events();
```

The scoped `EventBus` for this plugin — the same fluent surface the Module
SDK uses. Events published from a plugin are visible to modules on the
controller, and vice versa.

```java
ctx.events().on(PlayerConnectedEvent.class)
        .filter(e -> e.group().equals("lobby"))
        .subscribe(e -> ctx.logger().info(e.name() + " joined lobby"));
```

See [EventBus on the module side](/reference/module-sdk/event-bus/) for
the full subscription, filter, and publish surface.

### `commands()`

```java
CloudCommandRegistry commands();
```

Slash-command registration. Register
`@Command`-annotated classes; commands are forwarded to the platform's
native command system (Brigadier on Paper, the equivalent on Velocity /
BungeeCord). See
[Players + Commands](/reference/plugin-sdk/players-and-commands/).

### `players()`

```java
PlayerManager players();
```

Players currently online on this instance. See
[Players + Commands](/reference/plugin-sdk/players-and-commands/).

### `scheduler()`

```java
PluginScheduler scheduler();
```

Platform-agnostic, Folia-safe task scheduler. Use it for any delayed or
repeating work; calling Bukkit's scheduler directly is unsafe on Folia.
See [`PluginScheduler`](#pluginscheduler) below.

### `client()`

```java
CloudClient client();
```

Low-level client for talking to the controller from this instance: mark
ready / stopping, transfer players, fetch an instance view, report a
crash. See [`CloudClient`](#cloudclient) below. Most plugins drive
transfers and readiness through here; reading groups and networks is
covered under [Reading groups, instances, and
networks](#reading-groups-instances-and-networks).

### `logger()`

```java
java.util.logging.Logger logger();
```

JUL (`java.util.logging`) logger for the plugin. Plugins use JUL because
it behaves the same on Bukkit and Velocity. Modules — which run inside the
controller / daemon JVMs — use SLF4J instead.

## `InstanceContext`

Returned by `ctx.self()`. Self-info for the running instance.

```java
public interface InstanceContext {
    String       instanceId();
    String       group();
    String       nodeId();
    int          port();
    InstanceView snapshot();
    CloudClient  client();
}
```

| Method | Returns | Meaning |
|---|---|---|
| `instanceId()` | `String` | This instance's unique id (e.g. `"lobby-1"`). |
| `group()` | `String` | The group this instance belongs to. |
| `nodeId()` | `String` | The daemon node hosting this instance. |
| `port()` | `int` | TCP port the instance listens on. |
| `snapshot()` | `InstanceView` | Current snapshot of this instance's view from the cluster. |
| `client()` | `CloudClient` | The `CloudClient` scoped to this instance — same handle as `ctx.client()`. |

```java
InstanceContext self = ctx.self();
ctx.logger().info("running as " + self.instanceId()
        + " in group " + self.group()
        + " on node " + self.nodeId()
        + " port " + self.port());

InstanceView view = self.snapshot();
ctx.logger().info("players online: " + view.playerCount()
        + " state: " + view.state());
```

## `InstanceView`

A read-only snapshot of one running instance. Returned by
`InstanceContext.snapshot()` and `CloudClient.fetchInstance(...)`, and
used by the Module SDK's `ClusterView` as well.

```java
public record InstanceView(
        String        instanceId,
        String        group,
        String        nodeId,
        String        nodeAddress,
        InstanceState state,
        int           port,
        int           playerCount,
        long          uptimeMs,
        Instant       startedAt) {}
```

| Component | Type | Meaning |
|---|---|---|
| `instanceId()` | `String` | Unique instance identifier. |
| `group()` | `String` | Group this instance belongs to. |
| `nodeId()` | `String` | Daemon node hosting the instance. |
| `nodeAddress()` | `String` | Routable IP or hostname of the hosting node. |
| `state()` | `InstanceState` | Current lifecycle state. |
| `port()` | `int` | TCP port the instance listens on. |
| `playerCount()` | `int` | Players currently connected. |
| `uptimeMs()` | `long` | Milliseconds since the instance reached `RUNNING`. |
| `startedAt()` | `Instant` | Timestamp the instance reached `RUNNING`. |

## `InstanceState`

```java
public enum InstanceState {
    SCHEDULED, PREPARING, STARTING, RUNNING,
    STOPPING, STOPPED, CRASHED, DRAINING;

    public boolean isActive();        // RUNNING or DRAINING
    public boolean isTerminal();      // STOPPED or CRASHED
    public boolean isTransitional();  // neither active nor terminal
}
```

| Method | `true` when state is |
|---|---|
| `isActive()` | `RUNNING`, `DRAINING` |
| `isTerminal()` | `STOPPED`, `CRASHED` |
| `isTransitional()` | `SCHEDULED`, `PREPARING`, `STARTING`, `STOPPING` |

```java
if (view.state().isActive()) {
    // accepting or serving players
}
```

## `CloudClient`

The low-level controller client for a plugin instance. Returned by both
`ctx.client()` and `ctx.self().client()`. Every call returns a
`CompletableFuture` and is non-blocking.

```java
public interface CloudClient {
    String                          instanceId();
    CompletableFuture<Void>         markReady();
    CompletableFuture<Void>         markStopping();
    CompletableFuture<TransferResult> transferPlayer(UUID playerId, String targetGroup);
    CompletableFuture<TransferResult> transferPlayerTo(UUID playerId, String targetInstanceId);
    CompletableFuture<InstanceView> fetchInstance(String instanceId);
    CompletableFuture<Void>         reportCrash(String exitCode, String logTail);
}
```

### `instanceId()`

```java
String instanceId();
```

The id of the instance this client is scoped to.

### `markReady()`

```java
CompletableFuture<Void> markReady();
```

Notify the controller that this instance is ready to accept players. The
controller flips the instance to `RUNNING` and begins routing.

```java
ctx.client().markReady()
        .thenRun(() -> ctx.logger().info("instance ready"));
```

### `markStopping()`

```java
CompletableFuture<Void> markStopping();
```

Notify the controller that this instance is stopping. Routing stops; the
instance transitions out of the active set.

### `transferPlayer(playerId, targetGroup)`

```java
CompletableFuture<TransferResult> transferPlayer(UUID playerId, String targetGroup);
```

Move a player to any available instance in `targetGroup`. The controller
picks the target instance. Resolves with a [`TransferResult`](#transferresult).

```java
ctx.client().transferPlayer(playerId, "minigames")
        .thenAccept(r -> {
            if (r.success()) {
                ctx.logger().info("moved to " + r.targetInstanceId());
            } else {
                ctx.logger().warning("transfer failed: " + r.failureReason());
            }
        });
```

### `transferPlayerTo(playerId, targetInstanceId)`

```java
CompletableFuture<TransferResult> transferPlayerTo(UUID playerId, String targetInstanceId);
```

Move a player to a specific instance by id. Resolves with a
`TransferResult`.

### `fetchInstance(instanceId)`

```java
CompletableFuture<InstanceView> fetchInstance(String instanceId);
```

Fetch the current [`InstanceView`](#instanceview) for any instance in the
cluster by id.

```java
ctx.client().fetchInstance("lobby-2")
        .thenAccept(v -> ctx.logger().info("lobby-2 players: " + v.playerCount()));
```

### `reportCrash(exitCode, logTail)`

```java
CompletableFuture<Void> reportCrash(String exitCode, String logTail);
```

Report a crash to the controller with the process `exitCode` and a tail of
the instance log. Drives crash bookkeeping and operator alerting.

## `TransferResult`

```java
public record TransferResult(boolean success, String targetInstanceId, String failureReason) {
    public static TransferResult success(String targetInstanceId);
    public static TransferResult failure(String reason);
}
```

| Component | Type | Meaning |
|---|---|---|
| `success()` | `boolean` | `true` if the player was transferred. |
| `targetInstanceId()` | `String` | Resolved target instance id on success; `null` on failure. |
| `failureReason()` | `String` | Reason string on failure; `null` on success. |

## `PluginScheduler`

Platform-agnostic, Folia-safe scheduler. Returned by `ctx.scheduler()`.
Every method returns a [`ScheduledTask`](#scheduledtask). All three run the
task asynchronously.

```java
public interface PluginScheduler {
    ScheduledTask runAsync(Runnable task);
    ScheduledTask runDelayed(Duration delay, Runnable task);
    ScheduledTask runAtFixedRate(Duration initialDelay, Duration period, Runnable task);
}
```

### `runAsync(task)`

```java
ScheduledTask runAsync(Runnable task);
```

Run `task` asynchronously as soon as possible.

### `runDelayed(delay, task)`

```java
ScheduledTask runDelayed(Duration delay, Runnable task);
```

Run `task` asynchronously after `delay`.

```java
ctx.scheduler().runDelayed(Duration.ofSeconds(30),
        () -> ctx.logger().info("warmup complete"));
```

### `runAtFixedRate(initialDelay, period, task)`

```java
ScheduledTask runAtFixedRate(Duration initialDelay, Duration period, Runnable task);
```

Run `task` asynchronously starting after `initialDelay`, then every
`period`.

```java
ScheduledTask heartbeat = ctx.scheduler().runAtFixedRate(
        Duration.ZERO, Duration.ofMinutes(1), this::broadcast);
// later:
heartbeat.cancel();
```

## `ScheduledTask`

Handle to a scheduled task, shared by the plugin scheduler and the module
`TaskScheduler`.

```java
public interface ScheduledTask {
    void    cancel();        // no-op if already cancelled or completed
    boolean isCancelled();
}
```

## Reading groups, instances, and networks

`CloudPluginContext` exposes the local instance and per-player transfers
directly. Cluster-wide reads — every group, every instance, the network
compositions, and the global player roster — go through the controller's
HTTP API, fronted on the plugin side by `BaseControllerClient`
(`me.prexorjustin.prexorcloud.plugin.common`). Server and proxy platforms
subclass it and supply the API prefix (`/api/plugin` or `/api/proxy`).

```java
public List<InstanceDto>      fetchInstances();   // GET <prefix>/instances
public List<GroupDto>         fetchGroups();       // GET <prefix>/groups
public List<NetworkComposition> fetchNetworks();   // GET <prefix>/networks
public List<PlayerDto>        fetchPlayers();      // GET <prefix>/players
public void fireEvent(String type, Map<String, Object> data);  // POST <prefix>/events
```

### `GroupDto`

Shape returned by `fetchGroups()`. Unknown JSON fields are ignored
(`@JsonIgnoreProperties(ignoreUnknown = true)`).

| Field | Type | Meaning |
|---|---|---|
| `name` | `String` | Group name. |
| `platform` | `String` | Platform identifier. |
| `minInstances` | `int` | Minimum instance count. |
| `maxInstances` | `int` | Maximum instance count. |
| `maxPlayers` | `int` | Per-group player cap. |
| `onlineCount` | `int` | Players currently online in the group. |
| `isMaintenance` | `boolean` | Group is in maintenance mode. |
| `maintenanceMessage` | `String` | Message shown during maintenance. |
| `maintenanceBypass` | `List<String>` | Identities allowed to bypass maintenance. |
| `isStatic` | `boolean` | Static (non-scaling) group. |
| `defaultGroup` | `boolean` | Group is the default join target. |
| `memoryMb` | `int` | Memory reservation per instance, MiB. |
| `cpuReservation` | `double` | CPU reservation per instance. |
| `diskReservationMb` | `long` | Disk reservation per instance, MiB. |
| `jvmArgs` | `List<String>` | Extra JVM args. |
| `env` | `Map<String, String>` | Environment overrides. |
| `nodeAffinity` | `List<String>` | Node affinity constraints. |
| `motds` | `List<String>` | Proxy-only MOTD lines. |
| `motdMode` | `String` | Proxy-only MOTD rotation mode. |
| `motdIntervalSeconds` | `int` | Proxy-only MOTD rotation interval. |

### `InstanceDto`

Shape returned by `fetchInstances()`. Mirrors `InstanceView` but carries
`state` as a `String`.

| Field | Type |
|---|---|
| `instanceId` | `String` |
| `group` | `String` |
| `nodeId` | `String` |
| `nodeAddress` | `String` |
| `state` | `String` |
| `port` | `int` |
| `playerCount` | `int` |
| `uptimeMs` | `long` |
| `startedAt` | `Instant` |

### `PlayerDto`

Shape returned by `fetchPlayers()`.

| Field | Type |
|---|---|
| `id` | `String` |
| `name` | `String` |
| `instanceId` | `String` |
| `group` | `String` |

### `NetworkComposition`

Shape returned by `fetchNetworks()`. A named composition of proxy +
backend groups defining lobby spawn and fallback routing.

```java
public record NetworkComposition(
        String       name,
        String       description,
        String       lobbyGroup,
        List<String> fallbackGroups,
        List<String> memberGroups,
        List<String> proxyGroups,
        String       kickMessage,
        String       bedrockLobbyGroup,
        List<String> bedrockFallbackGroups) {}
```

| Component | Type | Meaning |
|---|---|---|
| `name()` | `String` | Unique network id; matches `[a-z0-9_][a-z0-9_-]*`. |
| `description()` | `String` | Human-readable description (may be empty). |
| `lobbyGroup()` | `String` | Default join target and last-resort fallback. |
| `fallbackGroups()` | `List<String>` | Ordered fallback chain on instance failure (may be empty). |
| `memberGroups()` | `List<String>` | Backend groups in the network; empty means no restriction. |
| `proxyGroups()` | `List<String>` | Proxy groups this composition applies to; empty means all proxies. |
| `kickMessage()` | `String` | Shown when all fallbacks are exhausted (may be empty). |
| `bedrockLobbyGroup()` | `String` | Join target for Bedrock players; blank means use `lobbyGroup`. |
| `bedrockFallbackGroups()` | `List<String>` | Bedrock-specific fallback chain; empty means use `fallbackGroups`. |

The canonical constructor validates `name` and `lobbyGroup` (both must be
non-blank) and copies the list fields defensively, normalizing `null` to
an empty list. A seven-argument constructor omits the two Bedrock fields,
defaulting them to `""` / `List.of()` so Bedrock players follow the Java
route.

### `fireEvent(type, data)`

```java
public void fireEvent(String type, Map<String, Object> data);
```

Publish a custom event to the controller's event bus (`POST
<prefix>/events`). A `null` `data` map is sent as an empty object. Fire and
forget — serialization failures are logged, not thrown.

## Workload tokens

`BaseControllerClient` authenticates every request with a workload token: a
short-lived, sliding-window bearer sent as `Authorization: Bearer <token>`.
You do not manage rotation manually.

- On a `401` response, the client exchanges the current token for a fresh
  one via `POST <prefix>/auth/refresh` and retries the original request
  once.
- Refresh is single-flight across threads: a burst of in-flight requests
  hitting the expiry window rotates the token only once; concurrent callers
  pick up the new token on their retry.
- Requests carry an incrementing `X-Prexor-Sequence` header for ordering
  and a `traceparent` header for tracing.
- Read calls (`get`) and the event-stream ticket request time out after 10
  seconds.

Server-Sent Events use a two-step handshake: the client obtains a
single-use ticket from `POST <prefix>/events/ticket`, then opens
`GET /api/v1/events/stream?ticket=<ticket>`, resuming from `lastSequence`
via the `Last-Event-ID` header when reconnecting.

## `CloudPluginBase` lifecycle

Plugins extend `CloudPluginBase` (not `JavaPlugin`).

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

| Method | When |
|---|---|
| `onEnable(ctx)` | Once per server startup. Required. |
| `onDisable()` | Best-effort on shutdown. |
| `onReload(ctx)` | When an operator runs a reload command. Optional. |

`adapt(...)` is the gateway to `@ForVersion` dispatch — see
[@CloudPlugin Annotation](/reference/plugin-sdk/cloudplugin-annotation/).
Both `adapt` overloads throw `IllegalStateException` if the generated
bridge has not initialized the `VersionDispatcher` before `onEnable`.

## Obtaining the context

Plugin developers never construct the context. The generated bridge calls
`CloudApiProvider.createPluginContext(...)` and passes the result to
`onEnable`. The global `CloudApi` handle (`CloudApiProvider.get()`) exposes
`events()`, `cluster()`, and `version()`; plugins use the scoped bus from
`ctx.events()` rather than the global one. `CloudApiProvider.get()` throws
`IllegalStateException` if PrexorCloud is not running.

## Example

A plugin that broadcasts every minute, logs joining players, and marks the
instance ready on enable:

```java
@CloudPlugin(name = "heartbeat", version = "1.0.0")
public final class HeartbeatPlugin extends CloudPluginBase {

    private HeartbeatService service;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.service = new HeartbeatService(ctx.players(), ctx.client(), ctx.logger());

        ctx.scheduler().runAtFixedRate(
                Duration.ZERO,
                Duration.ofMinutes(1),
                service::broadcast);

        ctx.events().on(PlayerConnectedEvent.class)
                    .subscribe(service::onConnect);

        ctx.client().markReady();

        ctx.logger().info("heartbeat enabled on instance " + ctx.self().instanceId());
    }
}
```

`HeartbeatService` takes `PlayerManager`, `CloudClient`, and `Logger` as
constructor arguments — the same constructor-injection rule as the Module
SDK.

## Next up

- [EventHandler](/reference/plugin-sdk/event-handler/) — subscription
  details and event types.
- [Players + Commands](/reference/plugin-sdk/players-and-commands/) — the
  `PlayerManager` and `CloudCommandRegistry` surfaces.
- [@CloudPlugin Annotation](/reference/plugin-sdk/cloudplugin-annotation/)
  — `@CloudPlugin` and `@ForVersion`.
