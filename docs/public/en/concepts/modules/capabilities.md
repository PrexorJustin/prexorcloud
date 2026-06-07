---
title: Capabilities
description: Named, typed contracts that link Modules to each other — providers, consumers, dynamic handles, classloader rules, and the built-in prexor.instance.files capability.
---

A capability is the only supported way one Module links to another. It is a named, typed contract: a string id (for example `prexor.player.journey`) bound to a Java interface that both sides compile against. A Module that owns the contract **provides** it; a Module that needs it **requires** it. The Controller resolves the wiring when Modules activate and keeps each binding live as providers come and go.

This page is the developer reference for the capability system. It covers the manifest declarations, the runtime API in `cloud-api`, how the registry resolves and rebinds, the classloader rules that keep Modules isolated, and the Controller's built-in `prexor.instance.files` capability.

## What a capability is

A capability has three parts:

- **An id** — a string, declared in the manifest and used at runtime. First-party capabilities use dotted names: `prexor.player.journey`, `prexor.instance.files`. Module-defined ones in the shipped Modules use hyphen names: `stats-aggregator-leaderboard`.
- **A type** — a public interface defined in `cloud-api` (or another type-only jar both sides compile against). Consumers resolve against this type; the provider's value must be an instance of it.
- **A version** — semver, carried by the provider. Consumers require a version range.

The interface must live where both the provider Module and the consumer Module can see it through the shared parent classloader — that is `cloud-api`. It must not live in the provider's or consumer's own jar. Putting it there re-creates the cross-classloader leakage the capability system exists to prevent.

At most one provider is bound to a given capability id at any moment. A second Module that declares `provides` for an id already held by another Module fails activation with `capability '<id>' is already provided by module '<other>'`.

## The two sides

Two artifacts wire a capability together: the manifest (`META-INF/prexor/module.yaml`) and the `PlatformModule` implementation.

| Concern | Provider | Consumer |
|---|---|---|
| Manifest | `capabilities.provides[]` | `capabilities.requires[]` |
| Code | `capabilityHandles()` returns a `CapabilityHandle` | `ctx.findCapability(...)` / `ctx.requireCapability(...)` |
| Lifecycle effect | binding becomes resolvable for consumers | required requirements gate the move to `ACTIVE` |

## Declaring capabilities in the manifest

Capabilities are declared under the `capabilities` block of `META-INF/prexor/module.yaml`. The block has two optional arrays: `provides` and `requires`.

```yaml
manifestVersion: 1
id: stats-aggregator
version: 1.0.0-SNAPSHOT
hosts: [controller]
backend:
  controller:
    entrypoint: me.prexorjustin.prexorcloud.modules.stats.platform.StatsAggregatorModule
capabilities:
  requires:
    - id: prexor.player.journey
      versionRange: ">=1.0.0 <2.0.0"
  provides:
    - id: stats-aggregator-leaderboard
      version: 1.0.0
```

### `provides` entry fields

| Field | Required | Notes |
|---|---|---|
| `id` | yes | Capability id. |
| `version` | yes | Semver (`x.y.z`, optional pre-release/build suffix). The version consumers match against. |
| `deprecatedSince` | no | `manifestVersion: 2` only. Semver naming the provider version where this capability entered deprecation. Setting it makes the Controller warn any consumer that resolves against the capability. |
| `removedIn` | no | `manifestVersion: 2` only. Semver naming the version where the capability will be removed. Requires `deprecatedSince` to also be set, or the manifest is rejected. |

Declaring the same `provides.id` twice in one manifest is rejected (`capabilities.provides declares '<id>' more than once`).

### `requires` entry fields

| Field | Required | Notes |
|---|---|---|
| `id` | yes | Capability id this Module consumes. |
| `versionRange` | yes | A semver range. Accepts comparator form (`">=1.0.0 <2.0.0"`) and interval form (`"[1.0,2.0)"`). Parsed by `SemverRange`; an invalid range fails the manifest. |

The manifest parser is strict. Unknown fields anywhere in the `capabilities` block are rejected with `capabilities... contains unknown field '<x>'`.

## Providing a capability in code

A `PlatformModule` exports its providers by overriding `capabilityHandles()`. Each handle binds an id to a typed value with `CapabilityHandle.of(id, type, value)`.

