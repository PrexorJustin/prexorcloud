---
title: Module system
description: How PrexorCloud loads jars at runtime — controller-hosted and daemon-hosted modules, the manifest, the lifecycle state machine, and capabilities for cross-module wiring.
---

A Module is a JVM jar the cluster loads at runtime to add features without forking the codebase. A module can register REST routes, subscribe to cluster events, store per-module state, expose and consume typed capabilities, contribute dashboard pages, and ship workload extensions that fan out to Minecraft servers.

This page is the orientation across the module system. The pages that follow go deep on each axis.

## What you'll learn

- The two module hosts — Controller and Daemon — and what each can do.
- The shape of a module manifest (`META-INF/prexor/module.yaml`), field by field.
- The lifecycle state machine the controller drives every module through.
- How modules link to each other through capabilities, never through classloaders.
- The commands to scaffold, build, sign, and install a module.

The reference module is `stats-aggregator` under `java/cloud-modules/stats-aggregator/`. Everything described here is exercised by it and by the other first-party modules in `java/cloud-modules/`.

## What a module is

A module is:

- A jar built against `cloud-api` only.
- Carrying a `META-INF/prexor/module.yaml` manifest with id, version, hosts, backend entrypoints, capabilities, storage, frontend, and workload extensions.
- Optionally accompanied by a `<jar>.cosign.bundle` (or legacy `<jar>.sig`) signature sidecar, or shipped inside a `.tar`/`.tar.gz`/`.tgz` bundle.
- Installed against the controller with `prexorctl module install` or `prexorctl module upload`.

