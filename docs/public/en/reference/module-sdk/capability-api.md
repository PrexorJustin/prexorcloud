---
title: Capability API
description: CapabilityHandle, provides/requires, dynamic handle proxies, capability events, and the built-in capabilities (InstanceFileAccess) — the typed dependency graph between platform modules.
---

The capability API is the dependency graph between platform modules. A
provider declares `provides:` in its `module.yaml` and exports
`CapabilityHandle` instances from `capabilityHandles()`; a consumer declares
`requires:` and resolves the handle through `ModuleContext`. The controller
refuses to transition a module to `ACTIVE` until every required capability is
bound by a provider whose version satisfies the consumer's range.

Handles are not captured directly. The registry hands consumers a dynamic
proxy that re-points to the live provider, so a provider upgrade or reload
swaps the backing implementation without the consumer re-resolving.

This page documents every public type, method, field, and the controller's
built-in capabilities, with the exact signatures from `java/cloud-api` and the
controller's `registerBuiltinCapabilities`.

## Package layout

| Type | Package |
|---|---|
| `CapabilityHandle<T>` | `me.prexorjustin.prexorcloud.api.module.platform` |
| `CapabilityDeclaration` | `me.prexorjustin.prexorcloud.api.module.platform` |
| `CapabilityHandleResolver` | `me.prexorjustin.prexorcloud.api.module.platform` |
| `DaemonCapabilityRegistry` | `me.prexorjustin.prexorcloud.api.module.platform` |
| `CapabilityRegisteredEvent` / `CapabilityUnregisteredEvent` / `CapabilityProviderChangedEvent` | `me.prexorjustin.prexorcloud.api.event.events` |
| `InstanceFileAccess` | `me.prexorjustin.prexorcloud.api.module.capability` |
| `PlayerJourneyTracker` | `me.prexorjustin.prexorcloud.api.module.capability` |

## `CapabilityHandle<T>`

```java
package me.prexorjustin.prexorcloud.api.module.platform;

public final class CapabilityHandle<T> {
    public static <T> CapabilityHandle<T> of(String id, Class<T> type, T value);

    public String   id();
    public Class<T> type();
    public T        value();
}
```

A typed binding from a capability id to its handle value. `type()` is the
public interface (or class) consumers resolve against; `value()` is the
instance behind it.

| Member | Returns | Notes |
|---|---|---|
| `of(String id, Class<T> type, T value)` | `CapabilityHandle<T>` | Factory. The only public constructor path. |
| `id()` | `String` | Capability id. Must match a `provides[].id` in the manifest. |
| `type()` | `Class<T>` | The public type consumers cast to. |
| `value()` | `T` | The backing implementation. |

`of` validates eagerly and throws before the handle exists:

- `NullPointerException` if `id`, `type`, or `value` is `null` (each guarded by
  `Objects.requireNonNull` with the field name).
- `IllegalArgumentException("id must not be blank")` if `id.isBlank()`.
- `IllegalArgumentException("handle for '<id>' is not an instance of <type>")`
  if `!type.isInstance(value)`. A provider cannot expose a handle no consumer
  can legally cast.

`equals`/`hashCode` cover all three fields; `toString()` returns
`CapabilityHandle[<id> : <type-fqn>]`.

```java
LeaderboardService leaderboard = new LeaderboardServiceImpl(repository);

CapabilityHandle<LeaderboardService> handle = CapabilityHandle.of(
        "stats-aggregator-leaderboard",
        LeaderboardService.class,
        leaderboard);
```

The `type` must be a public interface or class the consumer can resolve
against — usually an interface in the provider's own API jar, which consumers
pick up as a `compileOnly` dependency.

## Providing a capability

Override `capabilityHandles()` on your `PlatformModule` (or `DaemonModule`).
The controller calls it after activation and binds each returned handle.

```java
// PlatformModule.java (cloud-api)
default List<CapabilityHandle<?>> capabilityHandles() {
    return List.of();
}
```

