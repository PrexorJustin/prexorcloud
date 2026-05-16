# cloud-module-example (`example-playtime`)

Canonical ModuleSystem v2 reference module. It packages a platform-module
backend, a frontend bundle, and multi-version workload-extension artifacts. The
backend owns scoped Mongo storage and exposes a narrow
`example-playtime-query` capability handle for cross-module total-playtime
reads.

Copy this directory when starting a new module.

## What It Demonstrates

| STEP | File | Purpose |
|---|---|---|
| 0  | `build.gradle.kts` | Apply `prexorcloud.module`, declare universal + versioned plugin JARs |
| 1  | `src/main/module/module.yaml` | Platform manifest with backend, storage, capabilities, frontend, and extensions |
| 2  | `platform/ExamplePlatformModule.java` | `PlatformModule` lifecycle entrypoint |
| 3  | `data/Session.java`, `data/TopEntry.java`, `data/PlaytimeRepository.java` | Repository over `ModuleDataStore` with indexes |
| 4  | `config/Config.java` + `resources/example-playtime.yml` | Reusable config record and defaults |
| 5  | `events/PlaytimeEventNames.java`, `events/SessionStart.java`, `events/SessionEnd.java` | Typed wrappers around `CustomCloudEvent` |
| 6  | `service/PlaytimeQueryService.java` + `...Impl.java` | Internal read-only service adapted to the published capability handle |
| 7  | `rest/PlaytimeRoutes.java` | Reusable route surface for the pending platform-route API |
| 8  | `frontend/src/*.vue` | Dashboard page bundle packaged with the module |

## Config reference

File: `config/modules/example-playtime/example-playtime.yml` (auto-written on
first boot from `Config.defaults()`).

| Key (YAML) | Type | Default | Purpose |
|---|---|---|---|
| `flush-interval-seconds` | int | `30` | Suggested cadence to rebuild `totals` from `sessions` |
| `top-size` | int | `25` | Leaderboard size returned by `/top` and broadcast via `PLAYTIME:TOP_UPDATED` |
| `retain-sessions-days` | int | `30` | Suggested retention window for old `Session` docs |
| `report-via` | string | `"events"` | `"events"` → plugins publish on the bus; `"rest"` → plugins POST to `/session/*`. Both paths are always wired so the build exercises them; this only decides which one is authoritative in production |
| `enable-destructive-demos` | bool | `false` | Retained only as a conservative default for copied configs |

## Events

Plugin-side artifacts may publish these names as `CustomCloudEvent` payloads.
The backend route/task bridge is pending the platform-route/event API, but the
payload records remain reusable.

| Event type | Publisher | Subscriber in this module | Payload record |
|---|---|---|---|
| `PLAYTIME:SESSION_START` | plugin (Paper/Folia/Velocity) | pending platform event bridge | `SessionStart(playerId, sessionId, serverName, joinAt)` |
| `PLAYTIME:SESSION_END`   | plugin | pending platform event bridge | `SessionEnd(sessionId, quitAt, durationMs)` |
| `PLAYTIME:TOP_UPDATED`   | backend bridge after rebuild | dashboard page (`useModuleEvents`) | the page re-pulls `/top` |

## REST endpoints

`PlaytimeRoutes` is kept as a reusable route surface for the upcoming
platform-route API. It is unit-tested without booting the controller.

| Method | Path | Request | Response | Notes |
|---|---|---|---|---|
| `GET`  | `/top?limit=<n>` | — | `TopResponse(count, List<TopEntry>)` | `limit` clamped to `[1, topSize * 4]`; 400 on non-numeric |
| `GET`  | `/player/{uuid}` | — | `PlayerResponse(playerId, totalMs, sessionCount, lastSeen, recentSessions)` | 400 on malformed UUID, 404 when totals + recent are both empty |
| `POST` | `/session/start` | `SessionStartRequest(playerId, sessionId, serverName, joinAt)` | `202 {"ok": true}` | 400 on missing `playerId` / `sessionId` / `joinAt` |
| `POST` | `/session/end`   | `SessionEndRequest(sessionId, quitAt, durationMs)` | `202 {"ok": true}` | 400 on missing field or `durationMs < 0` |
| `GET`  | `/config`        | — | `Config` record | Useful for debugging hot-reload |