A module's backend code (`PlatformModule` / `DaemonModule` implementations) and its capability contracts (interfaces) live in different jars: contracts that one module exposes to another belong in `cloud-api`, never in the provider's own jar — see [Capabilities, not classpaths](#capabilities-not-classpaths).

## Two hosts

A module declares which process(es) it runs in with the `hosts` field. When the manifest omits `hosts`, the parser defaults to `[controller]`.

```yaml
manifestVersion: 1
id: my-module
hosts: [controller]              # or [daemon], or [controller, daemon]
backend:
  controller:
    entrypoint: com.example.MyControllerModule
  daemon:
    entrypoint: com.example.MyDaemonModule
```

| Host | Process | Entrypoint interface | Has access to |
|---|---|---|---|
| `controller` | controller JVM | `PlatformModule` | EventBus, Mongo storage, Redis storage, capability registry (cluster-wide), REST route registration, frontend manifest, workload extensions |
| `daemon` | each daemon JVM | `DaemonModule` | Instance lifecycle hooks, node-local capability registry, controller events forwarded over the daemon's gRPC bridge |

The host is reported back to a module at runtime through `ModuleContext.host()`, returning the `ModuleHost` enum (`CONTROLLER` or `DAEMON`), so a dual-host module can branch on which side it is running.

A module that lists both hosts ships **one jar with two entrypoints**. The controller installs it normally, then fans the same jar out to every connected daemon. Each side instantiates its own entrypoint. The two halves do not share heap state; they communicate through controller-bus events forwarded to the daemon.

What the daemon side deliberately lacks: no Mongo storage. `ModuleContext.findMongoStorage()` returns `Optional.empty()` in a daemon process. Daemon capability bindings are node-local — cross-node visibility is out of scope.

See:

- [Platform modules](/concepts/modules/platform/) — the controller-side contract: REST routes, events, storage, frontend.
- [Daemon modules](/concepts/modules/daemon/) — the host-side contract: instance lifecycle hooks, node-local state.

## The manifest

The manifest is YAML at `META-INF/prexor/module.yaml` inside the jar. The parser is strict: unknown fields are rejected, ids must match `[a-z][a-z0-9-]*`, and versions must be semver-shaped (`x.y.z` with an optional pre-release suffix).

### Top-level fields

| Field | Required | Type | Notes |
|---|---|---|---|
| `manifestVersion` | no | int | `1` or `2`. Defaults to the current version (`2`). Fields added in v2 are rejected when declared against `manifestVersion: 1`. |
| `id` | yes | string | Globally unique module id, `[a-z][a-z0-9-]*`. |
| `version` | yes | string | Semver, e.g. `1.0.0` or `1.0.0-SNAPSHOT`. |
| `hosts` | no | string array | Non-empty if present. `controller`, `daemon`, or both. Defaults to `[controller]`. |
| `backend` | yes | object | Entrypoints per host. |
| `frontend` | no | object | Dashboard bundle. `sdkVersion` (currently `1`) + `entry` (e.g. `index.js`). |
| `capabilities` | no | object | `provides` / `requires` declarations. |
| `storage` | no | object | `mongo` / `redis` flags + optional limits. |
| `extensions` | no | array | Workload extensions fanned out to instances. |

### `backend`

Declares the entrypoint class for each host. The shape must match the declared `hosts`:

```yaml
backend:
  controller:
    entrypoint: com.example.MyControllerModule
    reloadable: false            # manifestVersion 2+, default false
  daemon:
    entrypoint: com.example.MyDaemonModule
```

Rules the parser enforces:

- If `hosts` includes `controller`, `backend.controller.entrypoint` is required.
- If `hosts` includes `daemon`, `backend.daemon.entrypoint` is required.
- At least one of `controller` / `daemon` must be present.
- A legacy single-string form `backend.entrypoint: "..."` is still accepted and treated as the controller entrypoint; it cannot be mixed with the `controller`/`daemon` fields and requires `hosts` to include `controller`.
- `reloadable` (manifest version 2+) opts the controller entrypoint into the hot-reload fast path. Set it only if the module implements `PlatformModule.onReload` — see [The lifecycle](#the-lifecycle).

### `capabilities`

```yaml
capabilities:
  requires:
    - id: prexor.player.journey
      versionRange: ">=1.0.0 <2.0.0"
  provides:
    - id: stats-aggregator-leaderboard
      version: 1.0.0
```

- `requires[]` — `id` plus a `versionRange` (a semver range; parsed by `SemverRange`). Every requirement must resolve to an active provider before the module reaches `ACTIVE`.
- `provides[]` — `id` plus a semver `version`. In `manifestVersion: 2`, a `provides` entry may also carry `deprecatedSince` and `removedIn` (both semver, naming the *provider* version where the capability entered and will exit its deprecation window). `removedIn` requires `deprecatedSince` to be set. A non-null `deprecatedSince` is the signal the controller uses to warn consumers still resolving the capability.
- Duplicate `provides` ids in one manifest are rejected.

### `storage`

```yaml
storage:
  mongo: true
  redis: false
  limits:
    mongoDocuments: 500000
    redisKeys: 0
```

- `mongo` / `redis` — booleans, default `false`. When set, the controller allocates a scoped Mongo namespace and/or Redis key prefix for the module.
- `limits.mongoDocuments` requires `mongo: true`; `limits.redisKeys` requires `redis: true`. A limit of `0` means unbounded. Limits must be positive when present.

### `extensions`

A workload extension is module-owned code that the controller fans out to instances of a given runtime.

```yaml
extensions:
  - id: example-playtime-paper
    target: server/paper          # <workloadType>/<runtimeFamily>
    activation: explicit-group-attach
    conflicts: []
    variants:
      - id: example-playtime-paper
        mcVersionRange: "*"
        runtimeApiVersion: 1
        artifact: extensions/server/paper/example-playtime-paper.jar
        sha256: <64-char hex>
        installPath: plugins/
```

- `target` is a `<workloadType>/<runtimeFamily>` pair, for example `server/paper`, `server/folia`, `proxy/velocity`, `server/bedrock-geyser`.
- `activation` is one of `explicit-group-attach`, `default-enabled`, `always`.
- Each variant pins `mcVersionRange` (a semver range or `*`), `runtimeApiVersion` (≥ 1), a relative `artifact` path inside the jar, the artifact's `sha256` (64-char hex), and a relative `installPath` for where it lands on the instance. Relative paths may not start with `/` or contain `..`.

## The lifecycle

The controller drives every module through a deterministic state machine (`ModuleLifecycleManager.ModuleState`):

```
INSTALLED ──(requires satisfied)──▶ ACTIVE
    │                                 │
    │ (requires unsatisfied)          │ (a required capability goes away)
    ▼                                 ▼
 WAITING ◀──────────────────────── STOPPING ──▶ WAITING
    │                                 │
    └──(requires satisfied)──▶ ACTIVE │
                                      ▼
                              (uninstall) ──▶ UNLOADED

ACTIVE ──(reloadable jar)──▶ RELOADING ──▶ ACTIVE

any hook throws ──▶ FAILED
```

The states:

| State | Meaning |
|---|---|
| `INSTALLED` | Jar accepted, `onLoad` + `onRegisterRoutes` ran. Transient — the controller immediately reconciles. |
| `WAITING` | One or more `requires` capabilities are unbound. The module is parked here, not started. |
| `ACTIVE` | All requirements satisfied; `onStart` ran cleanly. The module's capability handles are published and its routes are live. |
| `RELOADING` | A reload-compatible jar is replacing a running module via the fast path. |
| `STOPPING` | A required capability disappeared, or an upgrade/uninstall is in progress; `onStop` is running. |
| `UNLOADED` | Uninstalled. `onUnload` ran and routes were dropped. A new install can reuse the id. |
| `FAILED` | A lifecycle hook threw. The exception message is recorded in `lastError`. |

Reconciliation is requirement-driven. On install and whenever a capability provider appears or disappears, the controller re-checks each module's `requires` set:

- `INSTALLED`/`WAITING` + requirements satisfied → `onStart` → `ACTIVE`.
- `INSTALLED`/`WAITING` + requirements unsatisfied → `WAITING`.
- `ACTIVE` + a requirement lost → `STOPPING` → `onStop` → `WAITING`.

### Hooks

`PlatformModule` lifecycle hooks, in the order the controller calls them:

- `onLoad(ctx)` — jar loaded, before routes and start.
- `onRegisterRoutes(registrar)` — register module-owned REST routes (called once, after `onLoad`, before `onStart`).
- `onStart(ctx)` — requirements satisfied; bring the module up.
- `onStop(ctx)` — a requirement was lost, or the module is being upgraded/uninstalled.
- `onUnload(ctx)` — final teardown on uninstall/upgrade.
- `onUpgrade(ctx)` — runs on a new jar that replaces an existing module.
- `onReload(ctx)` — the fast `ACTIVE → RELOADING → ACTIVE` path.
- `capabilityHandles()` — the handles this module exports once active.
- `healthCheck()` — optional liveness probe; polled on a cadence and surfaced at `GET /api/v1/modules/platform/{id}/health` and as the `prexorcloud.module.health` metric. Returns `HEALTHY`, `UNHEALTHY`, or `UNKNOWN` (the default when a module doesn't opt in).

`DaemonModule` shares `onLoad`/`onStart`/`onStop`/`onUnload`/`onUpgrade` and `capabilityHandles()`, and adds instance hooks: `onInstanceStarting(spec)` (may mutate `spec.jvmArgs()` / `spec.env()` before launch), `onInstanceStarted(handle)`, `onInstanceStopping(handle)`, `onInstanceStopped(handle, exit)`.

### Upgrade vs reload

Two paths replace a running module:

- **Upgrade** (default). The old module is stopped (`onStop`) and unloaded (`onUnload`), its routes are dropped, then the new jar runs `onLoad` → `onUpgrade` → `onRegisterRoutes` and reconciles. This re-evaluates requirements and re-binds consumers.
- **Reload** (fast path, opt-in). When the replacement jar's controller entrypoint declares `reloadable: true` **and** its capability declaration (`provides` + `requires`) is byte-for-byte identical to the running version, the controller calls only `onReload` on the new entrypoint. The outgoing module is never sent `onStop` or `onUnload`, so the new instance must hand off its own live state inside `onReload` — re-arm scheduler tasks, rebuild or re-point caches. Routes are still cleared and re-registered, because route handlers are classes in the outgoing classloader. Any change to the capability shape forces the full upgrade path. If `onReload` throws, the module is left `FAILED` with no rollback.

The `ModuleContext` carries `previousVersion()` (`""` on a fresh install; the prior version string on an upgrade/reload), and `isUpgrade()` is a convenience over it.

See [Lifecycle](/concepts/modules/lifecycle/) for the full state machine and classloader-cleanup rules.

## The module context

Every lifecycle hook receives a `ModuleContext`. It exposes the module's identity, the capabilities it declared as `requires`, its persistent storage, and the cross-cutting primitives shared with the plugin SDK.

| Group | Members |
|---|---|
| Identity | `manifest()`, `jarPath()`, `previousVersion()`, `isUpgrade()`, `host()` |
| Capabilities | `findCapability(id, type)` → `Optional<T>`, `requireCapability(id, type)` → `T` (throws if unbound) |
| Storage | `findMongoStorage()` / `requireMongoStorage()` → `ModuleDataStore`; `findRedisStorage()` / `requireRedisStorage()` → `PlatformRedisStorage` |
| Primitives | `events()` (cluster-wide `EventBus`), `logger()` (SLF4J, namespaced `module:<id>`), `scheduler()` (`TaskScheduler`, cancelled on stop), `httpClient()` (shared outbound pool), `json()` (standard Jackson `ObjectMapper`) |

The scheduler and HTTP client are host-owned: scheduler tasks are cancelled automatically on module stop, and the HTTP connection pool is shared across modules.

## Capabilities, not classpaths

The single mechanism by which modules link to each other is the **capability**: a named, versioned, typed contract. The contract type is a public interface defined in `cloud-api`.

A provider exports a handle from `capabilityHandles()`:

```java
// In cloud-api: the contract both sides agree on
public interface LeaderboardQuery {
    List<LeaderboardEntry> top(String metric, int limit);
}
```

```java
// In the provider module
@Override
public List<CapabilityHandle<?>> capabilityHandles() {
    return List.of(CapabilityHandle.of(
            "stats-aggregator-leaderboard",   // must match a manifest `provides` id
            LeaderboardQuery.class,           // the public contract type
            this.leaderboardService));        // an instance of that type
}
```

`CapabilityHandle.of(id, type, value)` enforces `value instanceof type`, so a provider cannot publish a handle no consumer can legally cast.

A consumer resolves through its context, not a classloader:

```java
// In the consumer module — declared `requires` in the manifest
Optional<LeaderboardQuery> q =
        context.findCapability("stats-aggregator-leaderboard", LeaderboardQuery.class);
q.ifPresent(query -> render(query.top("kills", 10)));

// Or, for a capability the module cannot run without:
LeaderboardQuery required =
        context.requireCapability("stats-aggregator-leaderboard", LeaderboardQuery.class);
```

Cross-module classloader exposure is forbidden. A module that imports another module's internal class fails to load — the parent (controller / daemon) classloader does not see other modules' jars. This is the rule that lets you upgrade, disable, or unload a module without breaking the rest of the system. The controller surfaces leaked classloaders at `GET /api/v1/modules/platform/leaked-classloaders`.

See [Capabilities](/concepts/modules/capabilities/) for the registry contract and dynamic-binding behaviour.

## Where modules cannot reach

The controller deliberately does not expose:

- The internal cluster-state write model (modules see read-only views).
- Other modules' classloaders, fields, or storage namespaces.
- The mTLS material, the JWT signing key, or any plugin token.

If you want one of these, the answer is a new capability and a review of its design, not a reach-around.

## Authoring and shipping

The CLI covers the full author loop. All commands live under `prexorctl module`.

```bash
# Scaffold a new module (full wizard or fast template copy)
prexorctl module new my-module

# Watch the jar and re-upload to the local controller on every rebuild
prexorctl module dev my-module

# Run the module's gradle test task
prexorctl module test my-module

# Validate a built jar against the platform-module contract
prexorctl module doctor build/libs/my-module.jar

# Build for release
cd java && ./gradlew :cloud-modules:my-module:shadowJar

# Sign the jar with cosign (produces my-module.jar.cosign.bundle)
cosign sign-blob --bundle build/libs/my-module.jar.cosign.bundle build/libs/my-module.jar
```

Install or update against a controller:

```bash
# Install a signed module — local .jar (sidecar auto-detected) or .tar bundle,
# or an id[@version] from a configured registry
prexorctl module install build/libs/my-module.jar

# Upload-and-install a jar directly
prexorctl module upload build/libs/my-module.jar

# Browse / install / upgrade from configured registries
prexorctl module search
prexorctl module upgrade my-module

# Inspect and remove
prexorctl module list
prexorctl module delete my-module
```

`module install` auto-detects a sidecar named `<jar>.cosign.bundle` or `<jar>.sig`, or takes one via `--signature`. Use `--check-requires` to fail fast when the target controller cannot satisfy the manifest's `requires`, and `--registry` to pin a source registry. Production controllers verify signatures fail-closed against a configured trust root.

## Module vs plugin

Modules are not the only way to extend a Minecraft network. A `@CloudPlugin` jar lives in a server's `cloud-plugins/` directory and runs inside the Minecraft JVM. A Module lives on the controller (and optionally each daemon) and may carry a workload extension that the controller fans out to instances.

Pick the in-process Plugin when the behaviour is local to one server type; pick the Module when you need cluster-wide state, REST, a dashboard page, or capabilities shared across modules. See [Plugins](/concepts/plugins/) for the side-by-side comparison.

## Next up

- [Platform modules](/concepts/modules/platform/) — REST routes, EventBus, Mongo/Redis storage, frontend manifests.
- [Daemon modules](/concepts/modules/daemon/) — instance lifecycle hooks, node-local state, forwarded events.
- [Capabilities](/concepts/modules/capabilities/) — registering and resolving capability handles.
- [Lifecycle](/concepts/modules/lifecycle/) — the state machine, classloader rules, cleanup on unload.
- [Plugins](/concepts/plugins/) — in-process plugin vs module, when to pick which.
