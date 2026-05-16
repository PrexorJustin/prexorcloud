# Module system

A platform module is a JVM jar that the controller loads at runtime to add features. Modules can register REST routes, subscribe to events, store per-module state, expose typed capabilities, contribute frontend pages, and ship workload extensions for MC servers.

The reference module is `stats-aggregator` under `java/cloud-module/cloud-module-stats-aggregator/`. Anything described here is exercised by it end-to-end.

> Not sure whether you want a module or a standalone `@CloudPlugin` jar? See [`plugin-vs-module.md`](plugin-vs-module.md) for the decision flowchart.

## What a module is

A module is:

- A jar built against `cloud-api` only.
- Containing a `META-INF/prexor-module.json` manifest with id, version, dependencies, capabilities, extensions, and frontend manifest references.
- Optionally accompanied by a `<jar>.cosign.bundle` (or legacy `<jar>.sig`) signature.
- Installed via `prexorctl module install <bundle>` against the controller.

## Lifecycle FSM

Every module moves through a deterministic state machine:

```
                ┌──────────┐
                │UNINSTALLED│
                └─────┬────┘
                      │ install (signed jar accepted, manifest parsed)
                      ▼
                ┌──────────┐
                │ INSTALLED│
                └─────┬────┘
                      │ activate (capability deps resolved, extensions registered)
                      ▼
                ┌──────────┐
                │  ACTIVE  │
                └─────┬────┘
                      │ deactivate (capability handles cleared, extensions unregistered)
                      ▼
                ┌──────────┐
                │ INSTALLED│
                └──────────┘
                  uninstall → UNINSTALLED → classloader closed
```

Transitions are persisted to MongoDB (`module_packages` collection) and propagated as SSE events. The dashboard module page reflects the state in real time.

A module can be paused mid-lifecycle (e.g. when a capability dependency cannot be resolved). The reason is stored alongside the state so operators can see *why* a module is in `INSTALLED` rather than `ACTIVE`.

## Capability handles

The single mechanism by which modules link to each other.

A capability is a named, typed contract: `CapabilityHandle<T>` where `T` is an interface defined in `cloud-api`. At any moment there is at most one provider for a given capability name.

### Defining a capability

```java
// cloud-api
public interface PlayerJourneyTracker {
    List<PlayerJourneyEvent> getJourney(UUID playerUuid, int limit);
    PlayerJourneyEvent getLatest(UUID playerUuid);
}
```

The capability name is a string (by convention, dotted: `prexor.player.journey`).

### Providing a capability

```java
public final class StatsModule implements PlatformModule {
    @Override
    public void onActivate(CapabilityRegistry registry, ...) {
        registry.register("stats.aggregator.leaderboard", LeaderboardProvider.class, this::resolveLeaderboard);
    }
}
```

### Consuming a capability

```java
@Override
public void onActivate(CapabilityRegistry registry, ...) {
    CapabilityHandle<PlayerJourneyTracker> handle =
        registry.resolve("prexor.player.journey", PlayerJourneyTracker.class);
    // handle.get() is null until the provider is active
    var tracker = handle.get();
    if (tracker != null) tracker.getJourney(uuid, 50);
}
```

The handle is dynamic. When a provider deactivates, `handle.get()` returns null. When a different provider rebinds, the same handle now points at the new instance. Consumers do not need to refetch.

### Built-in capabilities

The controller registers some capabilities itself:

- `prexor.player.journey` → `PlayerJourneyTracker` — the Player Journey Bus, exposed by `PlayerJourneyService`.

Other built-ins live alongside their owning subsystem.

### Why this rule exists

See [`decisions.md`](decisions.md) §"Modules link via capability handles, never via classpath." Cross-module classloader exposure is the bug we never want.

### Deprecating a capability (`manifestVersion: 2`)

Capabilities are versioned but the version alone doesn't tell consumers when a provider is on its way out. `manifestVersion: 2` adds two optional fields on each `capabilities.provides[]` entry:

- `deprecatedSince` — the provider version where this capability was first marked deprecated.
- `removedIn` — the provider version where it will be removed (optional, but only meaningful with `deprecatedSince`).

```yaml
manifestVersion: 2
id: profiles
version: 2.5.0
capabilities:
  provides:
    - id: player-profile
      version: 1.4.0
      deprecatedSince: 1.3.0
      removedIn: 2.0.0
```

Effect:

- `CapabilityRegistry` warns (`logger.warn`) every time a consumer resolves a requirement against a deprecated provider, naming the consumer module and the capability. The counter is also exposed as the Prometheus `prexorcloud.capabilities.deprecated_resolutions` and on the JSON metrics snapshot as `deprecatedProviderResolutionCount`.
- `prexorctl module doctor` warns when the inspected jar advertises any deprecated `provides[]`, so authors get a CI-time signal before consumers do.
- `manifestVersion: 1` manifests cannot use these fields — the strict parser rejects them as unknown.

Bump `manifestVersion` to `2` whenever you add a `deprecatedSince`/`removedIn` line; everything else stays exactly as it was on v1.

## REST routes

Modules can register routes mounted at `/api/v1/modules/<moduleId>/<sub>`.

```java
@Override
public void onRegisterRoutes(RouteRegistrar reg) {
    reg.get("/players/top", this::handleTopPlayers);
    reg.post("/sessions/join", this::handleSessionJoin);
}
```

Routes are dispatched by the controller-side `ModuleRouteRegistry`. The actual Javalin handler is one wildcard per HTTP method — `RestServer` mounts `GET /api/v1/modules/{moduleId}/<sub>`, `POST` likewise, etc., and the registry resolves `(moduleId, method, sub)` to the registered handler.

When a module unloads, its routes drop atomically. There is no "old route still served by ghost handler" possibility.

Permission gating: routes registered by modules are subject to the same RBAC as core routes. The controller injects a `RequestContext` with the caller's permissions and the module checks them via `ctx.requirePermission(...)`. Built-in module-permission constants (`MODULES_VIEW`, `MODULES_MANAGE`) cover most cases; modules can also reuse domain permissions like `GROUPS_VIEW`.

## Event subscriptions

Modules subscribe to the SSE bus through the in-process `EventBus`:

```java
@Override
public void onActivate(EventBus bus, ...) {
    bus.subscribe(InstanceCrashedEvent.class, this::onCrash);
}
```

Subscriptions are scoped to the module's lifecycle — they are removed automatically on deactivate. Modules do not need to implement their own teardown.

## Per-module storage

Modules get two storage primitives, both isolated by module id.

### Mongo-backed document storage

```java
ModuleDataStore data = ctx.dataStore();
data.insertOne("sessions", new Document("playerUuid", uuid).append("joinedAt", Instant.now()));
data.find("sessions", new Query().eq("playerUuid", uuid)).forEach(...);
```

Collections live under `mod_<moduleId>_<name>` so module ids never collide. Soft size limits (per-module, configurable) prevent a runaway module from consuming the whole Mongo. The controller tracks usage in `module_storage_metrics`.

### Valkey-backed key/value storage

```java
ModuleRedisStorage redis = ctx.redisStorage();
redis.set("leaderboard:top", json, Duration.ofMinutes(5));
String top = redis.get("leaderboard:top");
```

Keys live under `prexor:v1:platform:<moduleId>:`. The contract is "scope your keys under your module id"; the controller does not enforce it (no per-key validation), but the prefix is a convention every reference module follows.

In `development` profile (no coordination store), modules that *request* Redis storage get a no-op handle. Modules that declare it as required in their manifest fail to activate in development; build them against the Mongo-backed store or run in production profile.

## Workload extensions

Modules can ship MC-server-side code via workload extensions. The cloud-api defines the manifest shape; the controller's `ExtensionRegistry` resolves which extension applies to which group based on platform / version / variant matchers.

The decision is hashed into the composition plan, so the daemon installs exactly the right jar — and a hash mismatch (e.g. operator forgot to upgrade the module on one host) is detected in the test harness.

This is how the `stats-aggregator` reference module ships its server-side counterpart. It is also how third-party plugins extend the cloud system without forking.

Supported `target:` strings today:

| target                  | host                                | activation                |
|-------------------------|-------------------------------------|---------------------------|
| `server/paper`          | Paper / Spigot 1.20+                | explicit-group-attach     |
| `server/folia`          | Folia 1.21+                         | explicit-group-attach     |
| `proxy/velocity`        | Velocity 3.x proxy                  | explicit-group-attach     |
| `proxy/bungeecord`      | BungeeCord / Waterfall              | explicit-group-attach     |
| `server/bedrock-geyser` | Geyser running on a Java server     | explicit-group-attach     |

### Bedrock support via Geyser

