# Architecture

PrexorCloud is three processes plus two backing stores:

```
                  ┌────────────────┐
                  │   Dashboard    │  Nuxt 4 + Vue 3 (operator UI)
                  └────────┬───────┘
                           │ REST + SSE
            ┌──────────────▼──────────────┐
            │         Controller          │  Java 21+, single JVM per instance
            │  REST · gRPC · scheduler    │  active-active HA via Redis leases
            │  module manager · SSE bus   │
            └────┬───────────────┬────────┘
                 │ gRPC          │
                 │               │
        ┌────────▼─────┐   ┌─────▼──────┐
        │   Daemon     │   │  Daemon    │  one per host
        │ (host node)  │   │ (host node)│  spawns MC processes
        └──────────────┘   └────────────┘
                 ▲               ▲
                 │ stdio + RCON  │
        ┌────────┴─────────┐    │
        │ Minecraft server │ …  │  Paper / Spigot / Velocity / Bungee
        │     processes    │    │  with prexor-plugin embedded
        └──────────────────┘    │

                  ┌──────────────┐    ┌──────────────┐
                  │   MongoDB    │    │    Valkey    │
                  │ durable state│    │ coordination │
                  └──────────────┘    └──────────────┘
```

The whole thing fits on a laptop in development mode. In production it scales to thousands of MC instances across dozens of hosts.

## 1. Module layout

The Java codebase is a multi-project Gradle build:

| Module | Process | Role |
|---|---|---|
| `cloud-api` | — | Public types every module compiles against. `PlatformModule`, `CapabilityHandle<T>`, frontend manifest, MC-domain records. |
| `cloud-protocol` | — | Generated gRPC + protobuf types shared between controller and daemon. |
| `cloud-security` | — | JWT, certificate authority, mTLS context, password hashing. No process state. |
| `cloud-common` | — | Shared infrastructure: YAML config loader, logging setup, version detection, file permissions. |
| `cloud-controller` | controller JVM | REST, gRPC server, scheduler, module lifecycle, SSE bus, all persistence. |
| `cloud-daemon` | daemon JVM | Process supervision, template materialisation, plan application. |
| `cloud-module/cloud-module-stats-aggregator` | controller JVM (loaded) | Reference platform module. |
| `cloud-plugins/cloud-plugins-server-*` | MC server JVM | Paper / Spigot plugin code (in-game integration). |
| `cloud-plugins/cloud-plugins-proxy-*` | proxy JVM | Velocity / Bungee plugin code (network composition + routing). |
| `cloud-test-harness` | test JVM | Multi-controller integration tests (recovery, HA, perf, DR). |

Cross-module classpath exposure between platform modules is forbidden. Modules link through capabilities, never through shared internal types. See [`modules.md`](modules.md).

The CLI (`prexorctl`) is a separate Go project under `cli/`. It compiles to a single static binary and talks to the controller over the same REST API the dashboard uses.

The dashboard is a separate Node project under `dashboard/`. It is a Nuxt 4 SPA that consumes the OpenAPI-generated SDK and SSE event stream.

## 2. The controller

The controller is the brain. One JVM process owns:

- REST API (Javalin 7, port 8080 by default).
- gRPC server for daemon connections (port 9090 by default).
- Scheduler — decides where instances run and when they are reaped.
- Module lifecycle — install, activate, capability resolution, unload.
- SSE event bus — pushes state changes to dashboards and modules.
- Persistence to MongoDB (durable) and Valkey (coordination).
- mTLS material — issues + rotates certificates for daemons.

Construction lives in `PrexorCloudBootstrap`. There is no DI framework. Everything is wired by hand. This is intentional — see [`decisions.md`](decisions.md) §"Constructor injection only."

Multiple controller processes can run at once. They share MongoDB and Valkey, and coordinate through Valkey leases. Any healthy controller can serve REST and gRPC traffic. See §6 below for the HA model.

## 3. The daemon

One daemon process per host. Connects to the controller over gRPC.

The daemon never invents state. The controller produces a *composition plan* (templates + runtime jar + extensions + env/config patches, all hashed) and the daemon applies it. If the plan does not exist or is not addressed to this daemon, the daemon does nothing.

Per host the daemon owns:

- Process supervision — `ProcessBuilder` per MC instance, stdio capture, RCON when applicable, exit-code classification.
- Template materialisation — assembles the layered template chain (base → base-{platform} → group → user templates) into the instance directory.
- Plan application — applies controller-issued composition plans deterministically.
- Crash classification — captures console tail and exit code, reports to controller via gRPC `CrashReport`.
- Heartbeat — keeps the gRPC stream alive; the controller treats stream loss as node-offline.

The daemon does *not* run MC processes inside containers or cgroups. Process isolation is not in v1 scope — see `decisions.md`.

## 4. Plugins

Plugins are code that ships *inside* a Minecraft server or proxy JVM, alongside the cloud-installed jar. Two kinds:

- **Server plugins** (`cloud-plugins-server-paper`, `-spigot`, `-folia`) — emit player-join / -leave / -transfer events to the controller, expose RCON, render MOTDs, handle group-aware commands.
- **Proxy plugins** (`cloud-plugins-proxy-velocity`, `-bungee`) — implement Network Composition routing. On player connect, walk the lobbyGroup → fallbackGroups chain. On kick, walk the fallback chain again. Cache the relevant `NetworkComposition` from the controller.

Both kinds authenticate to the controller with a per-instance plugin token (`ptk_` prefix) issued at start time. Plugin tokens are scoped to a single instance and have a short TTL — see [`auth.md`](auth.md).

## 5. Runtime profiles

The controller boots in one of two profiles, selected by `controller.yaml: runtime.profile`:

- **`production`** (default). Requires a Redis-protocol coordination store (Valkey by default). The wiring graph is `RedisRuntimeServices`; every coordination accessor returns a non-null component. `ConfigValidator` rejects a `production` config without a configured coordination store.
- **`development`**. Single-controller, no shared coordination store. The wiring graph is `InMemoryRuntimeServices`. Several coordination-only features fall back or degrade — see below.

There is no silent fallback. The selection is made once, at `PrexorCloudBootstrap`, and the aggregate `RuntimeServices` hides the difference. The only branch consumer code makes is `RuntimeServices.coordinationEnabled()`. `RuntimeServicesWiringTest` enforces by reflection that the production graph contains zero `Optional<*Redis*>` and zero nullable Redis-typed fields.

### What production gives you that development does not

| Feature | Development | Production |
|---|---|---|
| Single-controller correctness | yes | yes |
| Multi-controller HA (lease-scoped work, fencing, standby promotion) | no | yes |
| SSE replay across controller restart | no — replay buffer in process memory | yes — buffered in Valkey |
| Persisted SSE / console session tickets | no | yes |
| Persisted REST + workload rate-limit windows | no | yes |
| Per-module Redis-protocol storage (when module *requests*) | no-op handle | real handle |
| Per-module Redis-protocol storage (when module *requires*) | activation fails | activation succeeds |
| Cluster event fanout (Redis pub/sub) | local EventBus only | pub/sub fanout |
| Workflow handoff across controllers (drain, deployment, healing, rolling-restart, recoverable start) | runs locally only; harness tests skipped | full handoff |

What is preserved in development (in-memory equivalents satisfying the same interface):

- JWT revocation — in-memory map. Revoked tokens are rejected for the rest of this controller's uptime.
- Login-attempt counter / account lockout — in-memory; counters reset on restart.
- Console flood-suppression window — in-memory.
- Per-node certificate revocation — in-memory.

Use `production` for anything beyond local iteration on a feature. The Compose stack ships Valkey out of the box; spinning it up locally is one container.

## 6. HA model: active-active, lease-scoped

PrexorCloud controller HA is **active-active with lease-scoped work**. Multiple controllers can run simultaneously against the same MongoDB + Valkey. Any healthy controller serves REST and gRPC. Mutation paths must hold the relevant lease and carry the current fencing token.

There is no single standby waiting for a leader to fail.

### What is leased

| Lease scope | Key shape | Purpose |
|---|---|---|
| Group | `prexor:v1:lease:group:<name>` | Group-scoped scheduling work (placement, scaling, drains for instances in the group) |
| Platform module mutation | `prexor:v1:lease:platform-module` | Install / upgrade / uninstall / storage deletion |
| Workflow resumption | `prexor:v1:lease:workflow:<scope>` | Persisted start-retry, node-drain, healing, recoverable-start workflows resume only when the controller owns the matching lease |
| Node ownership | tracked separately via `prexor:v1:node:` ownership records | Commands for a connected node go through the controller that owns its gRPC session |

### Fencing

Every lease acquisition returns a monotonic fencing token. Before a controller mutates state under a lease (reserves placement, dispatches a start, mutates module state, resumes a workflow), it checks that its token is still current. If a different controller has since taken the lease, the old controller stops mutating.

This is the write-safety mechanism. Clock skew can move lease *expiry* timing around, but it cannot cause two controllers to issue conflicting writes against the same scope, because only one controller holds a current fencing token at a time.

### Failover

When a controller stops or loses its lease, another controller can acquire the same scoped lease after expiry and resume from durable state in MongoDB + Valkey. The new owner reconciles live node/session state, persisted workflow state, and runtime records before issuing additional mutations.

Specifically: standby promotion is exercised at four points in `RecoveryTest` — drain, deployment, placement-time, and in-flight module mutation. The harness shows that a controller restart mid-failover resumes without duplicate restarts.

### Operator requirements for HA

- Run Valkey (or any Redis-protocol-compatible store) when active-active is enabled. Without a coordination store, the controller behaves as a single-writer deployment.
- Use shared MongoDB and shared Valkey across every controller in the HA set.
- Keep controller clocks reasonably synchronised. Fencing protects against split-brain writes; clock skew only affects lease *expiry*.
- Coordinate backup and restore with controller shutdown or a maintenance window — restore tooling does not currently acquire all mutation leases.

See [`runbooks/recover-controller.md`](runbooks/recover-controller.md), [`runbooks/recover-redis.md`](runbooks/recover-redis.md), and [`runbooks/recover-mongo.md`](runbooks/recover-mongo.md).

