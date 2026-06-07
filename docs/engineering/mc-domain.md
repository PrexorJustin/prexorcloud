# Minecraft-domain primitives

PrexorCloud is a generic-looking orchestrator that earns its "the cloud system Minecraft deserves" tagline because of three primitives that exist nowhere else in this stack: **Network Composition**, **Event Choreography**, and the **Player Journey Bus**. Skip these and PrexorCloud is a polished generic orchestrator with a bit of MC plumbing. With them, it understands what "a Minecraft network" actually is.

## 1. Network Composition

### The problem

A Minecraft network is not a flat list of servers. It is a topology: players connect to a *proxy* group, the proxy lands them in a *lobby* group, from there they pick a survival/creative/minigame group, and on a kick they fall back to a known-good lobby. Most cloud systems make the operator describe this in proxy-plugin YAML, then forget to keep the cloud aware of it.

### The fix

`NetworkComposition` is a first-class controller record:

```java
record NetworkComposition(
    String name,
    String proxyGroup,
    String lobbyGroup,
    List<String> fallbackGroups,
    String kickMessage
) { ... }
```

Stored in MongoDB (`networks` collection), exposed via REST CRUD at `/api/v1/networks` (gated on `networks.view` / `.create` / `.update` / `.delete`), seeded on first install via `controller.yaml: networks:`.

The proxy plugins consume the same record at runtime via the read-only `/api/proxy/networks` endpoint (plugin-token auth). On the proxy side:

- **Velocity** — `VelocityPlayerListener.onChooseInitialServer` walks `[lobbyGroup] ++ fallbackGroups` to pick where to land the player. `KickedFromServerEvent` walks the same chain (excluding the kicking group) to redirect the player.
- **Bungee** — `BungeePlayerListener.ServerConnectEvent` and `ServerKickEvent` do the equivalent.

If the chain is exhausted, the player is disconnected with the network's `kickMessage`.

### Why it matters

- The dashboard sees the network composition and renders an editor under `/networks` — operators change topology without editing any plugin YAML.
- The proxy plugins are *dumb*. They cache one HTTP response and route by it. There is no plugin-side state to drift.
- A 100-server lobby↔survival↔creative network operates without a single proxy-plugin override file.

### Bootstrap

`controller.yaml: networks:` seeds networks on first install:

```yaml
networks:
  - name: main
    proxyGroup: proxy
    lobbyGroup: lobby
    fallbackGroups: [lobby-overflow]
    kickMessage: "<red>The lobby is full. Try again in a few minutes."
```

`PrexorCloudBootstrap.initNetworks` applies these only when the corresponding name is not already in `MongoNetworkStore`. Existing networks are not overwritten; subsequent edits go through the dashboard.

### Where it lives

- Record: `cloud-api/.../api/domain/NetworkComposition.java`
- Store: `controller/network/MongoNetworkStore.java`
- Manager: `controller/network/NetworkManager.java`
- Routes: `controller/rest/route/NetworkRoutes.java`, `controller/rest/route/proxy/ProxyNetworkRoutes.java`
- Cache (in proxy plugins): `cloud-plugins-internal/.../plugin/common/CloudStateCache.java`
- Router (in proxy plugins): `cloud-plugins-internal/.../plugin/common/NetworkRouter.java`
- Dashboard: `dashboard/app/pages/networks/index.vue`, `components/networks/NetworkDialog.vue`, `stores/networks.ts`

## 2. Event Choreography

### The problem

A network has time-bound state changes. Friday 19:00 UTC: scale lobby up because peak hours start. Saturday 02:00 UTC: scale down because peak hours end. Sunday 08:00 UTC: enable maintenance for an hour because patch rollout is scheduled. These are *overlays* on group config, not a separate scheduling system.

### The fix

`EventChoreography` is a cron-shaped overlay on the existing scaler. No new scheduler primitives. No separate engine.

```yaml
events:
  - id: peak-hours
    cron: "0 19 * * 5"             # Fri 19:00 UTC
    duration: "PT7H"               # 7 hours
    targetGroup: lobby
    overlay:
      minInstances: 4              # peak floor
      maxInstances: 20             # peak ceiling
      scalingMode: DYNAMIC

  - id: maintenance-window
    cron: "0 8 * * 0"              # Sun 08:00 UTC
    duration: "PT1H"
    targetGroup: lobby
    overlay:
      maintenance: true
      maintenanceMessage: "<yellow>Patching, back in 1h"
```

`EventChoreographer` is a 5-field cron parser with Vixie OR semantics on day-of-month / day-of-week, and IANA timezone support. It evaluates active overlays each scheduler tick and feeds them into `SchedulerDesiredStatePlanner.planGroup` so `minInstances`, `maxInstances`, `scalingMode`, and `maintenance` switch on/off transparently.

State changes emit `CHOREOGRAPHY_OVERLAY_ACTIVATED` / `_DEACTIVATED` on the SSE bus, so the dashboard shows a "peak hours active" pill on the affected group.

Read-only REST surface:

- `GET /api/v1/events` — configured entries
- `GET /api/v1/events/active` — currently firing overlays

Both gated on `events.view`. There is no UI for editing overlays in v1 — they are config-driven. We can add an editor later when operators ask.

