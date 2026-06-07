---
title: Plugin SDK
description: Java SDK reference for standalone @CloudPlugin jars — single-platform Minecraft / proxy plugins that drop into a server's plugins/ folder and connect to the controller.
---

The **Plugin SDK** is the Java API for the standalone `@CloudPlugin`
path. A plugin here is one shaded jar with a `@CloudPlugin` annotation
that drops directly into a Paper / Spigot / Folia / Velocity /
BungeeCord server's `cloud-plugins/` folder and connects to the controller
via `cloud-api`.

A plugin is **not** a module — it has no `module.yaml`, no frontend, no
per-platform variants. Pick this when you only need in-game / in-proxy
behaviour on one platform; pick a [module](/reference/module-sdk/) when
you need cluster-wide state, REST endpoints, dashboard UI, or
coordination across nodes.

## What you'll learn

- The five SDK pages that make up the plugin surface.
- The single-class shape of a `@CloudPlugin`.
- The `VersionDispatcher` pattern for supporting multiple Minecraft
  versions from one jar.

## SDK pages

| Page | Surface |
|---|---|
| [CloudPluginContext](/reference/plugin-sdk/plugin-context/) | Top-level handle — events, players, commands, scheduler, logger. |
| [EventHandler](/reference/plugin-sdk/event-handler/) | Subscribing to cluster events from a plugin. |
| [Players + Commands](/reference/plugin-sdk/players-and-commands/) | `PlayerManager` and the command registry (builder + annotation paths). |
| [@CloudPlugin Annotation](/reference/plugin-sdk/cloudplugin-annotation/) | `@CloudPlugin`, `@ForVersion`, and `VersionDispatcher`. |

## Hello-world plugin

```java
package com.example.welcome;

import me.prexorjustin.prexorcloud.api.client.version.ForVersion;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;
import me.prexorjustin.prexorcloud.api.event.PlayerConnectedEvent;

@CloudPlugin(
    name = "welcome",
    version = "1.0.0",
    authors = {"PrexorCloud"})
public final class WelcomePlugin extends CloudPluginBase {

    @Override
    public void onEnable(CloudPluginContext ctx) {
        ctx.events().on(PlayerConnectedEvent.class).subscribe(e -> {
            ctx.players().getPlayer(e.uniqueId())
               .ifPresent(p -> p.sendMessage("Welcome, " + p.name() + "!"));
        });
        ctx.logger().info("welcome plugin enabled");
    }
}
```

Build:

```bash
prexorctl plugin new welcome --platform paper --mc-version 1.21
cd java && ./gradlew :cloud-plugin:cloud-plugin-welcome:shadowJar
```

The annotation processor generates the right platform descriptor
(`plugin.yml` for Paper / Spigot / Folia, `velocity-plugin.json` for
Velocity, `bungee.yml` for BungeeCord) plus a tiny bridge class that
boots the plugin into the platform's lifecycle.

## Versioning across Minecraft releases

A single jar can serve multiple Minecraft versions through `@ForVersion`:

```java
public interface WelcomeHandler {

    @ForVersion(min = "1.21")
    class Modern implements WelcomeHandler { /* uses 1.21 APIs */ }

    @ForVersion(min = "1.17", max = "1.20")
    class Legacy implements WelcomeHandler { /* uses pre-1.21 APIs */ }

    @ForVersion(fallback = true)
    class Default implements WelcomeHandler { /* unknown future versions */ }
}

WelcomeHandler handler = adapt(WelcomeHandler.class);
```

`adapt(...)` lives on `CloudPluginBase`; it picks the highest-`min`
class whose range covers the running server. See
[@CloudPlugin Annotation](/reference/plugin-sdk/cloudplugin-annotation/)
for the full dispatcher contract.

## Conventions

- **Logging**: `java.util.logging.Logger` via `ctx.logger()` — JUL is
  the lowest common denominator across Bukkit and Velocity. Modules
  use SLF4J; plugins use JUL.
- **Threading**: never call platform APIs off the main thread (Folia
  excepted). Use `ctx.scheduler()` to bounce work back; it's
  Folia-safe.
- **Events**: subscribe in `onEnable`, unsubscribe is automatic on
  plugin disable — the bus tracks subscriptions per-plugin.

## Next up

- [CloudPluginContext](/reference/plugin-sdk/plugin-context/) — start
  here.
- [@CloudPlugin Annotation](/reference/plugin-sdk/cloudplugin-annotation/)
  — annotation reference.
- [Concepts → Plugins vs Modules](/concepts/plugins/)