```java
// DaemonModule.java — same contract, node-local binding
default List<CapabilityHandle<?>> capabilityHandles() {
    return List.of();
}
```

Each returned handle's `id()` must match a `provides[].id` entry in the
manifest. A duplicate id across two handles from the same provider throws
`IllegalArgumentException("duplicate capability handle id in provider: <id>")`
during activation.

Worked example, from `stats-aggregator` — note the generic erasure cast some
real providers use when the concrete service type differs from the public
interface:

```java
public static final String LEADERBOARD_CAPABILITY_ID = "stats-aggregator-leaderboard";

@Override
@SuppressWarnings({"rawtypes", "unchecked"})
public List<CapabilityHandle<?>> capabilityHandles() {
    if (leaderboard == null) {
        return List.of();
    }
    return List.of(CapabilityHandle.of(
            LEADERBOARD_CAPABILITY_ID, (Class) LeaderboardService.class, leaderboard));
}
```

Returning an empty list before the service is initialized (here, `leaderboard
== null`) is the correct way to withhold a capability the module can't yet
serve.

## Requiring a capability

Declare the requirement in the manifest, then resolve it through
`ModuleContext` in a lifecycle hook.

```java
// ModuleContext.java (cloud-api)
<T> Optional<T> findCapability(String capabilityId, Class<T> type);
<T> T           requireCapability(String capabilityId, Class<T> type);
```

| Method | On absent capability | Use for |
|---|---|---|
| `findCapability(id, type)` | returns `Optional.empty()` | Soft dependencies the module can degrade without. |
| `requireCapability(id, type)` | throws `IllegalStateException("required capability is not available: <id>")` | Hard dependencies the module can't run without. |

Both throw `NullPointerException` if `capabilityId` or `type` is `null`.
`requireCapability` is implemented as `findCapability(...).orElseThrow(...)`.

When the handle is present, `findCapability` returns the dynamic proxy (see
below), not the raw provider value. If a non-proxy raw handle is bound and the
requested `type` doesn't match, the context throws
`IllegalStateException("capability '<id>' is not assignable to <type>")`.

Soft dependency (`stats-aggregator` resolving the journey log):

```java
@Override
public void onLoad(ModuleContext context) {
    PlayerJourneyTracker tracker = context.findCapability(
                    PlayerJourneyTracker.CAPABILITY_ID, PlayerJourneyTracker.class)
            .orElse(null);
    this.journey = new JourneyEnricher(tracker); // tolerates null
}
```

Hard dependency (`backup-orchestrator` requiring instance file access):

```java
@Override
public void onLoad(ModuleContext context) {
    InstanceFileAccess files = context.requireCapability(
            InstanceFileAccess.CAPABILITY_ID, InstanceFileAccess.class);
    this.snapshots = new SnapshotService(files);
}
```

Resolve in `onLoad` for `requires`, since the controller guarantees every
`requires` is bound before `onLoad` runs.

## Manifest declaration

The `module.yaml` `capabilities:` section is the source of truth. The parser
(`PlatformModuleManifestParser`) rejects unknown fields and validates versions.

```yaml
capabilities:
  provides:
    - id: stats-aggregator-leaderboard
      version: 1.0.0
  requires:
    - id: prexor.player.journey
      versionRange: ">=1.0.0 <2.0.0"
```

### `provides[]`

| Field | Required | Type | Rules |
|---|---|---|---|
| `id` | yes | string | Capability identifier; validated as an identifier. Duplicate ids in one `provides` list are rejected. |
| `version` | yes | string | Must be strict semver `x.y.z`. Rejected otherwise: `<where>.version must be semver (x.y.z): <value>`. |
| `deprecatedSince` | no (`manifestVersion: 2`+) | string | Semver-shaped. Provider version where the capability entered its deprecation window. Setting it makes the controller warn every consumer that resolves against this capability. |
| `removedIn` | no (`manifestVersion: 2`+) | string | Semver-shaped. Provider version where the capability will be removed. Setting `removedIn` without `deprecatedSince` is rejected: `<where>.removedIn requires .deprecatedSince to also be set`. |

