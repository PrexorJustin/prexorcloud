---
title: "@CloudPlugin Annotation"
description: "@CloudPlugin metadata, @ForVersion adapter dispatch, and the VersionDispatcher pattern that lets a single jar serve multiple Minecraft versions."
---

`@CloudPlugin` is the marker annotation an annotation processor reads
to generate the right platform descriptor (`plugin.yml`,
`velocity-plugin.json`, etc.) and a thin bridge class that boots
your plugin into the platform's lifecycle. `@ForVersion` and
`VersionDispatcher` give you per-Minecraft-version adapter dispatch
inside a single shaded jar.

## What you'll learn

- Every field on `@CloudPlugin`.
- How the annotation processor picks a platform via the
  `cloud.platform` compiler argument.
- The `@ForVersion` selection rules and the `VersionDispatcher` API.

## `@CloudPlugin`

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

| Field | Purpose |
|---|---|
| `name` | Plugin name as the platform sees it. Doubles as the platform descriptor filename anchor. |
| `version` | Plugin version (semver). |
| `description` | Human-readable description; written into the descriptor. |
| `authors` | Author list. |
| `dependencies` | Hard dependencies — must be loaded before this plugin. **PrexorCloud is added automatically.** |
| `softDependencies` | Loaded before this plugin if present, ignored otherwise. |
| `apiVersion` | Minimum Bukkit/Paper API version. Ignored on Velocity / Bungee. |

### `apiVersion` inference

If your plugin uses `@ForVersion` adapters, the processor automatically
infers the effective `apiVersion` from the lowest `@ForVersion(min=...)`
it finds. The processor uses whichever is **lower** between the
inferred minimum and the explicit field, so you typically don't need
to set this manually.

Example: a plugin with `@ForVersion(min="1.16")` and
`@ForVersion(min="1.21")` will have `api-version: '1.16'` in the
generated `plugin.yml` regardless of what `apiVersion` is set to.

### Generated artifacts

Per `cloud.platform=<paper|spigot|folia|velocity|bungeecord>` compiler
arg, the processor generates:

- A platform bridge class: `<Pascal>CloudBridge` (Paper / Spigot),
  `<Pascal>FoliaBridge`, `<Pascal>VelocityBridge`, `<Pascal>BungeeBridge`.
- The platform descriptor: `plugin.yml` for Paper / Spigot / Folia,
  `velocity-plugin.json` for Velocity, `bungee.yml` for Bungee.

The `prexorcloud.plugin-<platform>` Gradle convention plugin (applied
automatically by `prexorctl plugin new`) wires the right `cloud.platform`
arg.

## `@ForVersion`

```java
package me.prexorjustin.prexorcloud.api.client.version;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ForVersion {
    String  min()      default "";   // inclusive
    String  max()      default "";   // inclusive; empty = unbounded
    boolean fallback() default false;
}
```

Annotate **nested classes** inside an interface or container class.
Each nested class targets a Minecraft version range.

```java
public interface WelcomeHandler {

    @ForVersion(min = "1.21")
    class Modern implements WelcomeHandler { /* uses 1.21 APIs */ }

    @ForVersion(min = "1.17", max = "1.20")
    class Legacy implements WelcomeHandler { /* uses pre-1.21 APIs */ }

    @ForVersion(fallback = true)
    class Default implements WelcomeHandler { /* unknown future versions */ }
}
```

`fallback = true` is used when no versioned class matches — this
prevents `UnsupportedOperationException` on unknown future Minecraft
releases. At most one fallback per container is allowed; `min` and
`max` are ignored when `fallback=true`.

## VersionDispatcher

The runtime that drives `@ForVersion` selection.

```java
package me.prexorjustin.prexorcloud.api.client.version;

public final class VersionDispatcher {

    public VersionDispatcher(String runningVersion);   // e.g. "1.21.4"

    public int     major();
    public int     minor();
    public String  versionString();

    public boolean atLeast(String version);
    public boolean atMost(String version);
    public boolean matches(String min, String max);

    public <T> T resolve(Class<T> type);                          // type also acts as container
    public <T> T resolve(Class<T> type, Class<?> container);
}
```

### Selection order

1. Collect every nested class of the container annotated with
   `@ForVersion` whose range covers the running server.
2. The class with the **highest** `min` version wins (greedy best-fit).
3. If no class matches the range, a class annotated with
   `@ForVersion(fallback = true)` is used.
4. If neither exists, `UnsupportedOperationException` is thrown with
   a message listing which versions are covered.

Every dispatch decision is logged at `FINE` via JUL so administrators
can confirm which adapter was chosen at startup.

### Calling from your plugin

`CloudPluginBase` exposes two convenience methods that route through
the dispatcher injected by the bridge:

```java
protected final <T> T adapt(Class<T> type);
protected final <T> T adapt(Class<T> type, Class<?> container);
```

So inside your plugin:

```java
@CloudPlugin(name = "welcome", version = "1.0.0")
public final class WelcomePlugin extends CloudPluginBase {

    private WelcomeHandler handler;

    @Override
    public void onEnable(CloudPluginContext ctx) {
        this.handler = adapt(WelcomeHandler.class);
        // handler is the right adapter for the running MC version
    }
}
```

## End-to-end example

```java
@CloudPlugin(
    name = "welcome",
    version = "1.0.0",
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
            ctx.players().getPlayer(e.uniqueId()).ifPresent(greeter::greet));
    }
}
```

## Next up

- [CloudPluginContext](/reference/plugin-sdk/plugin-context/) — `adapt()`
  comes from `CloudPluginBase`.
- [Plugin SDK index](/reference/plugin-sdk/) — quick-start.
- [Concepts → Plugin packaging](/concepts/plugins/)
