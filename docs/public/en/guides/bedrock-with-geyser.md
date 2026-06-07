---
title: Bedrock support with Geyser
description: Route Bedrock players edition-aware, run the Geyser proxy sidecar, and provision a managed standalone Geyser Instance that fronts a Java proxy Group.
---

# Bedrock support with Geyser

This page covers Bedrock support end to end:

1. **Edition-aware routing** — how proxies detect Bedrock players and send them to dedicated lobby/fallback Groups in a Network.
2. **The Geyser sidecar** — Geyser running as an extension inside an existing Java proxy.
3. **A managed standalone Geyser Instance** — the `GEYSER` catalog platform, the Group field `bedrockProxyGroup`, and how the Controller injects a live Java-proxy remote into Geyser's config at provision time.

Audience: operators who run a Bedrock-capable Network, and developers who want to know exactly which UUID, field, and config key is in play.

There are two ways to accept Bedrock clients. They are not mutually exclusive, but most setups pick one:

| Topology | Where Geyser runs | Who routes Bedrock players | Best for |
|---|---|---|---|
| Sidecar | As a Geyser extension inside a Velocity/BungeeCord proxy Instance (or a backend) | The Java proxy, using edition-aware `NetworkRouter` | Networks that already run a Java proxy |
| Standalone | As its own managed Instance from the `GEYSER` platform | The Java proxy it forwards to (Geyser only translates) | Isolating Bedrock translation from the Java proxy |

## How a Bedrock player is detected

Detection is derived from the player UUID. No extra transport from the plugins is needed.

Floodgate — the standard Geyser auth companion — assigns every Bedrock player a Java UUID of the form `new UUID(0, xuid)`: the high 64 bits are zero. `PlayerEdition.detect` checks exactly that:

```java
// cloud-api: me.prexorjustin.prexorcloud.api.domain.PlayerEdition
public static String detect(UUID uuid) {
    return uuid != null && uuid.getMostSignificantBits() == 0L ? BEDROCK : JAVA;
}
```

The constants are `"java"` and `"bedrock"`. `PlayerEdition` lives in `cloud-api` so the Controller (player visibility) and the proxy plugins (routing) share one detector.

**Scope and limits.** `detect` recognises Bedrock players that authenticate through Floodgate in its default, non-prefixed UUID mode — the recommended setup. A standalone Geyser proxy *without* Floodgate hands out ordinary random Java UUIDs and is indistinguishable here; such players are reported as `java`.

The standalone Geyser sidecar does not depend on this heuristic for session reporting. It reports every Bedrock session with an explicit `edition=bedrock` to the Controller, authoritative even when the Java UUID looks ordinary. Edition-aware *routing*, which runs on the Java proxy, still relies on `detect`.

## Edition-aware routing in a Network

A `NetworkComposition` defines lobby spawn and fallback routing over backend Groups. Two optional fields add a separate Bedrock route:

| Field | Type | Empty/blank means |
|---|---|---|
| `bedrockLobbyGroup` | `String` | Bedrock players use `lobbyGroup` |
| `bedrockFallbackGroups` | `List<String>` | Bedrock players use `fallbackGroups` |

Use them when only a subset of your backends run Geyser and can accept Bedrock clients. When both are unset, Bedrock players follow the same route as Java players — preserving pre-Bedrock behaviour.

The full record (`cloud-api`):

```java
public record NetworkComposition(
        String name,
        String description,
        String lobbyGroup,
        List<String> fallbackGroups,
        List<String> memberGroups,
        List<String> proxyGroups,
        String kickMessage,
        String bedrockLobbyGroup,
        List<String> bedrockFallbackGroups) { ... }
```

`name` is required and must match `[a-z0-9_][a-z0-9_-]*`. `lobbyGroup` is required. A seven-argument constructor (without the two Bedrock fields) still exists for compatibility.

### How the proxy applies it

The proxy reads Networks from `GET /api/proxy/networks` and builds a `NetworkRouter` for its own proxy Group. On join and on kick it calls `detect(uuid)` and passes the edition into the router:

```java
// VelocityPlayerListener (BungeeCord is equivalent)
String edition = PlayerEdition.detect(event.getPlayer().getUniqueId());
List<String> chain = router.fallbackChain(null, edition);   // on join
List<String> chain = router.fallbackChain(sourceGroup, edition); // on kick
```

`NetworkRouter` resolves the route per edition:

- **Join target** (`joinTargetGroup(edition)`): for a Bedrock player, `bedrockLobbyGroup` when set, otherwise `lobbyGroup`. With no Network applied, the cluster default Group.
- **Fallback chain** (`fallbackChain(excludeGroup, edition)`): `[lobby] ++ fallbacks` for the edition, deduplicated, with `excludeGroup` removed (the Group the player was just kicked from). For Bedrock players this uses `bedrockLobbyGroup` / `bedrockFallbackGroups` when configured, else the shared Java route.
- **Kick message** (`kickMessage(default)`): the Network's `kickMessage` when non-blank, else the supplied default.

A blank `bedrockLobbyGroup` or empty `bedrockFallbackGroups` always falls back to the Java route — so partial configuration is safe.

### Create a Bedrock-aware Network

Networks are managed over REST (`/api/v1/networks`) and the dashboard. There is no `prexorctl network` command. Create one with a `POST`:

```bash
curl -sS -X POST https://controller.example.com/api/v1/networks \
  -H "Authorization: Bearer $PREXOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "main",
    "description": "Survival network",
    "lobbyGroup": "lobby",
    "fallbackGroups": ["survival"],
    "memberGroups": ["lobby", "survival"],
    "proxyGroups": ["edge"],
    "kickMessage": "Disconnected from the network.",
    "bedrockLobbyGroup": "lobby-bedrock",
    "bedrockFallbackGroups": ["survival-bedrock"]
  }'
```

Validation enforced by the Controller:

- All referenced Groups (`lobbyGroup`, `fallbackGroups`, `memberGroups`, `proxyGroups`) must already exist.
- Each `proxyGroups` entry must reference a proxy-platform Group; otherwise the call fails with `proxyGroups entry '<name>' is not a proxy platform`.
- `proxyGroups` must contain no blanks or duplicates.
- A duplicate name returns `409`; an invalid name or unknown referenced Group returns `400`.

`bedrockLobbyGroup` and `bedrockFallbackGroups` are not range-validated against each other — an empty Bedrock route simply defers to the Java route at request time.

## Topology 1: the Geyser sidecar

Geyser is a Bedrock-to-Java protocol translator, not a server-list proxy. It forwards every Bedrock client to its single configured remote (typically a Java proxy that already does edition-aware routing). The sidecar's job is narrow:

- Register the Geyser process with the Controller as a proxy Instance, through the shared `AbstractProxyCloudPlugin` lifecycle.
- Report every Bedrock session as `edition=bedrock`.

The extension is `cloud-plugins/proxy/geyser`, packaged as `PrexorCloudGeyserExtension.jar` and loaded from the instance's `extensions/` directory. Its descriptor (`extension.yml`):

```yaml
id: prexorcloud
name: PrexorCloud
main: me.prexorjustin.prexorcloud.proxy.geyser.PrexorCloudGeyser
version: 1.0.0
api: 1.0.0
```

Behaviour, from `GeyserCloudCore`:

- **Session join** reports `connection.javaUuid()`, the Bedrock username (falling back to the Java username), the Instance, the Group, and `PlayerEdition.BEDROCK`.
- **Session disconnect** reports the leave.
- **`registerBackend` / `unregisterBackend`** are deliberate no-ops — Geyser keeps no backend server map.
- **`transferPlayer`** uses Geyser's `GeyserConnection.transfer(host, port)`. This only matters in the standalone-straight-to-backend topology; behind a Java proxy, the proxy owns transfers.
- **Player count** and **pings** come from `geyserApi().onlineConnections()`.

You do not configure the sidecar's routing — the Java proxy it sits in front of (or alongside) handles that with the edition-aware `NetworkRouter` described above.

## Topology 2: a managed standalone Geyser Instance

Here Geyser runs as its own Instance, scheduled from the `GEYSER` platform. The Controller wires it to a live Java proxy at provision time.

### The `GEYSER` catalog platform

`GEYSER` is a `ConfigFormat` value in the protocol (`daemon_service.proto`):

```proto
enum ConfigFormat {
  CONFIG_FORMAT_UNSPECIFIED = 0;
  PAPER = 1;
  SPIGOT = 2;
  VELOCITY = 3;
  BUNGEECORD = 4;
  GEYSER = 5;
}
```

The catalog starts empty; you register platforms in `config/catalog.yml` or over the catalog REST API. Register Geyser as a **proxy** platform with config format `geyser` and one or more downloadable versions (the Geyser standalone jar):

```yaml
# config/catalog.yml
platforms:
  - platform: geyser
    category: PROXY
    configFormat: geyser
    versions:
      - version: "2.x"
        downloadUrl: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/standalone"
        sha256: "<sha256-of-the-jar>"
```

`category: PROXY` makes the Instance a proxy workload; `configFormat: geyser` selects the Geyser config baseline and the Geyser config patcher.

### The base template the Controller generates

On first use of a `geyser` platform the Controller's `BaseTemplateGenerator` creates `base-geyser`:

- It copies the shipped Geyser `config.yml` baseline.
- It installs `PrexorCloudGeyserExtension.jar` into the instance's `extensions/` directory (Geyser loads integrations as extensions, not from `plugins/`).

The shipped `config.yml` baseline:

```yaml
bedrock:
  address: 0.0.0.0
  port: %PORT%
  clone-remote-port: false
  motd1: "PrexorCloud"
  motd2: "Bedrock"
  server-name: "PrexorCloud"
  compression-level: 6
  enable-proxy-protocol: false
remote:
  address: 127.0.0.1
  port: 25565
  auth-type: floodgate
  allow-password-authentication: true
  use-proxy-protocol: false
  forward-hostname: false
floodgate-key-file: key.pem
command-suggestions: true
passthrough-motd: false
passthrough-player-counts: false
legacy-ping-passthrough: false
ping-passthrough-interval: 3
forward-player-ping: false
max-players: %MAX_PLAYERS%
debug-mode: false
```

Two kinds of value get filled in:

- `%PORT%` and `%MAX_PLAYERS%` are `%VARIABLE%` placeholders substituted by the Daemon at template prep from the Instance's allocated port and the Group's `maxPlayers` (default 100). This sets the Bedrock listener.
- `remote.address` / `remote.port` are injected dynamically by the Controller at provision time (next section). Until a proxy is running they keep the conservative fallback `127.0.0.1:25565`.

`auth-type: floodgate` and `floodgate-key-file: key.pem` are part of the baseline. PrexorCloud does not generate `key.pem`. For Floodgate auth, supply the shared Floodgate key as `key.pem` in the Group's Template (or the matching plugin on your Java backends); without it Geyser falls back to its own auth handling.

### The Group field `bedrockProxyGroup`

A Geyser Group declares which Java proxy Group it fronts:

```java
// GroupConfig (controller)
@JsonProperty("bedrockProxyGroup") String bedrockProxyGroup
```

It defaults to `""` (empty for non-Geyser Groups). Validation in `GroupManager`:

- `bedrockProxyGroup` must not equal the Group's own name — `bedrockProxyGroup cannot reference the group itself`.
- The referenced Group must exist — otherwise `bedrockProxyGroup not found: <name>`.

Inheritance: a child Group inherits the parent's `bedrockProxyGroup` when its own is empty.

There is no `prexorctl group create` flag for this field. Set it over REST when creating or updating the Group (the body is a full `GroupConfig`):

```bash
curl -sS -X PUT https://controller.example.com/api/v1/groups/bedrock-gate \
  -H "Authorization: Bearer $PREXOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "bedrock-gate",
    "platform": "geyser",
    "platformVersion": "2.x",
    "scalingMode": "STATIC",
    "minInstances": 1,
    "maxInstances": 1,
    "maxPlayers": 100,
    "bedrockProxyGroup": "edge"
  }'
```

Here `edge` is your Java proxy (Velocity/BungeeCord) Group. The dashboard exposes the same field.

### How the remote is injected at provision time

When the Controller plans a Geyser Instance (`InstanceCompositionPlanner`), the `geyser` config-format branch builds dynamic `remote.*` patches:

1. Read `group.bedrockProxyGroup()`. If blank, log a warning and keep the config default.
2. Ask the `BedrockRemoteResolver` for a live endpoint of that proxy Group.
3. The wired resolver, `ClusterStateBedrockRemoteResolver`, picks the first `RUNNING` Instance of the proxy Group and combines its node's advertised host with the Instance's listen port. It strips a trailing `:port` from the node address while keeping bare hosts and bracketed IPv6 intact.
4. On a hit, emit two patches:

   ```text
   remote.address = <proxy host>
   remote.port    = <proxy listen port>
   ```

5. On a miss (no proxy running yet — cold start), log a warning and keep the config default. The remote stays at `127.0.0.1:25565` until the Instance is provisioned again with a proxy up.

The patch keys are dotted paths because Geyser's `config.yml` nests duplicate leaf keys — `bedrock.port` and `remote.port` both exist.

### How the Daemon applies the patch

The Daemon's `ServerConfigPatcher` has a dedicated `geyser` branch. The flat patcher used for other formats can't disambiguate `bedrock.port` from `remote.port`, so Geyser is handled separately and the generic pass is skipped:

- `patchGeyserConfig` reads `config.yml`, then applies each dotted-path patch with `setNestedYamlKey`.
- `setNestedYamlKey` descends one section at a time, bounding the search to each parent's indentation block, so `remote.address` lands under `remote:` and not anywhere else.
- Line-level edits preserve the shipped file's comments and formatting.
- A key that isn't present in the file is skipped with a warning (`Geyser config key not found, skipping: <key>`) — Geyser fills its own defaults on boot rather than receiving a misplaced key.