`deprecatedSince` and `removedIn` are only accepted under
`manifestVersion: 2`; declaring them under `manifestVersion: 1` is an unknown
field and fails parsing. `CURRENT_MANIFEST_VERSION` is `2`,
`MIN_MANIFEST_VERSION` is `1`.

```yaml
# manifestVersion: 2
capabilities:
  provides:
    - id: stats-aggregator-leaderboard
      version: 2.0.0
      deprecatedSince: 1.9.0
      removedIn: 3.0.0
```

### `requires[]`

| Field | Required | Type | Rules |
|---|---|---|---|
| `id` | yes | string | Capability identifier. |
| `versionRange` | yes | string | A `SemverRange`. Validated at parse time; invalid ranges fail with `<where>.versionRange is invalid: <value>`. |

`versionRange` follows semver-range syntax, e.g. `">=1.0.0 <2.0.0"`. The
controller binds a `requires` only when the active provider's `version` falls
inside the range.

### Java mirror — `CapabilityDeclaration`

```java
public record CapabilityDeclaration(List<Provides> provides, List<Requires> requires) {

    public static final CapabilityDeclaration EMPTY = new CapabilityDeclaration(List.of(), List.of());

    public boolean isEmpty();

    public record Provides(String id, String version, String deprecatedSince, String removedIn) {
        public Provides(String id, String version);   // deprecatedSince/removedIn null
        public boolean isDeprecated();                 // deprecatedSince != null
    }

    public record Requires(String id, String versionRange) {}
}
```

The canonical constructor copies and null-coerces both lists to immutable
empties. `EMPTY` is the value a manifest carries when the `capabilities:`
section is absent.

## Dynamic handles

Consumers never capture the raw provider value. The registry wraps every
binding in a `DynamicCapabilityHandle` that implements `CapabilityHandleResolver`:

```java
package me.prexorjustin.prexorcloud.api.module.platform;

public interface CapabilityHandleResolver {
    <T> T resolve(Class<T> type);
}
```

`ModuleContext.findCapability` / `requireCapability` call `resolve(type)` and
hand back the result. Behavior depends on whether `type` is an interface:

- **Interface `type`:** `resolve` returns a cached `java.lang.reflect.Proxy`.
  Every method call dispatches to the current delegate at call time. Cache key
  is the requested `Class`, so two consumers requesting different interfaces of
  the same capability get distinct proxies. `Object` methods are handled
  locally: `toString()` returns `CapabilityProxy[<id>]`, `hashCode()` is the
  proxy identity hash, `equals` is reference identity.
- **Non-interface `type`:** `resolve` casts the live delegate directly (no
  proxy). The returned reference is the raw provider; it will not track a later
  delegate swap. Prefer interface-typed capabilities.

The proxy re-reads a `volatile` delegate on each invocation, so:

- A provider upgrade or in-place reload (`replaceModuleBindings`) swaps the
  delegate; in-flight consumers see the new implementation on their next call
  without re-resolving.
- When the provider deactivates, the delegate is set to `null` and the proxy
  cache is cleared (so the provider's classloader isn't pinned). A subsequent
  call through the stale proxy throws
  `IllegalStateException("required capability is not available: <id>")`.
- If the live delegate isn't assignable to the requested type, calls throw
  `IllegalStateException("capability '<id>' is not assignable to <type>")`.
- Checked exceptions thrown by the delegate are unwrapped from
  `InvocationTargetException` and re-thrown as-is.

Implication: hold the resolved reference for the life of the module; do not
re-resolve per call, and do not assume the same object identity survives a
provider swap.

## Resolution and lifecycle

