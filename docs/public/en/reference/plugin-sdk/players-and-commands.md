---
title: Players, commands, and player events
description: The PlayerManager roster, the CloudCommandRegistry command API (builder, annotation, programmatic), player-related cloud events, and the in-server plugin↔controller REST surface.
---

This page documents four in-server plugin surfaces:

- `PlayerManager` / `CloudPlayer` — the player roster on this instance.
- `CloudCommandRegistry` — slash commands, with three registration
  paths (builder, annotation, programmatic).
- Player-related cloud events delivered through the `EventBus`.
- `CloudClient` and the `/api/plugin` REST endpoints the server plugin
  calls on the controller.

All four are reached through
[`CloudPluginContext`](/reference/plugin-sdk/plugin-context/). Types
documented here live in `me.prexorjustin.prexorcloud.api.plugin.*`
unless stated otherwise.

## PlayerManager

```java
package me.prexorjustin.prexorcloud.api.plugin.player;

public interface PlayerManager {
    Optional<CloudPlayer> getPlayer(UUID uniqueId);
    Optional<CloudPlayer> getPlayer(String name);
    Collection<CloudPlayer>  onlinePlayers();
    int onlineCount();
}
```

`PlayerManager` covers players currently online on **this** instance.
Obtain it from `ctx.players()`.

| Method | Returns | Notes |
|---|---|---|
| `getPlayer(UUID uniqueId)` | `Optional<CloudPlayer>` | Empty if the player is not online here. |
| `getPlayer(String name)` | `Optional<CloudPlayer>` | Name lookup; empty if not found. |
| `onlinePlayers()` | `Collection<CloudPlayer>` | Snapshot of the current roster. |
| `onlineCount()` | `int` | Size of the roster. |

Treat every `Optional` as cold: players disconnect between the lookup
and your next line.

```java
ctx.players().getPlayer("Notch")
    .ifPresent(p -> p.sendMessage("Welcome back."));

int online = ctx.players().onlineCount();
```

### CloudPlayer

```java
package me.prexorjustin.prexorcloud.api.plugin.player;

public interface CloudPlayer {
    UUID    uniqueId();
    String  name();
    String  currentInstanceId();
    String  currentGroup();
    PlayerView toView();
    void    sendMessage(String message);
    CompletableFuture<Boolean> transfer(String targetGroup);
    CompletableFuture<Boolean> transferTo(String targetInstanceId);
    void    kick(String reason);
}
```

| Method | Returns | Behavior |
|---|---|---|
| `uniqueId()` | `UUID` | The player's UUID. |
| `name()` | `String` | Current display name. |
| `currentInstanceId()` | `String` | The instance ID the player is on. |
| `currentGroup()` | `String` | The group that instance belongs to. |
| `toView()` | `PlayerView` | Read-only data projection (see below). |
| `sendMessage(String)` | `void` | Sends a chat message. Platform-translated. |
| `transfer(String targetGroup)` | `CompletableFuture<Boolean>` | Queues a transfer to the best instance in `targetGroup`. |
| `transferTo(String targetInstanceId)` | `CompletableFuture<Boolean>` | Queues a transfer to a specific instance. |
| `kick(String reason)` | `void` | Disconnects the player with `reason`. |

`sendMessage` and `kick` are implemented at the platform level — on
Bukkit/Paper they go through the server's player API; the platform
component supplies the concrete behavior.