```java
public final class PlayerJourneyModule implements PlatformModule {

    private MongoPlayerJourneyTracker tracker;

    @Override
    public void onLoad(ModuleContext context) {
        var repository = new JourneyRepository(context.requireMongoStorage());
        tracker = new MongoPlayerJourneyTracker(repository);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<CapabilityHandle<?>> capabilityHandles() {
        if (tracker == null) return List.of();
        return List.of(
            CapabilityHandle.of(
                PlayerJourneyTracker.CAPABILITY_ID,
                (Class) PlayerJourneyTracker.class,
                tracker));
    }
}
```

Rules the `CapabilityHandle` constructor enforces:

- `id` must be non-blank.
- `type` and `value` are non-null.
- `value` must be an instance of `type`. A handle whose value cannot be cast to its declared type is rejected at construction with `handle for '<id>' is not an instance of <type>`.

Every id returned by `capabilityHandles()` must match a `provides` entry in the manifest. The registry indexes handles by id; returning two handles with the same id throws `duplicate capability handle id in provider`.

Return an empty list (as the example does before `onLoad` completes) when the implementation is not built yet. The registry binds whatever the list contains at activation time.

## Consuming a capability in code

Consumers resolve through `ModuleContext`. Two methods:

```java
// Optional<T> — empty when the provider is absent or not yet active.
<T> Optional<T> findCapability(String capabilityId, Class<T> type);

// T — throws when the capability is unbound. Use only when the Module
// cannot meaningfully run without it.
<T> T requireCapability(String capabilityId, Class<T> type);
```

Resolve a capability the Module declared under `requires`:

```java
@Override
public void onLoad(ModuleContext context) {
    PlayerJourneyTracker tracker =
        context.findCapability(
                PlayerJourneyTracker.CAPABILITY_ID, PlayerJourneyTracker.class)
            .orElse(null);
    this.journey = new JourneyEnricher(tracker);   // copes with null
}
```

What `findCapability` returns is not the provider's object directly — it is a **dynamic handle** (see below). Capture it once and keep calling it; do not re-resolve on every use.

## Dynamic handles

The value a consumer resolves is backed by a dynamic handle that can swap its delegate without the consumer replacing its reference. This is what lets you upgrade a provider Module without restarting its consumers.

How it behaves:

- **Interface types** resolve to a `java.lang.reflect.Proxy` that forwards each call to the current delegate. When a new provider version binds, the same proxy now forwards to the new instance — the consumer's reference is unchanged.
- **When the provider deactivates**, the delegate is cleared. A call through the proxy then throws `IllegalStateException: required capability is not available: <id>`. The handle does not silently return `null` mid-call; guard by re-checking presence through `findCapability` if a provider can disappear under you, or hold a required dependency so the Module parks instead.
- **Non-interface types** resolve to the delegate object directly (cast to the requested type), with no proxy.

The proxy implements `toString`, `hashCode`, and `equals` locally (`CapabilityProxy[<id>]`, identity hash, identity equals) so those calls do not fan out to a possibly-absent delegate.

## How resolution gates activation

`requires` entries are resolved when the Controller activates a Module:

- An entry resolves when a provider is bound **and** the provider's version satisfies the entry's `versionRange`.
- An entry is **unresolved** when there is no provider (`missing provider`) or the active provider's version is out of range (`version mismatch: active provider <module>@<version>`).

A Module with unresolved required capabilities does not move to `ACTIVE`; it parks until a satisfying provider binds. Unresolved requirements are surfaced over REST (see below) so you can see exactly what a parked Module is waiting on. See [Lifecycle](/concepts/modules/lifecycle/) for the full state machine.

The Controller also validates the capability graph across all installed Modules before activation. Two failures are fatal:

- **Duplicate provider** — two Modules declare `provides` for the same id (`capability '<id>' is provided by multiple modules`).
- **Dependency cycle** — provider/consumer edges form a loop (`capability dependency cycle detected: a -> b -> a`).

## Versioning and deprecation

The provider's `version` and the consumer's `versionRange` are matched with `SemverRange.contains`. A consumer that needs a method introduced in provider 1.2 demands `">=1.2.0"`; if only 1.1 is installed, the requirement stays unresolved and the consumer parks rather than failing at install time.

When a provider's `provides` entry sets `deprecatedSince`, every consumer that resolves against it triggers a Controller warning:

