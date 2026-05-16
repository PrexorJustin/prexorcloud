---
title: Players + Commands
description: PlayerManager handle for the on-server roster and the CloudCommandRegistry — builder, annotation, and programmatic command registration paths.
---

Two cross-platform surfaces: `PlayerManager` for the player roster
on this instance, and `CloudCommandRegistry` for slash commands.
Both are reached through
[`CloudPluginContext`](/reference/plugin-sdk/plugin-context/).

## What you'll learn

- The `PlayerManager` and `CloudPlayer` API.
- Three ways to register commands: builder, annotation, and
  programmatic.

## PlayerManager

```java
package me.prexorjustin.prexorcloud.api.plugin.player;

public interface PlayerManager {
    Optional<CloudPlayer> getPlayer(UUID uniqueId);
    Optional<CloudPlayer> getPlayer(String name);
    Collection<CloudPlayer> onlinePlayers();
    int onlineCount();
}
```

`getPlayer` is the canonical lookup; treat the `Optional` seriously —
players disconnect at any moment.

### CloudPlayer

```java
public interface CloudPlayer {
    UUID    uniqueId();
    String  name();
    String  group();           // group of the instance the player is on
    String  instanceId();
    void    sendMessage(String message);
    void    kick(String reason);
    void    transfer(String targetGroup);     // proxy-aware, requires proxy plugin
    boolean hasPermission(String node);
}
```

`sendMessage` and `kick` are platform-translated — on Paper they go
through the Adventure component path, on Velocity/Bungee they go
through the proxy's player API.

## CloudCommandRegistry

```java
package me.prexorjustin.prexorcloud.api.plugin.command;

public interface CloudCommandRegistry {
    void register(LiteralBuilder builder);
    void register(Object pojo);
    void register(CloudCommand command);
    void unregister(String name);
}
```

Commands are forwarded to the platform's native command system —
Brigadier on Paper, the equivalent on Velocity / Bungee.

### Builder path — recommended

Unlimited depth, type-safe args. Define `Arg` instances once as
`static final` and reuse them:

```java
private static final Arg<CloudPlayer> TARGET = Arg.player("target");
private static final Arg<String>      REASON = Arg.string("reason").greedy().optional("No reason");

ctx.commands().register(
    Commands.literal("cloud").permission("cloud.admin")
        .then(Commands.literal("player")
            .then(Commands.literal("kick")
                .arg(TARGET).arg(REASON)
                .executes(c -> c.get(TARGET).kick(c.get(REASON)))
            )
        )
);
```

`c.get(arg)` is the typed accessor for parsed args; the registry
generates Brigadier-equivalent suggestions automatically.

### Annotation path — POJO with zero boilerplate

```java
@Command(name = "message", aliases = {"msg", "tell"})
@RequirePlayer
public final class MessageCommand {

    private final MessageService service;

    public MessageCommand(MessageService service) {
        this.service = service;
    }

    @Default
    public void run(CommandContext ctx,
                    @Param("target") CloudPlayer target,
                    @Param("text") @Greedy String text) {
        service.send(ctx.player(), target, text);
    }
}

ctx.commands().register(new MessageCommand(messageService));
```

The annotation processor compiles the POJO down to the same node
tree the builder path produces.

### Mixed — annotated POJOs nested in a builder tree

```java
ctx.commands().register(
    Commands.literal("cloud")
        .then(Commands.node(new MemberCommand(svc)))   // @Sub-annotated class
);
```

`Commands.node(...)` is the bridge — the annotation tree is grafted
into the builder tree at that point.

### Programmatic path

```java
ctx.commands().register(new CloudCommand() {
    @Override public String name() { return "raw"; }
    @Override public boolean execute(CloudCommandSender s, String[] args) { ... }
});
```

For platform-native integrations or one-off raw commands. The other
two paths cover everything else.

## Permissions, console, and player guards

| Annotation | Effect |
|---|---|
| `@Permission("node")` | Auto-rejects callers without the permission. |
| `@RequirePlayer` | Console rejected. |
| `@RequireConsole` | In-game callers rejected. |

These work on annotated POJO commands; the builder path uses
`.permission(...)` and dedicated guards on the `LiteralBuilder` API.

## Example

Bind a `/heartbeat` command to the heartbeat plugin:

```java
@Command(name = "heartbeat", description = "Print heartbeat status")
@Permission("heartbeat.use")
public final class HeartbeatCommand {

    private final HeartbeatService service;

    public HeartbeatCommand(HeartbeatService service) {
        this.service = service;
    }

    @Default
    public void run(CommandContext ctx) {
        ctx.sender().sendMessage("Last beat: " + service.lastBeat());
    }
}

// in onEnable:
ctx.commands().register(new HeartbeatCommand(service));
```

## Next up

- [CloudPluginContext](/reference/plugin-sdk/plugin-context/) — `players()`
  and `commands()`.
- [@CloudPlugin Annotation](/reference/plugin-sdk/cloudplugin-annotation/)
  — annotation reference for the plugin itself.