## 7. Data flow: launching an instance

A worked example, end-to-end:

1. Operator hits `POST /api/v1/groups/lobby/scale {targetInstances: 5}` (or scaling rules trigger automatically).
2. **Scheduler** (per controller, running on a per-group lease) decides the instance is missing. Picks a node via `WeightedNodeSelector` (affinity / anti-affinity / capacity / spread).
3. **InstancePlacementCoordinator** allocates `lobby-3`, picks a port from the daemon's port range, holds the placement under the group lease's fencing token.
4. **CompositionPlanner** generates a plan: ordered template chain hashes, runtime jar reference, workload extensions, env vars, plugin token. Plan is persisted to `instance_composition_plans` in MongoDB.
5. Controller sends a `Start` gRPC frame to the daemon owning the chosen node.
6. **Daemon** receives `Start`, reads the composition plan, materialises the template chain into `instances/lobby-3/`, layers the runtime jar, applies env patches, spawns the JVM via `ProcessBuilder`.
7. The MC process boots. The bundled prexor-plugin reads `CLOUD_*` env vars, exchanges its plugin token for an authenticated REST session, registers itself.
8. Server hits `RUNNING` → daemon emits `InstanceStarted` over gRPC → controller updates `ClusterState` and fans `INSTANCE_STARTED` out on the SSE bus.
9. Dashboards subscribed to SSE see the instance flip green.

Failure cases are symmetric. If the daemon never acks the start, the scheduler retries from the persisted plan (idempotent — plans are hash-keyed). If the controller dies between steps 4 and 5, another controller acquires the group lease, finds the persisted plan, and dispatches.

## 8. Module classloader lifecycle

Each platform module loads in a `URLClassLoader` whose parent is the controller's classloader. Modules see `cloud-api` types through the parent and their own classes through their own loader. Cross-module classloader exposure is forbidden — modules link through capability handles.

On unload, `PlatformModuleManager` closes the classloader through try-with-resources around `LoadedRuntime.closeable`.

`ModuleClassLoaderTracker` wraps each loaded classloader in a `PhantomReference` against a `ReferenceQueue` and emits four metrics:

- `prexorcloud.module.classloader.leaked` (counter, by module ID)
- `prexorcloud.module.classloader.collected.total`
- `prexorcloud.module.classloader.tracked.total`
- `prexorcloud.module.classloader.pending`

`GET /api/v1/modules/platform/leaked-classloaders` returns pending leak reports for the dashboard. `POST /api/v1/modules/platform/force-cleanup` runs the tracker's forced-cleanup escalation. Both are gated on `MODULES_MANAGE`.

Two registries that hold module-supplied references are explicitly cleaned on unload:

- `CapabilityRegistry` — the dynamic handle stored per capability caches `Class<?> → Proxy` mappings. When a provider deactivates (or rebinds with a new manifest dropping a capability), the dynamic handle's delegate is set to `null` and its proxy cache cleared, so neither cached `Class<?>` keys nor proxy classes pin the unloaded classloader.
- `ModuleFrontendManager` — on `removeFrontend`, the cached `LoadedFrontend` is removed and the on-disk asset directory is deleted. `LoadedFrontend` holds only paths and parsed manifest records, so it cannot pin a classloader.

`ExtensionRegistry` is constructed from manifests parsed into types from `cloud-api` (parent classloader). It holds no module-loader-bound references and needs no per-unload cleanup.

## 9. SSE event bus

The controller exposes a single SSE stream at `GET /api/v1/events/stream`. Twenty-two event types currently flow through it (group, instance, node, player, deployment, module, capability, network, choreography, journey, etc.).

Each event carries a monotonic sequence number. Clients reconnect with `Last-Event-ID` and the server replays missed events from the per-client replay buffer. In production the replay buffer lives in Valkey and survives controller restart; in development it lives in process memory and is cleared on restart.

Authentication uses short-lived tickets. The dashboard exchanges its JWT for a 30-second SSE ticket via `POST /api/v1/events/ticket`, then connects with the ticket as a query string. This avoids passing the long-lived JWT through `EventSource` (which cannot set headers).

The same pattern applies to console SSE (`/services/{id}/console`), controller log SSE (`/system/logs/stream`), and daemon log SSE (`/nodes/{id}/logs/stream`).

## 10. Where to look next

- **State ownership** — what lives in MongoDB vs. Valkey vs. memory: [`data-model.md`](data-model.md)
- **Why every architectural choice was made** — [`decisions.md`](decisions.md)
- **Module system in depth** — [`modules.md`](modules.md)
- **MC-domain primitives** (Network Composition, Event Choreography, Player Journey Bus) — [`mc-domain.md`](mc-domain.md)
- **Auth model** — [`auth.md`](auth.md)
- **Observability** — [`observability.md`](observability.md)
- **How it ships** — [`distribution.md`](distribution.md)
- **Configuration knobs** — [`configuration.md`](configuration.md)
- **Coding conventions** — [`conventions.md`](conventions.md)
- **Glossary** — [`glossary.md`](glossary.md)