`transfer` and `transferTo` do not move the player synchronously. The
server plugin posts the request to the controller
(`POST /api/plugin/transfer-to-group` and `POST /api/plugin/transfer`
respectively — see [REST](#in-server-rest-apiplugin)); the proxy then
executes the move. The returned future completes with `true` once the
request is queued, not once the player has arrived. There is no
`hasPermission` on `CloudPlayer`; permission checks live on
`CloudCommandSender` ([below](#cloudcommandsender)).

```java
CloudPlayer p = ctx.players().getPlayer(uuid).orElseThrow();
p.transfer("survival-lobby").thenAccept(queued -> {
    if (queued) ctx.logger().info(p.name() + " queued for survival-lobby");
});
```

### PlayerView

`CloudPlayer.toView()` returns an immutable snapshot — useful when you
need to hand player data to a module or store it.

```java
package me.prexorjustin.prexorcloud.api.domain;

public record PlayerView(
    UUID uuid, String name, String instanceId, String group,
    String proxyInstanceId, Instant connectedAt) {}
```

`proxyInstanceId` and `connectedAt` are `null` when the view is built
on a game-server instance — that data only exists on the proxy side.

## CloudCommandRegistry

```java
package me.prexorjustin.prexorcloud.api.plugin.command;

public interface CloudCommandRegistry {
    void register(LiteralBuilder builder);   // builder path
    void register(Object pojo);              // annotation path
    void register(CloudCommand command);     // programmatic path
    void unregister(String name);
}
```

Obtain it from `ctx.commands()`. Commands are forwarded to the
platform's native command system (Brigadier on Paper, the equivalent on
other platforms). The three `register` overloads correspond to the
three paths below.

### Builder path

Unlimited nesting depth, type-safe arguments, no reflection. The entry
point is `Commands`:

```java
package me.prexorjustin.prexorcloud.api.plugin.command;

public final class Commands {
    public static LiteralBuilder literal(String name, String... aliases);
    public static LiteralBuilder node(Object annotatedPojo);
}
```

- `literal(name, aliases...)` — start a command (or child) node.
- `node(pojo)` — graft an annotation-based POJO into a builder tree at
  any depth (see [mixed](#mixed-annotated-pojos-in-a-builder-tree)).

#### LiteralBuilder

```java
public class LiteralBuilder {
    LiteralBuilder permission(String permission);
    LiteralBuilder alias(String... aliases);
    LiteralBuilder description(String description);
    LiteralBuilder requirePlayer();
    LiteralBuilder requirePlayer(String failMessage);
    LiteralBuilder requireConsole();
    LiteralBuilder requireConsole(String failMessage);
    <T> LiteralBuilder arg(Arg<T> arg);
    LiteralBuilder executes(CommandHandler handler);
    LiteralBuilder then(LiteralBuilder child);
    LiteralBuilder helpPageSize(int size);
    LiteralNode build();
}
```

| Method | Effect | Default |
|---|---|---|
| `permission(node)` | Requires `node` to run this command. | `""` (none) |
| `alias(names...)` | Adds alternate names (additive to constructor aliases). | — |
| `description(text)` | Shown in auto-generated help. | `""` |
| `requirePlayer()` | Rejects console callers. | off |
| `requirePlayer(msg)` | Same, with a custom reject message. | `§cThis command can only be used by players.` |
| `requireConsole()` | Rejects in-game callers. | off |
| `requireConsole(msg)` | Same, with a custom reject message. | `§cThis command can only be used from the console.` |
| `arg(arg)` | Declares one typed argument; consumed in declaration order. | — |
| `executes(handler)` | Sets the handler for this exact path. | none → auto help page |
| `then(child)` | Adds a child literal node (unlimited depth). | — |
| `helpPageSize(n)` | Entries per page in the auto-generated help. | `8` |

`build()` is called by the registry; you rarely call it directly. It
validates argument ordering and throws `IllegalStateException` if:

- a greedy argument is not the last argument, or
- a required argument follows an optional one.

If a node has no `executes(...)` handler, the dispatcher auto-generates
a help page listing that node's direct literal children.

#### Arg

`Arg<T>` is the typed argument descriptor — declaration, parser,
completer, and the typed lookup key in a single object. Declare each as
`static final` and reuse the same instance in both `.arg(...)` and
`ctx.get(...)`.

Static factories:

| Factory | Type | Parses to | Tab completion |
|---|---|---|---|
| `Arg.string(name)` | `Arg<String>` | the raw token | none |
| `Arg.integer(name)` | `Arg<Integer>` | `Integer` (`CommandException` on bad input) | none |
| `Arg.longArg(name)` | `Arg<Long>` | `Long` | none |
| `Arg.bool(name)` | `Arg<Boolean>` | accepts `true/yes/on/1` and `false/no/off/0` | `true`, `false` |
| `Arg.player(name)` | `Arg<CloudPlayer>` | resolved via the platform converter | online player names (global completer) |
| `Arg.group(name)` | `Arg<String>` | the raw token | group names (global completer) |
| `Arg.instance(name)` | `Arg<String>` | the raw token | instance IDs (global completer) |
| `Arg.choices(name, choices...)` | `Arg<String>` | lower-cased; `CommandException` if not in the set | the choice set |
| `Arg.of(name, parser)` | `Arg<T>` | your parser | none |
| `Arg.of(name, parser, completer)` | `Arg<T>` | your parser | your completer |

Fluent modifiers (each returns a **new** immutable `Arg<T>`):

| Modifier | Effect |
|---|---|
| `optional()` | Optional; default value `null`. |
| `optional(T defaultValue)` | Optional with an explicit default. |
| `greedy()` | Consumes all remaining tokens joined by spaces; must be the last arg. |
| `completer(TabCompleter fn)` | Overrides tab completion for this instance. |

```java
private static final Arg<CloudPlayer> TARGET = Arg.player("target");
private static final Arg<String>      REASON =
        Arg.string("reason").greedy().optional("No reason");

ctx.commands().register(
    Commands.literal("cloud", "cl").permission("cloud.admin")
        .then(Commands.literal("player")
            .then(Commands.literal("kick")
                .arg(TARGET).arg(REASON)
                .executes(c -> c.get(TARGET).kick(c.get(REASON)))
            )
        )
);
```

`player`, `group`, and `instance` rely on global completers registered
by the platform adapter at boot via
`Arg.registerGlobalCompleter(kind, completer)`; `player` arguments
additionally resolve through `Arg.registerPlayerConverter(...)`. Plugin
authors do not call these — the platform component wires them.

#### CommandContext

The handler receives a `CommandContext`:

```java
public final class CommandContext {
    CloudCommandSender sender();
    String label();
    List<String> args();

    <T> T get(Arg<T> arg);                 // typed; default if optional+absent
    <T> T getOrDefault(Arg<T> arg, T fallback);

    Optional<String> arg(int index);
    String requireArg(int index);
    String joinArgs(int fromIndex);
    int argInt(int index, int defaultValue);
    long argLong(int index, long defaultValue);
    boolean argBoolean(int index, boolean defaultValue);

    void fail(String message);
    void failIf(boolean condition, String message);
    void failUnless(boolean condition, String message);
    <T> T require(T value, String message);
}
```

- `get(arg)` returns the parsed value for a builder-path argument. If
  the arg was optional and absent, it returns `arg.defaultValue()`. It
  throws `IllegalStateException` if called before argument resolution
  (only possible on annotation-path commands that bypass the
  dispatcher).
- The index-based accessors (`arg(int)`, `argInt`, …) are for the
  programmatic path and raw token access.
- `fail` / `failIf` / `failUnless` / `require` throw `CommandException`
  to abort and send `§c<message>` to the sender. This is intended
  control flow; the stack trace is suppressed.

```java
.executes(c -> {
    CloudPlayer target = c.get(TARGET);
    c.failIf(target.name().equalsIgnoreCase(c.sender().name()),
             "You can't target yourself.");
    target.kick(c.get(REASON));
})
```

#### CloudCommandSender

```java
public interface CloudCommandSender {
    String  name();
    boolean isPlayer();
    boolean isConsole();
    void    sendMessage(String message);
    boolean hasPermission(String permission);
}
```

`hasPermission` is the per-sender permission check. `requirePlayer()` /
`requireConsole()` (and their annotation equivalents) gate on
`isPlayer()` / `isConsole()`.

### Annotation path

Register a `@Command`-annotated POJO. The annotation compiler produces
the same node tree as the builder path.

```java
@Command(name = "message", aliases = {"msg", "tell"})
@Permission("module.message")
@RequirePlayer
public final class MessageCommand {

    private final MessageService service;

    public MessageCommand(MessageService service) {
        this.service = service;
    }

    @Default
    public void run(CommandContext ctx,
                    @Param("target") CloudPlayer target,
                    @Param(value = "text", greedy = true) String text) {
        service.send(ctx.sender(), target, text);
    }
}

ctx.commands().register(new MessageCommand(messageService));
```

Annotations:

| Annotation | Target | Purpose |
|---|---|---|
| `@Command(name, aliases, description)` | class | Marks a root command. `aliases` and `description` default to empty. |
| `@Sub(value, description)` | method or class | Declares a subcommand. On a method = inline sub; on a class = standalone sub (for large trees). A `@Sub` class may itself contain `@Sub` methods (2 levels deep). |
| `@Default` | method | The handler invoked when no subcommand argument matches. If absent, help text is auto-generated from the `@Sub` descriptions. |
| `@Param(value, optional, greedy)` | parameter | Binds a method parameter to an argument by name. The argument type is inferred from the Java parameter type. `optional` and `greedy` default to `false`. |
| `@Permission(value)` | class or method | Required permission node. A `@Sub` without its own `@Permission` inherits the parent `@Command`'s; a `@Sub`-level `@Permission` overrides. |
| `@RequirePlayer(message)` | class | Rejects console callers with `message`. Default `§cThis command can only be used by players.` |
| `@RequireConsole(message)` | class | Rejects player callers with `message`. Default `§cThis command can only be used from the console.` |
| `@SubCompleter(value)` | method | Tab-completion provider for the `@Sub` named `value` (empty `value` targets the root `@Default`). Must return `List<String>` and accept a single `CommandContext`. |

A multi-subcommand class with permission inheritance:

```java
@Command(name = "server", aliases = {"sv"})
@Permission("cloud.server")                 // base permission
public final class ServerCommand {

    @Sub("list")                            // inherits cloud.server
    public void list(CommandContext ctx) { /* ... */ }

    @Sub("delete")
    @Permission("cloud.server.delete")      // overrides
    public void delete(CommandContext ctx, @Param("name") String name) { /* ... */ }

    @Default
    public void help(CommandContext ctx) {
        ctx.sender().sendMessage("§eUsage: /server <list|delete>");
    }
}

ctx.commands().register(new ServerCommand());
```

For the builder path, prefer `Arg<T>` over `@Param` — it gives
compile-time type safety without reflection.

### Mixed — annotated POJOs in a builder tree

`Commands.node(pojo)` slots an annotation-based class at any depth in a
builder tree:

```java
ctx.commands().register(
    Commands.literal("cloud")
        .then(Commands.literal("server")
            .then(Commands.node(new GroupCommand(svc))))   // @Sub-annotated, depth 2
);
```

The POJO is compiled by the same annotation compiler used by
`register(Object)`; the registry resolves it when the builder is built.

### Programmatic path

Register a raw `CloudCommand` for platform-native integrations or
one-off commands:

```java
public interface CloudCommand {
    String name();
    String permission();          // "" means no permission required
    void   execute(CommandContext ctx);
    default List<String> tabComplete(CommandContext ctx) { return List.of(); }
}
```

```java
ctx.commands().register(new CloudCommand() {
    @Override public String name() { return "ping"; }
    @Override public String permission() { return ""; }
    @Override public void execute(CommandContext ctx) {
        ctx.sender().sendMessage("pong");
    }
});
```

### Custom parsers and completers

```java
@FunctionalInterface
public interface ArgParser<T> {
    T parse(String raw, CommandContext ctx) throws CommandException;
}

@FunctionalInterface
public interface TabCompleter {
    List<String> complete(CommandContext ctx, String partial);
}
```

`ArgParser` parses one raw token into `T`; throw `CommandException` to
reject input. `TabCompleter` returns suggestions for `partial` — the
dispatcher applies case-insensitive prefix filtering after your call,
so you may return the full candidate set.

```java
private static final Arg<Duration> DURATION = Arg.of("duration",
    (raw, ctx) -> {
        try { return Duration.parse(raw); }
        catch (Exception e) { throw new CommandException("Bad duration: " + raw); }
    },
    (ctx, partial) -> List.of("PT30S", "PT5M", "PT1H"));
```

### Unregister

```java
ctx.commands().unregister("message");
```

Removes a previously registered command by its primary name.

## Player events

Subscribe through `ctx.events()`
([`EventBus`](/reference/plugin-sdk/plugin-context/)). Player-related
events are records in
`me.prexorjustin.prexorcloud.api.event.events`; each carries a `type()`
string used for dynamic dispatch and SSE streaming.

| Event | Fields | `type()` | Fired when |
|---|---|---|---|
| `PlayerConnectedEvent` | `UUID uuid, String name, String instanceId, String group` | `PLAYER_CONNECTED` | A player connects to any instance in the network. |
| `PlayerDisconnectedEvent` | `UUID uuid, String name, String instanceId, String group` | `PLAYER_DISCONNECTED` | A player disconnects from the network. |
| `PlayerTransferEvent` | `UUID uuid, String name, String fromInstanceId, String toInstanceId` | `PLAYER_TRANSFER` | A player is transferred between instances. |
| `PlayerJourneyEvent` | `PlayerJourneyEntry entry` | `PLAYER_JOURNEY` | A journey entry is appended to the journey log. |

These are cluster-wide events: a server plugin on `lobby-1` receives
`PlayerConnectedEvent` for a join on `survival-3`. Filter to scope.

Fluent subscription:

```java
ctx.events().on(PlayerConnectedEvent.class)
    .filter(e -> e.group().equals("survival-lobby"))
    .subscribe(e -> ctx.logger().info(e.name() + " joined survival-lobby"));
```

Direct subscription with an explicit handle:

```java
EventSubscription sub = ctx.events()
    .subscribe(PlayerDisconnectedEvent.class,
               e -> cache.remove(e.uuid()));
// later:
sub.unsubscribe();
```

`EventBus` also exposes `subscribeByType(String, handler)` for custom
events by their type string, `subscribeAll(handler)` for a catch-all,
and `publish(CloudEvent)` to emit an event. From a plugin, publishing a
`CustomCloudEvent` forwards it to the controller's event bus over REST
(see [`POST /api/plugin/events`](#in-server-rest-apiplugin)). Custom
event types use the `MODULE:ACTION` convention:

```java
ctx.events().publish(new CustomCloudEvent(
    "VOTIFIER:VOTE",
    ctx.self().instanceId(),
    Map.of("player", "Notch", "service", "PlanetMinecraft")));
```

## In-server REST (`/api/plugin`)

The server plugin talks to the controller over a small REST surface.
Most plugin authors use the high-level [`CloudClient`](#cloudclient);
the endpoints below are the wire contract underneath it.

### Transport

The server-side client (`ServerControllerClient`, extending the shared
`BaseControllerClient`) authenticates with a short-lived **workload
token** sent as `Authorization: Bearer <token>`. Behavior:

- All paths are under the `/api/plugin` prefix.
- Mutating `POST`s carry an `X-Prexor-Sequence` monotonic counter and a
  `traceparent` header; the controller validates the sequence.
- On `401`, the client exchanges its token at
  `POST /api/plugin/auth/refresh` and retries the original request
  once. Refresh is single-flight: a burst of concurrent requests
  rotates the token only once.
- Request timeout is 10 s. `GET`s expect `200`; reporting `POST`s are
  fire-and-forget (failures are logged, not thrown).

### Endpoints

| Method · Path | Body | Effect | Notable statuses |
|---|---|---|---|
| `POST /api/plugin/auth/refresh` | — | Rotate the workload token; returns `{ "token": ... }`. | `200` |
| `POST /api/plugin/events/ticket` | — | Issue a one-shot SSE ticket; returns `{ "ticket": ... }`. | `200`, `503` if the ticket manager is unavailable |
| `POST /api/plugin/ready` | `{}` | Mark this instance `RUNNING` (ready for players). | `200` |
| `POST /api/plugin/player-join` | `{ uuid, name, group }` | Add the player to cluster state for this instance. | `200` |
| `POST /api/plugin/player-leave` | `{ uuid }` | Remove the player from cluster state. | `200` |
| `POST /api/plugin/events` | `{ type, data }` | Publish a `CustomCloudEvent` on the controller's bus (source = instance ID). | `200` |
| `GET /api/plugin/instances` | — | List backend (non-proxy) instances visible to the plugin. | `200` |
| `GET /api/plugin/groups` | — | List groups with online counts. | `200` |
| `GET /api/plugin/players` | — | List players across the network. | `200` |
| `POST /api/plugin/transfer` | `{ playerUuid, targetInstanceId }` | Queue a transfer to a specific instance. | `200`, `400` bad UUID, `404` player/target missing |
| `POST /api/plugin/transfer-to-group` | `{ playerUuid, group }` | Queue a transfer to the least-loaded `RUNNING` instance in `group`. | `200`, `404` player/group missing, `409` no running instances |
| `POST /api/plugin/metrics` | metrics snapshot (below) | Record a periodic metrics snapshot. | `200` |
| `POST /api/plugin/message/send` | `{ fromUuid, fromName, toUuid, toName, content, replyToId? }` | Send a cross-network `/msg` via the message module. | `201`, `400` validation, `403` blocked, `503` module not loaded |

The metrics snapshot (`POST /api/plugin/metrics`) accepts these fields
(`InstanceMetricsPayload`):

```text
tps1m, tps5m, tps15m, msptAvg            (double)
heapUsedMb, heapMaxMb, heapCommittedMb,
gcCollections, gcTimeMs                  (long)
threadCount, daemonThreadCount,
playerCount, maxPlayers, worldCount      (int)
totalEntities, totalChunks               (long)
worlds: [{ name, environment,
           entityCount, chunkCount,
           playerCount }]
serverVersion (String), pluginCount (int), uptimeMs (long)
```

### CloudClient

The public client over that surface, from `ctx.client()` (also
`ctx.self().client()`):

```java
package me.prexorjustin.prexorcloud.api.client;

public interface CloudClient {
    String instanceId();
    CompletableFuture<Void> markReady();      // POST /api/plugin/ready
    CompletableFuture<Void> markStopping();
    CompletableFuture<TransferResult> transferPlayer(UUID playerId, String targetGroup);
    CompletableFuture<TransferResult> transferPlayerTo(UUID playerId, String targetInstanceId);
    CompletableFuture<InstanceView> fetchInstance(String instanceId);
    CompletableFuture<Void> reportCrash(String exitCode, String logTail);
}
```

| Method | Maps to | Notes |
|---|---|---|
| `instanceId()` | — | This instance's ID (from `PluginEnv`). |
| `markReady()` | `POST /api/plugin/ready` | Signals readiness; runs async. |
| `markStopping()` | — | No-op on Bukkit-based servers (completed future). |
| `transferPlayer(id, group)` | `POST /api/plugin/transfer-to-group` | Resolves to `TransferResult.success(group)` once queued. |
| `transferPlayerTo(id, instance)` | `POST /api/plugin/transfer` | Resolves to `TransferResult.success(instance)` once queued. |
| `fetchInstance(id)` | `GET /api/plugin/instances` | Filters the list; throws `IllegalArgumentException` if not found. |
| `reportCrash(exitCode, logTail)` | — | No-op on Bukkit-based servers (completed future). |

`TransferResult` reports the queued outcome — success carries the
target, failure carries a reason:

```java
public record TransferResult(boolean success, String targetInstanceId, String failureReason) {
    public static TransferResult success(String targetInstanceId);
    public static TransferResult failure(String reason);
}
```

`CloudPlayer.transfer` / `transferTo` are thin wrappers over
`transferPlayer` / `transferPlayerTo` and return
`CompletableFuture<Boolean>` instead of `TransferResult` — `true` once
the request is queued.

## Worked example

A `/heartbeat` command plus a join listener:

```java
@CloudPlugin(name = "Heartbeat", version = "1.0.0")
public final class HeartbeatPlugin extends CloudPluginBase {

    private final HeartbeatService service = new HeartbeatService();

    @Override
    public void onEnable(CloudPluginContext ctx) {
        ctx.commands().register(
            Commands.literal("heartbeat").permission("heartbeat.use")
                .description("Print heartbeat status")
                .executes(c -> c.sender().sendMessage("Last beat: " + service.lastBeat())));

        ctx.events().on(PlayerConnectedEvent.class)
            .filter(e -> e.instanceId().equals(ctx.self().instanceId()))
            .subscribe(e -> service.recordJoin(e.uuid()));
    }
}
```

## Related

- [CloudPluginContext](/reference/plugin-sdk/plugin-context/) —
  `players()`, `commands()`, `events()`, `client()`, `self()`.
- [@CloudPlugin annotation](/reference/plugin-sdk/cloudplugin-annotation/)
  — the plugin manifest annotation.
