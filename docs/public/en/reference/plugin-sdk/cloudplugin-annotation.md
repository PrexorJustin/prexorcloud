---
title: "@CloudPlugin annotation"
description: "Every @CloudPlugin attribute, the supported platforms and their generated descriptors, the bridge lifecycle, api-version inference, and the build wiring that connects them."
---

`@CloudPlugin` marks a `CloudPluginBase` subclass as a PrexorCloud plugin. The
`CloudPluginProcessor` annotation processor reads it at compile time and
generates two outputs per target platform: a platform bridge class that boots
your plugin into that platform's lifecycle, and the platform's descriptor file
(`plugin.yml`, `paper-plugin.yml`, `velocity-plugin.json`, or `extension.yml`).

This page documents every annotation attribute, every supported platform and
its generated output, the bridge lifecycle, `api-version` inference, and the
Gradle convention plugins that wire the right `-Acloud.platform` argument.

For per-Minecraft-version adapter dispatch inside a single jar, see
[`@ForVersion` and version dispatch](#forversion-and-version-dispatch).

## Annotation contract

```java
package me.prexorjustin.prexorcloud.api.plugin.annotation;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CloudPlugin {
    String   name();
    String   version();
    String   description()      default "";
    String[] authors()          default {};
    String[] dependencies()     default {};
    String[] softDependencies() default {};
    String   apiVersion()       default "1.21";
}
```

The annotated type must extend `CloudPluginBase`. If it does not, the processor
emits a compile **error** — `@CloudPlugin on <Type> has no effect: the class
must extend CloudPluginBase.` — and generates nothing.

`@Retention(RUNTIME)` is required: the generated bridge resolves the impl class
by name, and `@ForVersion` dispatch reads adapter annotations reflectively at
runtime.

### Attributes

| Attribute | Type | Default | Required | Purpose |
|---|---|---|---|---|
| `name` | `String` | — | yes | Plugin name as the platform sees it. Also the descriptor `name`, and (kebab-cased) the Velocity/Geyser `id`. |
| `version` | `String` | — | yes | Plugin version. Written verbatim into every descriptor. |
| `description` | `String` | `""` | no | Human-readable description. Omitted from the descriptor when empty. |
| `authors` | `String[]` | `{}` | no | Author list. Omitted from the descriptor when empty. |
| `dependencies` | `String[]` | `{}` | no | Hard dependencies — must load before this plugin. `PrexorCloud` is **always** added automatically; do not list it. |
| `softDependencies` | `String[]` | `{}` | no | Soft dependencies — loaded before this plugin if present, ignored otherwise. |
| `apiVersion` | `String` | `"1.21"` | no | Minimum Bukkit/Paper `api-version`. Ignored on Velocity, BungeeCord, and Geyser. Can be lowered automatically by `@ForVersion` inference — see [api-version inference](#api-version-inference). |

#### `name`

Used three ways depending on platform:

- Bukkit/Paper/Folia/BungeeCord: written as the descriptor `name:` field
  verbatim.
- Velocity: written as `name`, and lowercased with spaces replaced by `-` to
  form the plugin `id` (`@Plugin(id = ...)` and `velocity-plugin.json` `id`).
- Geyser: written as `name`, and kebab-cased to form the extension `id` in
  `extension.yml`.

#### `dependencies` and `softDependencies`

`PrexorCloud` is injected as a required hard dependency that loads before your
plugin, so the context factory is ready by the time `onEnable` runs. The
descriptor format varies by platform:

| Platform | Hard deps | Soft deps | `PrexorCloud` auto-added |
|---|---|---|---|
| Paper (`paper-plugin.yml`) | `dependencies.server` (`required: true`, `join-classpath: true`) | `dependencies.server` (`required: false`) | yes |
| Spigot / Folia (`plugin.yml`) | `depend` | `softdepend` | yes (`depend: [PrexorCloud, …]`) |
| Velocity (`velocity-plugin.json`) | `dependencies` (`optional: false`) | `dependencies` (`optional: true`) | yes (`{"id": "prexorcloud", …}`) |
| BungeeCord (`plugin.yml`) | `depends` | `softDepends` | yes (`depends: [PrexorCloud, …]`) |
| Geyser (`extension.yml`) | — (Geyser has no dependency graph) | — | n/a |

On Velocity, dependency entries are lowercased to match Velocity's `id`
convention. The Paper descriptor sets `join-classpath: true` on `PrexorCloud`
and every declared dependency so your plugin can reach the PrexorCloud API
without routing through the platform classloader.

#### `authors`

Rendered per platform: Bukkit/Paper/Folia use `authors: [a, b]`; Velocity has no
authors field (omitted); BungeeCord supports a single author, so only
`authors()[0]` is written as `author:`; Geyser writes `authors: ["a", "b"]`.

## Supported platforms

The processor supports six platform identifiers. The target is resolved at
compile time — see [platform resolution](#platform-resolution).

| `cloud.platform` | Aliases | Bridge class | `extends` / `implements` | Descriptor |
|---|---|---|---|---|
| `paper` | — | `<Pascal>CloudBridge` | `JavaPlugin` | `paper-plugin.yml` |
| `spigot` | — | `<Pascal>CloudBridge` | `JavaPlugin` | `plugin.yml` |
| `folia` | — | `<Pascal>FoliaBridge` | `JavaPlugin` | `plugin.yml` (`folia-supported: true`) |
| `velocity` | — | `<Pascal>VelocityBridge` | `@Plugin`-annotated | `velocity-plugin.json` |
| `bungeecord` | `bungee`, `waterfall` | `<Pascal>BungeeBridge` | `net.md_5.bungee.api.plugin.Plugin` | `plugin.yml` |
| `bedrock-geyser` | `geyser` | `<Pascal>GeyserBridge` | `org.geysermc.geyser.api.extension.Extension` | `extension.yml` |

`<Pascal>` is the simple name of your `@CloudPlugin` class. A class named
`WelcomePlugin` compiled for `paper` produces `WelcomePluginCloudBridge`.

The difference between `paper` and `spigot` is the descriptor: `paper` emits the
modern `paper-plugin.yml` (Paper 1.19.3+, `dependencies.server` map format with
`join-classpath`), `spigot` emits the legacy `plugin.yml` with `depend` /
`softdepend` lists. Both bridges extend `JavaPlugin`. `folia` uses the legacy
`plugin.yml` plus `folia-supported: true`.

An unrecognized `cloud.platform` value is a compile **error**: `Unknown
cloud.platform value: '<x>'. Expected: paper, spigot, folia, velocity,
bungeecord, bedrock-geyser.`

> Fabric and NeoForge mods do not go through `@CloudPlugin`. They are separate
> mod jars under `cloud-plugins/server/{fabric,neoforge}`, not
> annotation-processor targets. `@CloudPlugin` covers Bukkit-family servers,
> Velocity/BungeeCord proxies, and Geyser extensions only.

### Platform resolution

`CloudPluginProcessor` resolves the target platform in priority order:

1. **Explicit `-Acloud.platform=<value>` compiler argument.** Trimmed and
   lowercased. Takes precedence over everything else. This is what the Gradle
   convention plugins set.
2. **Classpath auto-detection**, by probing for a marker type, in this order:

   | Probe type | Resolved platform |
   |---|---|
   | `org.geysermc.geyser.api.GeyserApi` | `bedrock-geyser` |
   | `com.velocitypowered.api.proxy.ProxyServer` | `velocity` |
   | `net.md_5.bungee.api.plugin.Plugin` | `bungeecord` |
   | `io.papermc.paper.threadedregions.RegionizedServer` | `folia` |
   | `org.bukkit.plugin.java.JavaPlugin` | `paper` |

   Geyser is probed first so a Geyser-on-Velocity or Geyser-on-Paper build does
   not silently resolve to the host platform.
3. **Default `paper`**, with a build-time **warning**: `[CloudPlugin] Cannot
   detect platform from classpath for <Type>; defaulting to 'paper'. Add
   -Acloud.platform=<platform> to suppress this warning.`

In practice you always hit case 1 — every convention plugin sets the flag
explicitly. Auto-detection is the fallback for hand-rolled builds.

## Bridge lifecycle

The generated bridge holds a private `impl` field of your `@CloudPlugin` type
and forwards platform lifecycle callbacks to `CloudPluginBase`. `CloudPluginBase`
is platform-agnostic and does **not** extend `JavaPlugin`:

```java
public abstract class CloudPluginBase {
    public abstract void onEnable(CloudPluginContext ctx);
    public void onDisable() {}
    public void onReload(CloudPluginContext ctx) {}

    protected final <T> T adapt(Class<T> type);
    protected final <T> T adapt(Class<T> type, Class<?> container);
    protected final VersionDispatcher versions();
}
```

| Method | Called by | Notes |
|---|---|---|
| `onEnable(CloudPluginContext)` | bridge, once, on platform enable/init | Abstract — you must implement it. Receives the context from `CloudApiProvider.createPluginContext(bridge)`. |
| `onDisable()` | bridge, on platform disable/shutdown | Optional override. Only invoked if `impl` was constructed. |
| `onReload(CloudPluginContext)` | not invoked by the generated bridges | Hook for plugins that wire their own reload command. No platform calls it automatically. |
| `adapt(...)` / `versions()` | your code | `@ForVersion` dispatch — see below. |

What each bridge does on enable/init:

- **Paper / Spigot / Folia** (`onEnable`): construct `impl`, call
  `impl.initVersionDispatcher(new VersionDispatcher(Bukkit.getServer().getBukkitVersion()))`,
  auto-register `impl` as a Bukkit `Listener` if it implements one
  (`registerEvents`), then `impl.onEnable(CloudApiProvider.createPluginContext(this))`.
  On `onDisable`, call `impl.onDisable()` if `impl != null`.
- **Velocity**: construct `impl` and the `VersionDispatcher` in the `@Inject`
  constructor (off `server.getVersion().getVersion()`). On
  `ProxyInitializeEvent`, call `impl.onEnable(...)` then register `impl` on the
  proxy event manager so its `@Subscribe` handlers fire. On
  `ProxyShutdownEvent`, call `impl.onDisable()`.
- **BungeeCord** (`onEnable`): construct `impl`, init the `VersionDispatcher`
  off `ProxyServer.getInstance().getVersion()`, then `impl.onEnable(...)`. On
  `onDisable`, call `impl.onDisable()` if `impl != null`.
- **Geyser**: on `GeyserPostInitializeEvent`, construct `impl`, call
  `impl.onEnable(...)`, register `impl` on the extension's event bus. On
  `GeyserShutdownEvent`, call `impl.onDisable()` if `impl != null`. **No
  `VersionDispatcher` is wired** — a Geyser extension runs inside Geyser's own
  runtime regardless of host MC version, so `@ForVersion` dispatch is not
  meaningful. Calling `adapt(...)` on Geyser throws `IllegalStateException`.

### `CloudPluginContext`

`onEnable` and `onReload` receive a `CloudPluginContext`, the entry point to the
Cloud APIs:

```java
public interface CloudPluginContext {
    InstanceContext       self();       // this plugin's own server instance
    EventBus              events();     // subscribe to cluster events
    CloudCommandRegistry  commands();   // register @Command classes
    PlayerManager         players();    // players online on this instance
    PluginScheduler       scheduler();  // Folia-safe task scheduler
    CloudClient           client();     // low-level cloud client
    Logger                logger();     // java.util.logging.Logger
}
```

See [CloudPluginContext](/reference/plugin-sdk/plugin-context/) for the full
surface.

## api-version inference

`apiVersion` defaults to `"1.21"` and is only written to Bukkit-family
descriptors (`paper-plugin.yml`, `plugin.yml`). It is ignored on Velocity,
BungeeCord, and Geyser.

When your plugin uses `@ForVersion` adapters, the processor scans the entire
class hierarchy for `@ForVersion(min=...)` values and writes **the lower of**
the declared `apiVersion` and the lowest `min` it finds. You therefore rarely
set `apiVersion` by hand — the lowest version you actually support is inferred.

Example: a plugin declaring `apiVersion = "1.21"` but containing
`@ForVersion(min = "1.16")` and `@ForVersion(min = "1.21")` adapters generates
`api-version: '1.16'`. Comparison is on the minor (then patch) component:
`"1.16"` < `"1.21"`. `@ForVersion(fallback = true)` adapters and adapters with
an empty `min` are ignored by inference.

## `@ForVersion` and version dispatch

`@ForVersion` lets a single shaded jar carry multiple adapter implementations
and pick the right one for the running Minecraft (or proxy) version at startup.

```java
package me.prexorjustin.prexorcloud.api.client.version;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ForVersion {
    String  min()      default "";   // inclusive; ignored when fallback=true
    String  max()      default "";   // inclusive; empty = unbounded; ignored when fallback=true
    boolean fallback() default false;
}
```

Annotate **nested classes** of a container type (interface or class). Each
implements the container type and targets a version range:

```java
public interface WelcomeHandler {

    @ForVersion(min = "1.21")
    class Modern implements WelcomeHandler { /* 1.21+ APIs */ }

    @ForVersion(min = "1.17", max = "1.20")
    class Legacy implements WelcomeHandler { /* pre-1.21 APIs */ }

    @ForVersion(fallback = true)
    class Default implements WelcomeHandler { /* unknown future versions */ }
}
```

The processor emits a build-time **warning** if a container has `@ForVersion`
adapters but no `@ForVersion(fallback = true)`: servers running outside the
covered ranges would otherwise throw `UnsupportedOperationException` at runtime.
At most one fallback per container is allowed; `min` and `max` are ignored when
`fallback = true`.

### `VersionDispatcher`

The runtime that drives selection. The bridge constructs it from the running
version string and injects it via `initVersionDispatcher`; you reach it through
`adapt(...)` or `versions()`.

```java
package me.prexorjustin.prexorcloud.api.client.version;

public final class VersionDispatcher {

    public VersionDispatcher(String runningVersion);   // e.g. "1.21.4"

    public int     major();             // see naming note below
    public int     minor();             // see naming note below
    public String  versionString();     // the raw string passed in

    public boolean atLeast(String version);          // running >= version
    public boolean atMost(String version);           // running <= version
    public boolean matches(String min, String max);  // atLeast(min) && (max empty || atMost(max))

    public <T> T resolve(Class<T> type);                  // type is its own container
    public <T> T resolve(Class<T> type, Class<?> container);
}
```

> Naming note: parsing drops the leading `1`. For `"1.21.4"`, `major()` returns
> `21` (the Minecraft minor-version family) and `minor()` returns `4` (the
> patch). `"1.20"` parses to `major()=20`, `minor()=0`.
> `"1.21.4-R0.1-SNAPSHOT"` parses to `21`, `4`. All comparison methods compare
> the family number first, then the patch.

### Selection order

1. Collect every `@ForVersion` nested class of the container that `implements`
   the requested type and whose range `matches(min, max)` the running version.
2. The candidate with the **highest** `min` wins (greedy best-fit).
3. If no range matched, use the `@ForVersion(fallback = true)` class.
4. If neither exists, throw `UnsupportedOperationException` naming the type, the
   container, the running version, and the covered ranges, with the hint to add
   `@ForVersion(fallback = true)`.

The selected adapter is instantiated via its **no-arg constructor**; a missing
or failing constructor surfaces as a `RuntimeException` wrapping the
`ReflectiveOperationException`. Every decision is logged at `FINE` via JUL so
operators can confirm the chosen adapter at startup.

### Calling dispatch from your plugin

`CloudPluginBase` exposes two convenience methods that route through the injected
dispatcher:

```java
protected final <T> T adapt(Class<T> type);                  // type acts as its own container
protected final <T> T adapt(Class<T> type, Class<?> container);
```

Both throw `IllegalStateException` if no dispatcher was initialized (Geyser, or
a hand-rolled bridge that skipped `initVersionDispatcher`).

```java
@CloudPlugin(name = "welcome", version = "1.0.0")
public final class WelcomePlugin extends CloudPluginBase {

    private WelcomeHandler handler;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.handler = adapt(WelcomeHandler.class);   // right adapter for the running MC version
    }
}
```

## Build wiring

A `@CloudPlugin` jar needs three things on the compile classpath: the
`cloud-api` dependency, `cloud-api` registered as an `annotationProcessor`, and
the `-Acloud.platform=<platform>` compiler argument. The build-logic convention
plugins bundle all three (plus Java 21 and the Shadow plugin for the fat jar).

| Convention plugin id | Sets `cloud.platform` | Platform dependency |
|---|---|---|
| `prexorcloud.plugin-paper` | `paper` | Paper API 1.20 (`paperApi120`) |
| `prexorcloud.plugin-paper-1-21` | `paper` | Paper API 1.21 (`paperApi121`) |
| `prexorcloud.plugin-spigot` | `spigot` | Spigot API |
| `prexorcloud.plugin-folia` | `folia` | Paper API (Folia shares the artifact) |
| `prexorcloud.plugin-velocity` | `velocity` | Velocity API (compile + processor) |
| `prexorcloud.plugin-bungeecord` | `bungeecord` | BungeeCord API |
| `prexorcloud.plugin-bedrock-geyser` | `bedrock-geyser` | Geyser API (compile + processor) |

Minimal Paper plugin build script:

```kotlin
plugins {
    id("prexorcloud.plugin-paper")
}
```

That single line applies `prexorcloud.java21-compat`, `com.gradleup.shadow`, the
Paper API as `compileOnly`, `cloud-api` as both `compileOnly` and
`annotationProcessor`, and `-Acloud.platform=paper`.

### Velocity processor conflict

`velocity-api` ships its own annotation processor that competes with
`CloudPluginProcessor` (which already writes a complete `velocity-plugin.json`).
Exclude it to keep compilation one-pass:

```kotlin
plugins {
    id("prexorcloud.plugin-velocity")
}

configurations.named("annotationProcessor") {
    exclude(group = "com.velocitypowered", module = "velocity-api")
}
```

`prexorctl plugin new --platform=velocity` writes this exclusion for you.

### Scaffolding with `prexorctl plugin new`

`prexorctl plugin new <name>` generates a single-platform plugin subproject and
wires it into `java/settings.gradle.kts`.

```bash
prexorctl plugin new welcome --platform=paper --mc-version=1.21
```

```text
→ new plugin: welcome
  platform   paper (prexorcloud.plugin-paper-1-21)
  package    me.prexorjustin.prexorcloud.plugins.welcome
  dest       java/cloud-plugin/cloud-plugin-welcome
  files      2
  ✓ patched settings.gradle.kts (+1 include)

next:
  cd java && ./gradlew :cloud-plugin:cloud-plugin-welcome:shadowJar
```

Flags:

| Flag | Required | Default | Purpose |
|---|---|---|---|
| `--platform` | yes | — | `paper`, `spigot`, `folia`, `velocity`, or `bungeecord`. |
| `--mc-version` | no | `1.20` | Paper only: `1.20` → `prexorcloud.plugin-paper`, `1.21` → `prexorcloud.plugin-paper-1-21`. Ignored on other platforms. Any other value is an error. |
| `--package` | no | `me.prexorjustin.prexorcloud.plugins.<name>` | Override the generated Java package. |
| `--description` | no | `<Pascal> — standalone PrexorCloud plugin.` | Written into the `@CloudPlugin` annotation. |
| `--author` | no | `PrexorCloud` | Written into `authors`. |
| `--repo-root` | no | discovered upward from cwd | Repo root override. |
| `--force` | no | `false` | Overwrite an existing plugin directory instead of failing. |
| `--dry` | no | `false` | Print what would happen without writing files. |

The scaffolder emits two files under
`java/cloud-plugin/cloud-plugin-<name>/`: a `build.gradle.kts` applying the
matching convention plugin, and one `<Pascal>Plugin.java` extending
`CloudPluginBase` with a `@CloudPlugin` annotation and a starter `onEnable`. It
patches `java/settings.gradle.kts` under the `// ---- PLUGINS ---- //` anchor;
if that anchor is missing, the command fails rather than inventing it. The name
is validated as kebab-case (`^[a-z][a-z0-9-]*$`); anything else is rejected.

`prexorctl plugin new` does **not** scaffold Geyser plugins — Geyser is a
processor target but not a `--platform` choice. Author a Geyser plugin by hand
with `id("prexorcloud.plugin-bedrock-geyser")`.

## End-to-end example

```java
package me.prexorjustin.prexorcloud.plugins.welcome;

import me.prexorjustin.prexorcloud.api.client.version.ForVersion;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginBase;
import me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext;
import me.prexorjustin.prexorcloud.api.plugin.annotation.CloudPlugin;
import me.prexorjustin.prexorcloud.api.plugin.player.CloudPlayer;
import me.prexorjustin.prexorcloud.api.event.events.PlayerConnectedEvent;

@CloudPlugin(
    name = "welcome",
    version = "1.0.0",
    description = "Greets players on join.",
    authors = {"PrexorCloud"})
public final class WelcomePlugin extends CloudPluginBase {

    public interface Greeter {

        void greet(CloudPlayer player);

        @ForVersion(min = "1.21")
        final class Modern implements Greeter {
            @Override public void greet(CloudPlayer p) {
                p.sendMessage("§b§lWelcome, " + p.name());   // Adventure-aware
            }
        }

        @ForVersion(min = "1.17", max = "1.20")
        final class Legacy implements Greeter {
            @Override public void greet(CloudPlayer p) {
                p.sendMessage("Welcome, " + p.name());
            }
        }

        @ForVersion(fallback = true)
        final class Default implements Greeter {
            @Override public void greet(CloudPlayer p) {
                p.sendMessage("Welcome.");
            }
        }
    }

    @Override
    public void onEnable(CloudPluginContext ctx) {
        Greeter greeter = adapt(Greeter.class);
        ctx.events().on(PlayerConnectedEvent.class).subscribe(e ->
            ctx.players().getPlayer(e.uuid()).ifPresent(greeter::greet));
    }
}
```

Build and ship:

```bash
cd java && ./gradlew :cloud-plugin:cloud-plugin-welcome:shadowJar
# shaded jar lands in build/libs/ — drop it into the server's plugins/ folder
```

## Next

- [CloudPluginContext](/reference/plugin-sdk/plugin-context/) — the full API
  surface handed to `onEnable`.
- [Plugin SDK index](/reference/plugin-sdk/) — quick-start.
- [Concepts → Plugin packaging](/concepts/plugins/) — plugin vs module.