`server/bedrock-geyser` targets a [Geyser](https://geysermc.org) extension — Geyser is the cross-protocol bridge that lets Bedrock Edition clients connect to Java servers, and it has its own extension system (separate from Paper's plugin loader). PrexorCloud's `@CloudPlugin` annotation processor generates a `*GeyserBridge implements org.geysermc.geyser.api.extension.Extension` + an `extension.yml` descriptor; the daemon drops the jar into the host server's `extensions/` directory (alongside other Geyser extensions) rather than `plugins/`.

A Bedrock-targeted module variant ships alongside the Java-side variants — it doesn't replace them. Bedrock events surface through Geyser's `org.geysermc.geyser.api.event.bedrock.*` (`SessionLoginEvent`, `SessionJoinEvent`, `SessionDisconnectEvent`, `ClientEmoteEvent`). The reference module's `example-playtime-bedrock-geyser` variant demonstrates the pattern: emit `PLAYTIME:BEDROCK_*` events on session join/disconnect so dashboards can render cross-platform totals without splitting by client kind.

Three Geyser-specific things to remember when authoring a Bedrock variant:

1. **No `@ForVersion` dispatch.** Geyser abstracts over the host MC version, so the generated bridge doesn't wire `VersionDispatcher`. If you need per-host-version branching, ship a sibling Paper/Folia/Velocity variant instead.
2. **`installPath: extensions/`** (not `plugins/`) in the module manifest — Geyser doesn't scan the server's plugin directory.
3. **Bedrock-only XUID identity.** `SessionLoginEvent.connection().xuid()` is the stable identity for Bedrock players. Java UUIDs only become available after Floodgate hand-off; design event payloads to handle both keys.

## Frontend manifest

Modules can ship dashboard pages. The frontend manifest (`META-INF/frontend/manifest.json` inside the jar) declares:

- The route path under the dashboard (e.g. `/modules/stats-aggregator`).
- The entry-point asset (a single ESM bundle).
- The capabilities the page consumes (so the dashboard can hide pages whose capabilities are unavailable).
- The required permissions (so RBAC hides pages from viewers without access).

`ModuleFrontendManager` extracts the manifest + asset to the controller's frontend cache, the dashboard renders the page through `@prexorcloud/module-sdk`. SDK versioning matrix lives at `dashboard/packages/module-sdk/COMPAT.md`.

When the module unloads, the frontend cache directory is deleted and the dashboard drops the page.

## Signing and verification

Module bundles are signed via Cosign. Production controllers verify fail-closed.

Two formats are accepted:

- `<jar>.cosign.bundle` — Cosign sign-blob format, new style. Supports raw-keyed signatures, embedded-cert signatures with PKIX validation, and embedded-cert pinning against raw pubkey trust roots.
- `<jar>.sig` — legacy keyed PEM sidecar. Deprecated; supported for backwards compatibility.

Configure under `modules.signing`:

```yaml
modules:
  signing:
    required: true                     # production default
    mode: COSIGN_BUNDLE                # COSIGN_BUNDLE | KEYED (default KEYED for back-compat)
    trustRoot: "config/cosign-roots.pem"
    rekor:
      policy: REQUIRE_SET              # DISABLED | REQUIRE_SET
      publicKey: "config/rekor.pub"    # required when policy != DISABLED
```

`REQUIRE_SET` enforces offline Rekor SET verification: the controller loads Rekor's public key locally, parses the bundle's `SignedEntryTimestamp`, reconstructs the canonical JSON of the Rekor payload, and rejects bundles whose SET does not verify. No network access is required.

Inclusion-proof Merkle-path verification is *not* implemented. SET is enough — see [`decisions.md`](decisions.md) §"Cosign + offline Rekor."

## Authoring and shipping a module

The CLI ships everything an author needs:

```bash
# Scaffold a new module from the template
prexorctl module new my-module

# Watch + reload during development
prexorctl module dev my-module

# Run gradle tests
prexorctl module test my-module

# Build for release
cd java && ./gradlew :cloud-module:cloud-module-my-module:shadowJar

# Sign with cosign (your key + Sigstore identity flow)
cosign sign-blob --bundle my-module.cosign.bundle path/to/my-module.jar

# Upload to a controller
prexorctl module install my-module.jar  # auto-detects sibling .cosign.bundle
```

`module new` rewrites tokens from the template, patches `settings.gradle.kts`, and supports `--strip-comments`, `--force`, `--dry`, `--package`, `--repo-root`. `module dev` resolves `archiveName` from the module's `build.gradle.kts`, polls `build/libs/<archiveName>.jar` for changes, and reuploads via `POST /api/v1/modules/platform/upload` on first install or `.../upgrade` thereafter; it also spawns `./gradlew assemble -t` so one terminal is enough.

If the module has a `frontend/` subtree, `frontend/dist/` is polled as a separate track. A change there triggers a frontend-only `POST /api/v1/modules/platform/{moduleId}/frontend/reload` that re-stages the dashboard bundle without touching the platform module's classloader — capabilities, REST routes, and Mongo handles stay live across the reload. The controller publishes `MODULE_FRONTEND_RELOADED`, the dashboard invalidates its bundle cache and re-imports. Jar changes already include the latest frontend, so a same-tick jar+frontend rebuild short-circuits to a single jar upload.

`module test` is a thin wrapper around `./gradlew :cloud-module:cloud-module-<name>:test`. Mock-based unit tests stay there. For integration tests against real persistence and a real REST surface, use the harness described in [Testing](#testing).

## Testing

Two layers, picked per test:

- **Unit tests with mocks.** Fast, in-process, no containers. The reference module's `PlaytimeRepositoryTest`, `PlaytimeQueryServiceImplTest`, and `PlaytimeRoutesTest` follow this style — Mockito for `ModuleDataStore`, fake `RouteRegistrar` for handlers, no controller at all. Use these for branching logic, validation, and capability handle shape.

- **Integration tests via `ModuleTestHarness`.** Lives at `java/cloud-test-harness/src/main/java/.../harness/module/ModuleTestHarness.java`. Boots ephemeral MongoDB + Redis via Testcontainers and an in-process controller pointed at them — no shared local services, no daemon. Use this when the contract you want to verify involves Jackson serialization through a real driver, Mongo index behavior, route mounting under `/api/v1/modules/<id>/`, or capability registration.

`ModuleTestHarness` is `AutoCloseable`. Skip the test gracefully when Docker isn't reachable so dev machines without a daemon don't see red:

```java
@Test
void topEndpointRanksByTotalMs() throws Exception {
    assumeTrue(ModuleTestHarness.isDockerAvailable(), "Docker required");

    try (var harness = ModuleTestHarness.start()) {
        harness.installFromJar(Path.of(System.getProperty("example.shadowjar.path")));

        var repo = new PlaytimeRepository(harness.dataStoreFor("example-playtime"));
        // ... seed data, rebuild totals ...

        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(harness.restBaseUrl() + "/api/v1/modules/example-playtime/top"))
                        .header("Authorization", "Bearer " + harness.adminJwt())
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        // ... assert on response.body() ...
    }
}
```

Wire your module's test classpath in the obvious way:

```kotlin
dependencies {
    testImplementation(project(":cloud-test-harness"))
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.shadowJar)
    doFirst {
        systemProperty("module.shadowjar.path", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    }
}
```

The harness installs modules via the same `PlatformModuleManager.install(Path)` path the production controller runs — same jar prep, same classloader isolation, same capability activation. Module signing is in `allowUnsignedDevelopment` mode under the hood, so unsigned shadowJars work.

For lifecycle bugs that need failover, multi-daemon coordination, or HA — the kind of thing the controller does, not a module — use `TestCluster` from `cloud-test-harness/src/test/java/.../harness/`. `ModuleTestHarness` is intentionally narrower.

## Where modules cannot reach

The controller does not expose:

- The internal `ClusterState` model (modules see read-only `ClusterView`).
- The internal `EventBus` write side (modules can publish their own events through the SDK, not arbitrary controller-internal events).
- Other modules' classloaders, fields, or Mongo collections.
- The mTLS material, the JWT signing key, or any plugin token.

If you find yourself wanting one of these, the answer is a new capability + a new audit on its design. Not a hack.

## Where to look in the code

| What | Where |
|---|---|
| Public API every module compiles against | `java/cloud-api/` |
| Module registry, lifecycle FSM, classloader tracker | `java/cloud-controller/.../module/` |
| Reference module | `java/cloud-module/cloud-module-stats-aggregator/` |
| Module REST dispatcher | `controller/module/ModuleRouteRegistry`, `controller/rest/RestServer` |
| Capability registry + dynamic handles | `controller/capability/CapabilityRegistry` |
| Platform module signing + Rekor | `controller/module/platform/PlatformModuleSignatureVerifier` |
| Frontend SDK (npm) | `dashboard/packages/module-sdk/` |