Validation pattern lives at the top of `PlaytimeRoutes#register`; mirror it
across your own routes when the platform-route API is wired.

## Cross-module service API

Other modules can consume playtime data without a hard dependency on this
module's internals by requiring the `example-playtime-query` capability. The
published handle uses `java.util.function.ToLongFunction<UUID>` because JDK
types are parent-loaded and remain safe across isolated module classloaders:

```java
@SuppressWarnings("unchecked")
ToLongFunction<UUID> playtime =
    (ToLongFunction<UUID>) context.requireCapability("example-playtime-query", ToLongFunction.class);
long totalMs = playtime.applyAsLong(playerId);
```

The module-local `PlaytimeQueryService` remains deliberately narrow and
read-only. Writes and lifecycle stay behind this module's own event handlers so
consumers cannot invalidate cache or ordering invariants.

## Plugin variants

| Subproject | Target | Strategy |
|---|---|---|
| `plugin/folia`       | Folia server        | Single JAR, intra-JAR `@ForVersion` dispatch |
| `plugin/paper/v1_20` | Paper 1.20          | Separate JAR per MC version (`versionedPlugins`) |
| `plugin/paper/v1_21` | Paper 1.21          | Separate JAR per MC version (`versionedPlugins`) |
| `plugin/velocity`    | Velocity proxy      | Single JAR, no version fan-out |

Two strategies are shown on purpose: the Folia plugin uses `@ForVersion` inside
a single jar (cheap when NMS usage is trivial), while Paper fans out to one jar
per MC version (needed when mappings/APIs diverge). See each plugin's Javadoc
for the "when to use which" rationale.

## Using this as a template

1. Copy `java/cloud-module/cloud-module-example` to
   `java/cloud-module/cloud-module-<your-name>`.
2. Rename the package `me.prexorjustin.prexorcloud.modules.example` →
   `me.prexorjustin.prexorcloud.modules.<yours>` (including every `plugin/...`
   subproject).
3. Update `build.gradle.kts`:
   - `archiveName.set("<your-module>")`
   - extension artifact subproject paths in `extensionArtifacts`
4. Update `src/main/module/module.yaml`:
   - `id`, `backend.entrypoint`, `capabilities`, and extension artifact paths
5. Replace the `sessions` / `totals` collections in `PlaytimeRepository` with
   your own domain model. Keep the manifest/storage/capability pattern.
6. Build the module JAR with `./gradlew
   :cloud-module:cloud-module-<your-name>:assemble`.

Do NOT copy the `STEP <n>` comments verbatim — they anchor the teaching
narrative to specific numbered docs sections. Rewrite them for your module.

## Running the tests

```bash
# Linux/macOS
./gradlew :cloud-module:cloud-module-example:test

# Windows
gradlew.bat :cloud-module:cloud-module-example:test
```

Tests live under `src/test/java/` and mock only the external boundaries
(`ModuleDataStore`, `PlaytimeRepository`) per the testing convention in [`CONTRIBUTING.md`](../../../CONTRIBUTING.md). They cover:

- `PlaytimeRepositoryTest` — collection bootstrap, CRUD delegation
- `ExamplePlatformModuleTest` — lifecycle, scoped storage, capability handle
- `PlaytimeRoutesTest` — validation, clamping, 400/404, happy-path (uses a
  hand-rolled `FakeRegistrar` / `FakeRequest` / `FakeResponse` so no
  controller boot is needed)
- `PlaytimeQueryServiceImplTest` — published service contract
- `ConfigTest` — `defaults()` round-trip