```
INSTALLED → WAITING (requires unmet) → ACTIVE (all requires resolved)
```

The registry (`CapabilityRegistry`, in `cloud-modules:runtime`) tracks active
bindings and resolves requirements:

- `unresolvedRequirements(manifest)` returns one `UnresolvedRequirement(moduleId,
  capabilityId, versionRange, reason)` per unmet `requires`. `reason` is
  `"missing provider"` when nothing provides the id, or
  `"version mismatch: active provider <module>@<version>"` when a provider is
  present but its version is outside the range.
- `requirementsSatisfied(manifest)` is true when `unresolvedRequirements` is
  empty. The controller will not call `onStart` while a requirement is unmet;
  the module stays `WAITING` and the operator sees an "unmet capability"
  diagnostic in the dashboard.
- A capability provided by two different modules is rejected at activation:
  `IllegalStateException("capability '<id>' is already provided by module '<other>'")`.
  `validateNoCycles` additionally rejects duplicate providers and capability
  dependency cycles across the loaded set:
  `capability dependency cycle detected: a -> b -> a`.

### Deprecation warnings

When a consumer resolves a `requires` against a provider whose `provides` entry
sets `deprecatedSince`, the registry logs a `WARN` per resolution and increments
a metric:

```
Module 'stats-aggregator' resolved capability 'prexor.player.journey' (range >=1.0.0 <2.0.0)
against deprecated provider 'player-journey@1.9.0' (deprecatedSince=1.9.0, removedIn=3.0.0).
Migrate before the capability is removed.
```

The binding still resolves — deprecation warns, it does not block. The
`removedIn=...` clause is omitted from the message when `removedIn` is unset.

## Capability events

Binding changes are published on the controller `EventBus`. The bootstrap
wires the registry's listener to re-emit these so SSE/REST consumers (the
dashboard's `useCapability`) track the graph in real time. All three are
`CloudEvent` records in `me.prexorjustin.prexorcloud.api.event.events`.

| Event | Fields | `type()` | Fired when |
|---|---|---|---|
| `CapabilityRegisteredEvent` | `capabilityId`, `version`, `moduleId` | `CAPABILITY_REGISTERED` | A module activates with a `provides` entry, or the controller registers a built-in handle. |
| `CapabilityUnregisteredEvent` | `capabilityId`, `moduleId` | `CAPABILITY_UNREGISTERED` | A provider deactivates, or its `provides` list shrinks on upgrade. |
| `CapabilityProviderChangedEvent` | `capabilityId`, `moduleId`, `fromVersion`, `toVersion` | `CAPABILITY_PROVIDER_CHANGED` | An existing binding's version changes in place (same module, `replaceModuleBindings`). |

A provider switch across two different modules surfaces as
`CapabilityUnregisteredEvent` followed by `CapabilityRegisteredEvent` for the
same `capabilityId` — not a `CapabilityProviderChangedEvent`, which is reserved
for in-place same-module rebindings.

Subscribe through `ModuleContext.events()`:

```java
context.events().subscribe(CapabilityRegisteredEvent.class, e -> {
    if (e.capabilityId().equals(PlayerJourneyTracker.CAPABILITY_ID)) {
        context.logger().info("journey provider available: {}@{}", e.moduleId(), e.version());
    }
});
```

## Daemon-side registry

Daemon modules observe a node-local view through `DaemonCapabilityRegistry`.
Cross-node capability sharing is out of scope for v1 — only daemon modules on
the same node see each other's bindings.

```java
package me.prexorjustin.prexorcloud.api.module.platform;

public interface DaemonCapabilityRegistry {

    record CapabilityBinding(String capabilityId, String version, String moduleId) {}

    List<CapabilityBinding> activeBindings();

    Subscription addListener(Listener listener);

    interface Listener {
        void onCapabilityRegistered(String capabilityId, String version, String moduleId);
        void onCapabilityUnregistered(String capabilityId, String moduleId);
        void onCapabilityProviderChanged(String capabilityId, String moduleId, String fromVersion, String toVersion);
    }

    interface Subscription {
        void unsubscribe();
    }
}
```

