---
title: Capability API
description: Provides/requires graph and CapabilityHandle — how modules expose typed services to other modules without compile-time coupling.
---

The **capability API** is the dependency graph between modules. A
provider declares `provides:` in its manifest and exports
`CapabilityHandle` instances; a consumer declares `requires:` in its
manifest and resolves the handle through `ModuleContext`. The
controller will not transition a module to `ACTIVE` until every
required capability is bound.

## What you'll learn

- The shape of a `provides` / `requires` entry in `module.yaml`.
- The `CapabilityHandle<T>` value class.
- How to resolve a capability and what happens when one is missing.

## API surface

### `CapabilityHandle<T>`

```java
package me.prexorjustin.prexorcloud.api.module.platform;

public final class CapabilityHandle<T> {
    public static <T> CapabilityHandle<T> of(String id, Class<T> type, T value);

    public String   id();
    public Class<T> type();
    public T        value();
}
```

The constructor enforces `value instanceof type`, so providers cannot
expose a handle that no consumer can legally cast. The `type` must be a
public interface or class consumers can resolve against — typically an
interface declared in the provider's API jar.

### `CapabilityDeclaration`

The Java mirror of the manifest section.

```java
public record CapabilityDeclaration(
        List<Provides> provides,
        List<Requires> requires) {

    public record Provides(String id, String version);
    public record Requires(String id, String versionRange);
}
```

`versionRange` follows semver-range syntax (`>=1.0.0 <2.0.0`).

### Resolution

Resolution happens through `ModuleContext`:

```java
<T> Optional<T> findCapability(String capabilityId, Class<T> type);
<T> T           requireCapability(String capabilityId, Class<T> type);
```

`findCapability` returns empty when the capability is unbound;
`requireCapability` throws. Use `find` for soft dependencies the module
can fall back from, and `require` for hard ones.

## Lifecycle interaction

```
INSTALLED → WAITING (deps unmet) → ACTIVE (all `requires` resolved)
```

The controller refuses to call `onStart` while the module is in
`WAITING`. Manifest declarations are the source of truth: a module
that resolves a capability not listed in `requires` gets a noisy
`WARN` log but the call still works (graceful degradation).

## Example: provide

```yaml
# module.yaml of stats-aggregator
capabilities:
  provides:
    - id: stats-aggregator-leaderboard
      version: 1.0.0
```

```java
@Override
public List<CapabilityHandle<?>> capabilityHandles() {
    return List.of(CapabilityHandle.of(
            "stats-aggregator-leaderboard",
            LeaderboardService.class,
            leaderboard));
}
```

`LeaderboardService` is a public interface in the stats-aggregator's
own API jar; consumer modules pick that jar up as a `compileOnly`
dependency.

## Example: require

```yaml
capabilities:
  requires:
    - id: prexor.player.journey
      versionRange: ">=1.0.0 <2.0.0"
```

```java
@Override
public void onLoad(ModuleContext context) {
    PlayerJourneyTracker tracker = context.requireCapability(
            PlayerJourneyTracker.CAPABILITY_ID,
            PlayerJourneyTracker.class);
    this.journey = new JourneyEnricher(tracker);
}
```

Built-in capabilities (e.g. `prexor.player.journey`) are registered by
the controller itself, so they're always present in production. In a
test harness without that controller bring-up, switch to
`findCapability` and pass `null` through if absent.

## Versioning

A provider that ships a breaking change bumps its capability `version`
across the major boundary, and consumers tighten their `versionRange`.
The controller refuses to bind a `requires` against a `provides` whose
version falls outside the range — the module stays `WAITING` and the
operator sees a clear "unmet capability" diagnostic in the dashboard.

## Next up

- [ModuleContext](/reference/module-sdk/module-context/) — `findCapability`
  and `requireCapability`.
- [PlatformModule](/reference/module-sdk/platform-module/) —
  `capabilityHandles()`.
- [module.yaml](/reference/module-sdk/module-yaml/) — full manifest
  schema for the capability section.