```
Module 'stats-aggregator' resolved capability 'prexor.player.journey' (range >=1.0.0 <2.0.0)
against deprecated provider 'player-journey@1.4.0' (deprecatedSince=1.3.0, removedIn=2.0.0).
Migrate before the capability is removed.
```

The count of such resolutions is exposed as `deprecatedProviderResolutionCount` in the capability metrics.

## Built-in capability: `prexor.instance.files`

The Controller registers some capabilities itself rather than shipping them as Modules. The first is `prexor.instance.files`, type `InstanceFileAccess` (in `cloud-api`). It gives Modules a read-only view over files in a running Instance's working directory without opening their own Daemon gRPC channels.

It is registered under the reserved built-in provider id `@controller` at version `1.0.0`, after the Controller's file-tree and file-content services come up and **before** stored Modules load — so a Module that requires it resolves on first load. If those services are not wired in the current boot profile (for example an embedded test without the Daemon gateway), registration is skipped silently and consumers stay parked.

### Consuming it

Declare the requirement:

```yaml
capabilities:
  requires:
    - id: prexor.instance.files
      versionRange: ">=1.0.0 <2.0.0"
```

Resolve and call it:

```java
InstanceFileAccess files =
    context.requireCapability(
        InstanceFileAccess.CAPABILITY_ID, InstanceFileAccess.class);

// Walk an instance working directory.
InstanceFileAccess.InstanceFileTree tree =
    files.walk(nodeId, group, instanceId);
if (!tree.ok()) {
    log.warn("walk failed: {}", tree.error());   // e.g. DAEMON_UNREACHABLE
    return;
}

// Read first 4 KiB of a config file.
InstanceFileAccess.InstanceFileBytes bytes =
    files.read(nodeId, group, instanceId, "config/server.properties", 4096);
if (bytes.ok()) {
    process(bytes.content());                    // UTF-8 text
}
```

### Methods

```java
InstanceFileTree  walk(String nodeId, String group, String instanceId);
InstanceFileBytes read(String nodeId, String group, String instanceId,
                       String relPath, int maxBytes);
```

| Parameter | Meaning |
|---|---|
| `nodeId` | Id of the Daemon hosting the Instance. Non-blank. |
| `group` | The Instance's Group name. Empty string is accepted when the caller does not have one to hand. |
| `instanceId` | Instance id under the Daemon. Non-blank. |
| `relPath` | Forward-slash relative path under the Instance directory, e.g. `config/server.properties`. |
| `maxBytes` | Read cap. Pass `<= 0` for the Daemon default of 64 KiB. |

### Return shapes

```java
record InstanceFileEntry(String path, long sizeBytes, boolean isDir, long modifiedAtMs);

record InstanceFileTree(List<InstanceFileEntry> entries, boolean truncated, String error);

record InstanceFileBytes(String content, long totalSizeBytes, boolean truncated, String error);
```

Both `InstanceFileTree` and `InstanceFileBytes` expose `ok()`, which is true when `error` is empty.

### Bounds and behavior

- **Walk** is capped Daemon-side at 5 000 entries and 24 directory levels. Built-in directory-summary markers the Daemon emits when it trims a large directory are filtered out — `walk` returns only concrete paths you can read.
- **Read** returns the first `maxBytes` bytes. There is no offset or seek. The current Daemon RPC encodes content as UTF-8; treat `content` as text. A file larger than the cap comes back with `truncated() == true` and `totalSizeBytes()` set to the real on-disk size.
- **Binary files** (region files, NBT, world chunks) round-trip lossily through the UTF-8 encoding. Filter `walk` results by extension and do not `read` binaries through this capability. World-data snapshots are out of scope for `prexor.instance.files`.
- **Errors never throw.** Unreachable Daemons, timeouts, and Daemon-reported errors surface as a populated `error` tag — for example `INSTANCE_NOT_FOUND`, `DAEMON_UNREACHABLE`, or `TIMEOUT`. Both calls block up to 20 s per request.

## Classloader isolation

Each platform Module loads in its own classloader whose parent is the Controller's. A Module sees `cloud-api` types through the parent and its own classes through its own loader. It never sees another Module's classes — only the shared interface in the parent.

The dynamic-handle layer is built to avoid pinning an unloaded Module's classloader:

- The registry caches one `Class<?> → Proxy` mapping per resolving type, so repeated resolutions reuse the same proxy.
- When a provider deactivates (or rebinds with a manifest that drops the capability), the handle's delegate is set to `null` and the **proxy cache is cleared**. Dropping the cached `Proxy` instances also drops their `Class<?>` keys, so neither the keys nor the generated proxy classes pin the deactivated provider's classloader (or a consumer's, if the consumer resolved against a type it owned).

This is why the contract type must live in `cloud-api`: a type owned by the parent classloader is never the thing that leaks.

### Leak observability over REST

The Controller can wrap each loaded classloader in a `PhantomReference` and track collection. Two endpoints expose the tracker, both gated on the `MODULES_MANAGE` permission:

- `GET /api/v1/modules/platform/leaked-classloaders` — returns `tracking` (false when no tracker is configured), a `pending` list (per entry: `moduleId`, `moduleVersion`, `classLoaderClassName`, `trackedAt`, `ageMs`, `repeatCount`), and `totals` (`tracked`, `collected`, `leaks`, `forcedCleanupHints`).
- `POST /api/v1/modules/platform/leaked-classloaders/force-cleanup` — runs forced cleanup and returns `pendingBefore`, `pendingAfter`, `collected`, and `totalForcedCleanupHints`. Returns `409 CLASSLOADER_TRACKER_DISABLED` when no tracker is configured.

## Lifecycle events

Three events fire on binding changes, published on the Controller event bus:

- `CapabilityRegisteredEvent(capabilityId, version, moduleId)` — a capability binds (including built-in `@controller` handles).
- `CapabilityProviderChangedEvent(capabilityId, moduleId, fromVersion, toVersion)` — the same provider rebinds at a different version.
- `CapabilityUnregisteredEvent(capabilityId, moduleId)` — a binding is released.

These flow through the global SSE firehose at `GET /api/v1/events/stream`. A dedicated, lower-noise stream is also available:

- `GET /api/v1/modules/platform/capabilities/stream` — SSE filtered to the three capability events. Use this instead of subscribing to the firehose when you only track capability changes. Seed initial state from `GET /api/v1/modules/platform/capabilities`.

## Inspecting capabilities over REST

`GET /api/v1/modules/platform/capabilities` returns the full graph and metrics. It requires the `MODULES_VIEW` permission. The body has three keys:

- `modules` — per Module: `moduleId`, `state`, `provides[]` (each with `id`, `version`, `active`), `requires[]` (each with `id`, `versionRange`, and a `binding` of `{moduleId, version}` or `null`), and `unresolvedRequirements[]` (each `capabilityId`, `versionRange`, `reason`).
- `bindings` — every active binding as `{capabilityId, version, moduleId}`, including built-in `@controller` bindings that the per-Module view does not show.
- `metrics` — `resolutionCount`, `unresolvedRequirementCount`, `rebindingEventCount`, `deprecatedProviderResolutionCount`, `lastResolutionLatencyMillis`.

## Daemon-side capabilities

Daemon Modules have their own node-local registry, `DaemonCapabilityRegistry`. It mirrors the Controller contract: `activeBindings()` returns `{capabilityId, version, moduleId}` tuples, and `addListener(...)` subscribes to register/unregister/provider-changed events, returning a `Subscription` you `unsubscribe()`.

Scope is node-local in v1: only Daemon Modules on the same node see each other's bindings. There is no cross-node capability sharing. See [Daemon Modules](/concepts/modules/daemon/).

## Why this design

The alternative — Modules linking through each other's internal classes — is the classic Minecraft-plugin failure mode. One plugin upgrades a dependency, an internal signature changes, every dependent plugin breaks at runtime with a `NoSuchMethodError`, and the only recovery is a full restart.

Capabilities replace that with:

- **One contract per capability.** The interface lives in `cloud-api`; it does not change without a Controller release.
- **No classloader exposure.** Module B sees Module A only through the shared interface in the parent classloader.
- **Dynamic rebinding.** Module A is replaced without restarting Module B.

This is also why `cloud-api` is kept small and stable. Every type a Module compiles against lives there, and adding to it is a Controller release decision, not a Module decision.

## Next

- [Platform Modules](/concepts/modules/platform/) — the full Controller-side Module contract.
- [Lifecycle](/concepts/modules/lifecycle/) — how `requires` drives the `WAITING ↔ ACTIVE` transitions.
- [Daemon Modules](/concepts/modules/daemon/) — the node-local capability registry.
- [Events](/concepts/events/) — capability events on the SSE bus.