| Member | Returns | Notes |
|---|---|---|
| `activeBindings()` | `List<CapabilityBinding>` | Snapshot of every active binding on this node. |
| `addListener(Listener)` | `Subscription` | Subscribe to bind/unbind/replace. |
| `Subscription.unsubscribe()` | `void` | Detach the listener. |

Daemon modules resolve consumed capabilities the same way as controller
modules — through `ModuleContext.findCapability` / `requireCapability`. Daemon
modules have no Mongo storage; `findMongoStorage()` returns empty.

## Built-in capabilities

The controller registers built-in handles in `registerBuiltinCapabilities`
(`PrexorCloudBootstrap`) before `loadStoredModules`, so any module requiring
them resolves on first load. Built-ins are registered under the reserved
provider id `@controller` (`CapabilityRegistry.BUILTIN_PROVIDER_ID`); a normal
module activation can never clobber a built-in binding. Each registration is
guarded — if the backing service wasn't wired in the current bootstrap profile
(for example an embedded test that skips the daemon gateway), the capability is
silently skipped and consumers requiring it stay parked.

Registering a built-in whose id is already bound throws
`IllegalStateException("capability '<id>' is already provided by '<module>'")`.

| Capability id | Java type | Version | Provider |
|---|---|---|---|
| `prexor.instance.files` | `InstanceFileAccess` | `1.0.0` | `@controller` built-in |

`prexor.player.journey` (`PlayerJourneyTracker`) is **not** a controller
built-in — it is provided by the `player-journey` module. Treat it as a normal
module dependency: install `player-journey`, and your `requires` resolves when
that module reaches `ACTIVE`.

### `InstanceFileAccess`

Read-only view over instance working-directory files on remote daemons.
Modules that inspect files inside running instances (config snapshotting,
diagnostics scrapers, audit collectors) consume this instead of opening their
own daemon gRPC channels.

```java
package me.prexorjustin.prexorcloud.api.module.capability;

public interface InstanceFileAccess {

    String CAPABILITY_ID = "prexor.instance.files";

    InstanceFileTree  walk(String nodeId, String group, String instanceId);
    InstanceFileBytes read(String nodeId, String group, String instanceId, String relPath, int maxBytes);

    record InstanceFileEntry(String path, long sizeBytes, boolean isDir, long modifiedAtMs) {}

    record InstanceFileTree(List<InstanceFileEntry> entries, boolean truncated, String error) {
        public boolean ok();   // error == null || error.isBlank()
    }

    record InstanceFileBytes(String content, long totalSizeBytes, boolean truncated, String error) {
        public boolean ok();   // error == null || error.isBlank()
    }
}
```

#### `walk(String nodeId, String group, String instanceId)`

Walks an instance working directory and returns its file tree.

| Parameter | Type | Notes |
|---|---|---|
| `nodeId` | `String` | Id of the daemon hosting the instance. Non-blank. |
| `group` | `String` | Instance's group name. Empty string is acceptable when the caller has none to hand. |
| `instanceId` | `String` | Instance id under the daemon. Non-blank. |

Returns `InstanceFileTree`:

| Field | Type | Meaning |
|---|---|---|
| `entries` | `List<InstanceFileEntry>` | One entry per file/dir found. |
| `truncated` | `boolean` | True when the walk hit the daemon cap (5 000 entries / 24 directory levels). |
| `error` | `String` | `""` on success; otherwise a tag such as `INSTANCE_NOT_FOUND`, `DAEMON_UNREACHABLE`, or `TIMEOUT`. |

Each `InstanceFileEntry` carries `path` (relative, forward-slashed),
`sizeBytes`, `isDir`, and `modifiedAtMs` (epoch millis).