### Cold-start ordering

Because the remote is resolved from a *running* proxy Instance:

- Start (or have running) at least one Instance of the `bedrockProxyGroup` before the Geyser Instance is provisioned, so the resolver finds an endpoint.
- If the Geyser Instance comes up first, its `remote` stays at the `127.0.0.1:25565` default. Restart or reprovision the Geyser Instance after the proxy is `RUNNING` to pick up the live endpoint.
- Use a `STATIC` Geyser Group (one fixed Instance) when you want a stable Bedrock front door, and a separate, already-running Java proxy Group as its `bedrockProxyGroup`.

## Worked example: standalone Geyser fronting a Velocity edge

Goal: Bedrock players connect to a Geyser front door, which forwards to a live Velocity proxy; Bedrock players then land in a dedicated Bedrock lobby.

1. **Register the Geyser platform** in `config/catalog.yml` (see above), `category: PROXY`, `configFormat: geyser`.

2. **Run a Java proxy Group** `edge` (Velocity), already scheduled and `RUNNING`. This is what Geyser forwards to and what does edition-aware routing.

3. **Create the Geyser Group** pointing at it:

   ```bash
   curl -sS -X POST https://controller.example.com/api/v1/groups \
     -H "Authorization: Bearer $PREXOR_TOKEN" -H "Content-Type: application/json" \
     -d '{
       "name": "bedrock-gate",
       "platform": "geyser",
       "platformVersion": "2.x",
       "scalingMode": "STATIC",
       "minInstances": 1, "maxInstances": 1,
       "bedrockProxyGroup": "edge"
     }'
   ```

   When the Geyser Instance provisions, the Controller resolves `edge`'s running Instance and patches `remote.address` / `remote.port` into its `config.yml`. The Bedrock listener binds the Instance's allocated `%PORT%`.

4. **Add the Bedrock route to your Network** so Bedrock players (detected by Floodgate UUID on the Velocity proxy) spawn into a Bedrock-friendly lobby:

   ```bash
   curl -sS -X PUT https://controller.example.com/api/v1/networks/main \
     -H "Authorization: Bearer $PREXOR_TOKEN" -H "Content-Type: application/json" \
     -d '{
       "name": "main",
       "lobbyGroup": "lobby",
       "fallbackGroups": ["survival"],
       "proxyGroups": ["edge"],
       "bedrockLobbyGroup": "lobby-bedrock",
       "bedrockFallbackGroups": ["survival-bedrock"]
     }'
   ```

5. **Supply the Floodgate key** as `key.pem` in the `bedrock-gate` Group's Template (and the matching Floodgate plugin/key on the Velocity proxy) so Bedrock UUIDs are Floodgate-shaped and `PlayerEdition.detect` returns `bedrock`.

Result: a Bedrock client hits `bedrock-gate` on its Bedrock port, Geyser translates and forwards to a live `edge` Instance, the Velocity proxy detects the Floodgate UUID as Bedrock and routes the player to `lobby-bedrock`, failing over through `survival-bedrock`.

## Reference

| Item | Where | Value / behaviour |
|---|---|---|
| Bedrock detection | `PlayerEdition.detect` (`cloud-api`) | `getMostSignificantBits() == 0` → `bedrock`, else `java` |
| Edition constants | `PlayerEdition` | `"java"`, `"bedrock"` |
| Network Bedrock route | `NetworkComposition` | `bedrockLobbyGroup` (String), `bedrockFallbackGroups` (List) |
| Network REST | `POST/PUT /api/v1/networks`, `GET /api/proxy/networks` | full `NetworkComposition` body |
| Proxy routing | `NetworkRouter` (cloud-plugins/internal) | edition overloads on join/kick; blank/empty Bedrock fields defer to Java route |
| Geyser sidecar | `cloud-plugins/proxy/geyser`, `PrexorCloudGeyserExtension.jar` | loads from `extensions/`; reports `edition=bedrock` |
| Geyser config format | `ConfigFormat.GEYSER = 5` (proto) | selects Geyser baseline + patcher |
| Geyser Group field | `GroupConfig.bedrockProxyGroup` (default `""`) | proxy Group Geyser fronts; must exist, not self |
| Remote resolver | `ClusterStateBedrockRemoteResolver` | first `RUNNING` Instance of the proxy Group: node host + Instance port |
| Config baseline | `defaults/platform/geyser/config.yml` | `%PORT%`, `%MAX_PLAYERS%` substituted; `remote.*` injected; default `127.0.0.1:25565` |
| Daemon patcher | `ServerConfigPatcher.patchGeyserConfig` | section-aware dotted-key patch; missing keys skipped |
