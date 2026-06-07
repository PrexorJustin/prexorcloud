---
title: Modded servers (Fabric and NeoForge)
description: Run Fabric and NeoForge dedicated servers under PrexorCloud — how the cloud server mod registers and reports players and metrics, which Minecraft and loader versions are supported, and how to add a platform to the catalog and create a group.
---

PrexorCloud ships a server-side mod for Fabric and NeoForge dedicated servers.
The mod does the same job as the Bukkit/Paper plugin: it registers the
Instance with the Controller, reports player join and leave, and pushes a
metrics snapshot on a fixed tick interval. It reuses the same
platform-agnostic `ServerControllerClient` as every other server plugin, so an
Instance running Fabric or NeoForge appears in the dashboard and routing exactly
like a Paper one.

This page covers the supported versions, what the mod reports, what gets bundled
into the mod jar, and how to add a Fabric/NeoForge platform to the catalog and
create a Group.

> The mod jar is not auto-bundled into base templates yet. The Controller
> auto-installs the bundled plugin for `paper`, `spigot`, `folia`, `velocity`,
> `bungeecord`, and `geyser` only. For Fabric and NeoForge you place the mod jar
> into the Template's `mods/` directory yourself — see
> [Install the mod into a template](#install-the-mod-into-a-template).

## Supported versions

Both mods target Minecraft 1.21.1 on Java 21. The build and metadata pin the
following.

| | Fabric | NeoForge |
|---|---|---|
| Minecraft | `1.21.1` | `1.21.1` (range `[1.21.1,1.22)`) |
| Java | `>= 21` | 21 (toolchain) |
| Loader | Fabric Loader `>= 0.16.0` (built against `0.16.10`) | NeoForge `[21.1,)` (built against `21.1.233`) |
| Loader API | `fabric-api` (built against `0.116.12+1.21.1`) | — |
| Build system | Fabric Loom `1.16.3` (Yarn mappings `1.21.1+build.3`) | ModDevGradle `2.0.141` (Mojang official mappings) |

Sources:

- Fabric: `java/cloud-plugins/server/fabric/build.gradle.kts`,
  `java/cloud-plugins/server/fabric/src/main/resources/fabric.mod.json`.
- NeoForge: `java/cloud-plugins/server/neoforge/build.gradle.kts`,
  `java/cloud-plugins/server/neoforge/src/main/resources/META-INF/neoforge.mods.toml`
  (`pack_format` 48 in `pack.mcmeta`).
- Loader/build versions are pinned in `java/gradle/libs.versions.toml`.

The `depends`/`dependencies` blocks are enforced by the loader at startup. A
Fabric server below loader `0.16.0` or a Minecraft version outside `~1.21.1`
will refuse to load the mod; NeoForge enforces `loaderVersion = "[4,)"`
(`javafml`) and the dependency ranges above.

## How the mod works

The mod has one entry point per loader. Both follow the same lifecycle, mirroring
the Bukkit `AbstractCloudPlugin`.

- Fabric: `me.prexorjustin.prexorcloud.server.fabric.PrexorCloudFabric`
  implements `DedicatedServerModInitializer` and listens on Fabric's event API.
- NeoForge: `me.prexorjustin.prexorcloud.server.neoforge.PrexorCloudNeoForge`
  is annotated `@Mod("prexorcloud")` and listens on the game bus
  (`NeoForge.EVENT_BUS`), not the mod event bus.

Both read their configuration from `CLOUD_*` environment variables through
`PluginEnv` (in `cloud-api`):

| Variable | Read by | Meaning |
|---|---|---|
| `CLOUD_INSTANCE_ID` | `PluginEnv.instanceId()` / `isCloudManaged()` | Instance id; its presence is the cloud-managed flag |
| `CLOUD_GROUP` | `PluginEnv.group()` | Group this Instance belongs to |
| `CLOUD_NODE_ID` | `PluginEnv.nodeId()` | Node hosting the Instance |
| `CLOUD_CONTROLLER_HOST` / `CLOUD_CONTROLLER_PORT` | `PluginEnv.controllerUrl()` | Controller REST endpoint (`http://host:port`) |
| `CLOUD_PLUGIN_TOKEN` | `PluginEnv.pluginToken()` | Bearer token for `/api/plugin/*` calls |

The Daemon injects these into every managed Instance's process environment when
it launches the server. You do not set them by hand.

### Standalone detection

If `CLOUD_INSTANCE_ID` is not set, the mod logs a warning and does nothing:

```text
PrexorCloud: CLOUD_INSTANCE_ID not set -- running in standalone mode
```

This means the same jar is safe to drop into a server you run by hand — it stays
inert unless launched by the Daemon. Servers not managed by the cloud are left
untouched.

### Lifecycle and reporting

On `onInitializeServer` (Fabric) or in the mod constructor (NeoForge), the mod
checks `PluginEnv.isCloudManaged()`, then constructs a
`ServerControllerClient(controllerUrl, pluginToken)`. It registers four hooks:

| Event | Fabric event | NeoForge event | Call |
|---|---|---|---|
| Server started | `ServerLifecycleEvents.SERVER_STARTED` | `ServerStartedEvent` | `client.reportReady()` |
| Player join | `ServerPlayConnectionEvents.JOIN` | `PlayerEvent.PlayerLoggedInEvent` | `client.reportPlayerJoin(uuid, name, group)` |
| Player leave | `ServerPlayConnectionEvents.DISCONNECT` | `PlayerEvent.PlayerLoggedOutEvent` | `client.reportPlayerLeave(uuid)` |
| Server tick | `ServerTickEvents.END_SERVER_TICK` | `ServerTickEvent.Post` | `client.reportMetrics(...)` every 200 ticks |

`reportReady()` POSTs `{}` to `/api/plugin/ready`, signaling that startup is
complete and the Instance can accept players. On connect the mod logs:

```text
PrexorCloud connected (instanceId=..., group=...)
```

The metrics snapshot is collected and pushed every `200` ticks
(`METRICS_INTERVAL_TICKS`), about every 10 seconds at 20 TPS. All four calls are
non-blocking: `ServerControllerClient` posts asynchronously, and serialization
failures are logged as warnings, not raised.

The client targets these Controller endpoints under `/api/plugin`:

- `POST /api/plugin/ready`
- `POST /api/plugin/player-join` — body `{uuid, name, group}`
- `POST /api/plugin/player-leave` — body `{uuid}`
- `POST /api/plugin/metrics` — the metrics payload below

The same `ServerControllerClient` also exposes `requestTransfer`,
`requestTransferToGroup`, and `sendNetworkMessage`, but the Fabric/NeoForge entry
points do not call those — they only register, report players, and report
metrics.

### What metrics are reported

Each snapshot is an `InstanceMetricsPayload` (in `server/shared`), serialized to
JSON for `POST /api/plugin/metrics`. The collectors
(`FabricMetricsCollector`, `NeoForgeMetricsCollector`) fill these fields:

| Field | Fabric source | NeoForge source |
|---|---|---|
| `tps1m` / `tps5m` / `tps15m` | derived from MSPT (same value in all three) | same |
| `msptAvg` | `server.getAverageTickTime()` | `getAverageTickTimeNanos() / 1e6` |
| `heapUsedMb` / `heapMaxMb` / `heapCommittedMb` | JMX `MemoryMXBean` heap usage | same |
| `gcCollections` / `gcTimeMs` | JMX `GarbageCollectorMXBean` | same |
| `threadCount` / `daemonThreadCount` | JMX `ThreadMXBean` | same |
| `playerCount` / `maxPlayers` | `getCurrentPlayerCount()` / `getMaxPlayerCount()` | `getPlayerCount()` / `getMaxPlayers()` |
| `worldCount`, `totalChunks`, per-world snapshots | `server.getWorlds()` | `server.getAllLevels()` |
| `pluginCount` | `FabricLoader.getAllMods().size()` | `ModList.get().size()` |
| `serverVersion` | `"Fabric " + server.getVersion()` | `"NeoForge " + server.getServerVersion()` |
| `uptimeMs` | wall-clock since collector construction | same |

The JVM section (heap, GC, threads) is byte-for-byte identical to the Bukkit
collector — it is pure JMX. TPS is derived from MSPT and clamped to 20.0; the
1m/5m/15m values are all set to the same instantaneous figure (neither loader
exposes load-averaged TPS).

Per-world snapshots (`WorldSnapshot`) carry `name`
(the dimension key, e.g. `minecraft:overworld`), `environment` (the key path,
e.g. `overworld`), `chunkCount`, and `playerCount`.

> Entity counts are not reported. `totalEntities` and every
> `WorldSnapshot.entityCount` are sent as `0`, because neither Fabric nor
> NeoForge exposes a cheap aggregate entity count. Chunk and player counts are
> accurate.

## What gets bundled into the mod jar

The published mod jar is self-contained. The build shades `server:shared` and
its entire runtime closure into the jar so the cloud code runs without any extra
mods on the classpath.

Bundled into the jar:

- `cloud-plugins:server:shared` (`ServerControllerClient`,
  `InstanceMetricsPayload`, the metrics types)
- its transitive runtime closure: `cloud-api`, the internal plugin runtime, and
  Jackson (for JSON serialization)

Excluded from the jar (the loader provides them at runtime):

- `org.slf4j:slf4j-api`
- `ch.qos.logback:logback-classic`
- `ch.qos.logback:logback-core`

Bundling a logging binding would drop a competing `SLF4JServiceProvider` into
the mod jar and hijack the host's logging. The cloud code depends only on
`slf4j-api` and logs through the loader's binding.

Build differences:

- Fabric uses Loom. Loom remaps the shaded jar against Yarn mappings, so the
  published artifact is the *remapped* shadow jar (`remapJar` consumes
  `shadowJar`). Fabric Loader provides `slf4j-api` on the mod classpath, which is
  why the shade step excludes it.
- NeoForge uses ModDevGradle and compiles against Mojang's official mappings —
  the same names the runtime uses — so there is no remap step. The shaded jar
  (`PrexorCloudNeoForge.jar`) is the distributable mod. NeoForge pins
  `slf4j-api` to 2.0.9, so the build also excludes the transitive `slf4j-api`
  from the `bundled` configuration to avoid a version conflict.

## Add a Fabric or NeoForge platform to the catalog

The catalog (`config/catalog.yml`) lists every platform PrexorCloud can run and
the downloadable server jar for each version. `platform` is a free-form string —
there is no hardcoded enum — so `FABRIC` and `NEOFORGE` are valid platform names
the moment you add them.

Adding the first version of a new platform also creates the `base-{platform}`
Template (lazily, via `ensurePlatformTemplate`). For `FABRIC` and `NEOFORGE`
that Template is created with server defaults (`server.properties`, `eula.txt`)
but **without** a bundled cloud mod, because the mod is not in the Controller's
bundled-plugins set yet.

### Option A: edit `config/catalog.yml`

Add a platform block. The schema is `platform`, `category`, `configFormat`, and a
`versions` list of `{version, downloadUrl, sha256, recommended}`.

```yaml
platforms:
  - platform: "FABRIC"
    category: "SERVER"
    configFormat: "server"
    versions:
      - version: "1.21.1"
        downloadUrl: "https://example.com/fabric-server-1.21.1.jar"
        sha256: "<sha256-of-the-launcher-jar>"
        recommended: true
  - platform: "NEOFORGE"
    category: "SERVER"
    configFormat: "server"
    versions:
      - version: "1.21.1"
        downloadUrl: "https://example.com/neoforge-server-1.21.1.jar"
        sha256: "<sha256-of-the-server-jar>"
        recommended: true
```

Notes:

- `category` must be `SERVER` (not `PROXY`). Anything other than `PROXY` is
  treated as a server.
- `configFormat` selects which default config set the base Template copies. There
  is no `fabric`/`neoforge` resource set, so use `server` — it ships
  `server.properties` and `eula.txt`, which is all a modded server needs from the
  Template. (Leaving `configFormat` blank falls back to the platform name and
  would look for a non-existent `fabric`/`neoforge` resource set.)
- `downloadUrl` must point at the server launcher jar the Daemon will run. For
  Fabric this is the Fabric server launcher; for NeoForge the NeoForge installer
  output / server jar. Provide a `sha256` so the Daemon can verify the download.

### Option B: REST / dashboard

POST a version to the catalog. This is what the dashboard's catalog editor calls.

```bash
curl -X POST "$CONTROLLER/api/v1/catalog/FABRIC/versions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "version": "1.21.1",
        "downloadUrl": "https://example.com/fabric-server-1.21.1.jar",
        "sha256": "<sha256>",
        "category": "SERVER",
        "configFormat": "server"
      }'
```

Request body is `AddCatalogVersionRequest`
(`version, downloadUrl, sha256, category, configFormat`). Other catalog routes:

- `GET /api/v1/catalog` — list all entries
- `PUT /api/v1/catalog/{platform}/versions/{version}/recommended` — mark recommended
- `DELETE /api/v1/catalog/{platform}/versions/{version}` — remove a version (removes the platform if it was the last)

Adding the first version of a platform triggers `ensurePlatformTemplate`, which
creates `base-FABRIC` / `base-NEOFORGE` if it does not already exist.

> There is no `prexorctl catalog` subcommand. Manage the catalog through
> `config/catalog.yml` or the `/api/v1/catalog` REST routes.

## Install the mod into a template

Because the Controller does not auto-bundle the Fabric/NeoForge mod, you place it
into the platform's base Template (or a custom Template you layer on the Group).
The mod loads from the standard mods directory.

1. Build the mod jar:

   ```bash
   ./gradlew :cloud-plugins:server:fabric:build
   ./gradlew :cloud-plugins:server:neoforge:shadowJar
   ```

   Fabric output: `java/cloud-plugins/server/fabric/build/libs/` (the remapped
   jar, no classifier). NeoForge output:
   `java/cloud-plugins/server/neoforge/build/libs/PrexorCloudNeoForge.jar`.

2. Copy the jar into the Template's `mods/` directory. Extensions and the
   bootstrap cache place server artifacts under their `installPath` relative to
   the Instance directory; for Fabric/NeoForge that path is `mods/` (the same
   way Paper extensions land in `plugins/`).

   For example, for the `base-FABRIC` Template's files directory on the
   Controller:

   ```text
   <template-files>/mods/prexorcloud-1.0.0.jar
   ```

   For NeoForge use `base-NEOFORGE` and the `PrexorCloudNeoForge.jar`.

3. Also place the Fabric API jar in `mods/` if your server jar does not already
   bundle it — the Fabric mod declares `fabric-api` as a required dependency.
   NeoForge has no equivalent external API jar requirement.

The Template's files are materialized into each Instance directory on start, so
a jar under the Template's `mods/` is present before the server boots.

## Create a group

A Group binds a platform and version. The platform value must match a catalog
entry. The CLI command is the same for every platform.

```bash
prexorctl group create \
  --name fabric-survival \
  --platform FABRIC \
  --platform-version 1.21.1 \
  --template base-fabric \
  --scaling-mode STATIC \
  --min 1 --max 1 \
  --memory 4096 \
  --routing LOWEST_PLAYERS \
  --port-start 30000 --port-end 30100
```

Flags (from `prexorctl group create`):

| Flag | Default | Meaning |
|---|---|---|
| `--name` | (required) | Group name |
| `--platform` | (required) | Platform, e.g. `FABRIC` / `NEOFORGE` |
| `--platform-version` | — | Version from the catalog (e.g. `1.21.1`) |
| `--template` | — | Template layers, ordered (repeatable / comma-separated) |
| `--scaling-mode` | `DYNAMIC` | `STATIC`, `DYNAMIC`, or `MANUAL` |
| `--min` / `--max` | `1` / `10` | Instance bounds |
| `--memory` | `1024` | Memory per Instance (MB) |
| `--routing` | `LOWEST_PLAYERS` | Routing strategy |
| `--port-start` / `--port-end` | `30000` / `30100` | Port range |

The CLI POSTs to `/api/v1/groups` with `jarFile` defaulting to `server.jar`. The
Daemon downloads the catalog jar for `FABRIC 1.21.1`, places it as the server
jar, syncs the Template (including `mods/`), and launches the server with the
`CLOUD_*` environment injected.

> Modded survival worlds are usually long-lived and stateful. Use
> `--scaling-mode STATIC` (or `MANUAL`) and a single replica unless your mod set
> genuinely supports horizontal scaling — `DYNAMIC` will spin up additional
> Instances under load, each with its own world.

## Verify the instance reported in

After the Group's first Instance starts, confirm the mod registered.

```bash
prexorctl group info fabric-survival
```

You should see the Instance listed. Check the Instance log for the connect line:

```text
PrexorCloud connected (instanceId=..., group=fabric-survival)
```

If you instead see `CLOUD_INSTANCE_ID not set -- running in standalone mode`,
the server was not launched by the Daemon (the mod is inert) — start the Instance
through PrexorCloud, not by hand.

## Limitations and notes

- Minecraft 1.21.1 only. The mod metadata pins `~1.21.1` (Fabric) and
  `[1.21.1,1.22)` (NeoForge). Running a different Minecraft version requires a
  new build with updated pins and mappings.
- No auto-bundle. Unlike Paper/Spigot/Folia/Velocity/BungeeCord/Geyser, the
  Fabric and NeoForge mods are not embedded in the Controller and not installed
  into base Templates automatically. Install them into `mods/` yourself.
- Entity counts are reported as `0` (see [What metrics are reported](#what-metrics-are-reported)).
- The mods only register, report players, and report metrics. Transfer and
  network-message APIs exist on `ServerControllerClient` but are not wired into
  the Fabric/NeoForge entry points.