#### `read(String nodeId, String group, String instanceId, String relPath, int maxBytes)`

Reads up to `maxBytes` from a single file under the instance directory.

| Parameter | Type | Notes |
|---|---|---|
| `nodeId` | `String` | Daemon id. Non-blank. |
| `group` | `String` | Group name; empty string acceptable. |
| `instanceId` | `String` | Instance id. Non-blank. |
| `relPath` | `String` | Relative path under the instance dir, forward slashes, e.g. `"config/server.properties"`. |
| `maxBytes` | `int` | Cap on bytes returned. Pass `<= 0` for the daemon default (64 KiB). |

Returns `InstanceFileBytes`:

| Field | Type | Meaning |
|---|---|---|
| `content` | `String` | The daemon's UTF-8 encoding of the bytes read. Treat as text. |
| `totalSizeBytes` | `long` | Real on-disk size, even when truncated. |
| `truncated` | `boolean` | True when the file exceeded `maxBytes`; `content` holds the first `maxBytes`. |
| `error` | `String` | `""` on success; otherwise a tag. |

#### Bounds and behavior

- Walks cap at 5 000 entries / 24 directory levels (daemon-side enforced).
- Reads are bounded by the daemon's max-bytes setting (default 64 KiB).
  There is no offset/seek — only the first `N` bytes. At the daemon protocol
  level a read can request the last `N` bytes (`tail=true`); the cloud-api
  surface documented here exposes only the leading read.
- Both calls block up to 20 s per request and **never throw**. Unreachable
  daemons, timeouts, and daemon-reported errors all surface as a populated
  `error` tag on the return value. Always check `ok()` before reading `entries`
  or `content`.
- Content is UTF-8 text. Binary files (region files, NBT, world chunks)
  round-trip lossily; filter walk results by extension before reading. A future
  `prexor.instance.snapshot` capability backed by a daemon-side tar handler is
  the intended path for binary data.

#### Worked example

Manifest (`backup-orchestrator`):

```yaml
capabilities:
  requires:
    - id: prexor.instance.files
      versionRange: ">=1.0.0 <2.0.0"
```

Resolve and use:

```java
@Override
public void onLoad(ModuleContext context) {
    InstanceFileAccess files = context.requireCapability(
            InstanceFileAccess.CAPABILITY_ID, InstanceFileAccess.class);

    InstanceFileAccess.InstanceFileTree tree = files.walk("node-a", "lobby", "lobby-1");
    if (!tree.ok()) {
        context.logger().warn("walk failed: {}", tree.error());
        return;
    }

    for (var entry : tree.entries()) {
        if (entry.isDir() || !entry.path().endsWith(".properties")) {
            continue;
        }
        InstanceFileAccess.InstanceFileBytes bytes =
                files.read("node-a", "lobby", "lobby-1", entry.path(), 0); // 0 → 64 KiB default
        if (bytes.ok()) {
            context.logger().info("{} ({} bytes{}):\n{}",
                    entry.path(),
                    bytes.totalSizeBytes(),
                    bytes.truncated() ? ", truncated" : "",
                    bytes.content());
        }
    }
}
```

## Versioning

A provider shipping a breaking change bumps its capability `version` across the
major boundary and consumers tighten their `versionRange`. The controller
refuses to bind a `requires` against a `provides` whose version falls outside
the range — the consumer stays `WAITING` with a clear "version mismatch"
diagnostic. Use `deprecatedSince`/`removedIn` (manifestVersion 2) to give
consumers a warning window before removal.

## Next up

- [ModuleContext](/reference/module-sdk/module-context/) — `findCapability`,
  `requireCapability`, `events()`.
- [PlatformModule](/reference/module-sdk/platform-module/) —
  `capabilityHandles()`, lifecycle hooks.
- [module.yaml](/reference/module-sdk/module-yaml/) — full manifest schema.
- [EventBus](/reference/module-sdk/event-bus/) — subscribing to capability events.