### Why it matters

- Operators describe time-bound behaviour declaratively. No cron jobs hitting REST endpoints. No timer microservices.
- The scaler does the same job it always did. Overlays are just additional inputs.

### Where it lives

- Record: `cloud-api/.../api/domain/EventChoreography.java`
- Evaluator: `controller/event_choreography/EventChoreographer.java`
- Wire-in: `controller/scheduler/SchedulerDesiredStatePlanner.java`
- Routes: `controller/rest/route/EventRoutes.java`

## 3. Player Journey Bus

### The problem

"Where has this player been in the last 24 hours?" is a question every operator asks and every cloud system answers poorly. The data is there — connect, transfer, disconnect, crash — but it is scattered across logs, instance state, audit, and proxy plugins. By the time you reconstruct it, the player has logged off.

### The fix

`PlayerJourneyService` keeps a per-player append-only log of every observed transition:

- `PLAYER_CONNECTED` (proxy → first server)
- `PLAYER_TRANSFER` (server → server, or fallback after kick)
- `PLAYER_DISCONNECTED` (graceful or timeout)
- `INSTANCE_CRASHED` (the player was on this instance when it crashed)

Each entry carries timestamp, instance id, group, and reason. Persisted to `player_journey` (Mongo collection) with a per-player index. Surfaced in two ways:

- `PlayerJourneyEvent` on the SSE bus (live, for dashboards subscribing per player or globally).
- `GET /api/v1/players/{uuid}/journey?limit=N&since=ISO` for retrospective queries.

### Capability surface

The bus exposes a typed read-only capability for modules:

```java
CapabilityHandle<PlayerJourneyTracker> handle =
    registry.resolve("prexor.player.journey", PlayerJourneyTracker.class);
PlayerJourneyTracker tracker = handle.get();
List<PlayerJourneyEvent> recent = tracker.getJourney(uuid, 50);
```

The controller registers `PlayerJourneyService` as a built-in provider on the shared `CapabilityRegistry` at startup, so consumer modules resolve `prexor.player.journey` against a real handle on first load. No race window.

### What is *not* in the core

Stage / reason interpretation is **deliberately out of core**. The bus records observed transitions, not derived semantics. If you want to say "this player went from lobby → match → result-screen → lobby," that is a module concern. The reference module (`stats-aggregator`) layers typed lobby/queue/match stages on top of the raw log in its own per-module storage.

This split keeps the bus stable while letting operators evolve their domain model.

### Where it lives

- Service: `controller/player_journey/PlayerJourneyService.java`
- Capability interface: `cloud-api/.../module/capability/PlayerJourneyTracker.java`
- Routes: `controller/rest/route/PlayerRoutes.java` (the `/journey` subresource)
- Event: `cloud-api/.../api/event/player/PlayerJourneyEvent.java`
- Mongo collection: `player_journey`

## How the three primitives combine

A worked example: Friday peak hours, a player connects, gets kicked, ends up in a fallback.

1. **Choreography** activates `peak-hours` overlay → `lobby` minInstances jumps from 1 to 4. Scheduler scales up.
2. Player connects to proxy. Proxy plugin reads `NetworkComposition` cache → routes the player to `lobby`.
3. `PLAYER_CONNECTED` fires → `PlayerJourneyService` appends → SSE emits → dashboard updates.
4. The lobby instance crashes mid-session. Daemon reports crash. Controller emits `INSTANCE_CRASHED` and (because the player was on it) appends an `INSTANCE_CRASHED` journey entry.
5. Proxy plugin fires `KickedFromServerEvent` → walks `[lobby] ++ [lobby-overflow]` (the network's fallback chain) → routes to `lobby-overflow`.
6. `PLAYER_TRANSFER` fires → journey appends → dashboard shows the timeline.
7. 02:00 UTC, `peak-hours` deactivates → scaler returns to baseline → instances reaped after the dynamic-scaling cool-down.

That whole arc is operator-introspectable, replayable from the journey log, and required zero proxy-plugin YAML edits.

## What we explicitly do *not* do

- **Active player session reconstruction across controller restarts.** ClusterState is rebuilt from gRPC on daemon reconnect; transient sessions in flight at the moment of restart may be re-emitted slightly later than ideal. We accept that — the journey is durable; live state is not.
- **Network composition routing on the controller side.** Routing happens in the proxy plugin. The controller is the source of truth for the composition; it does not proxy network packets.
- **Choreography overlays on arbitrary fields.** Only `minInstances`, `maxInstances`, `scalingMode`, and `maintenance` are overlay-able. Adding more is a manageable diff but not v1.
- **Cross-network player movement.** The composition has one network at a time per proxy. Multi-network is a v2 conversation.

## Why these three and not five (or fifty)

We picked the three primitives that make MC operators' lives meaningfully easier and that we can implement without inventing a parallel runtime. They share the same shape:

- They are **declarative** (config / records, not code).
- They are **persisted** in MongoDB.
- They are **observable** via the SSE bus.
- They have a **single source of truth** that flows through the existing controller scheduler / state machinery.

A primitive that doesn't fit those four bullets does not belong in the core. It belongs in a module.
