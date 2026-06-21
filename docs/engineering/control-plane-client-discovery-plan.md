# Control-plane client discovery — implementation plan

**Why this file exists.** Two design questions came out of the HA-failover work:

1. **Discovery duplication.** The daemon follows the leader via a gRPC redirect (the controller *pushes* `leader_grpc_addr` on the HandshakeAck), but the in-server plugin *independently* discovers the leader via a seed list + 307-follow. Two mechanisms for one problem ("where is the leader?").
2. **Static IP seed lists.** Each instance is injected with `CLOUD_CONTROLLER_SEEDS=ip,ip,ip` — every controller's REST IP, enumerated. That is the dated part: raw IPs are ephemeral and the list must be re-pushed when membership changes.

This is the plan to modernise both. The guiding principles:

- **Seeds are for cold-start only.** The live membership must be learned dynamically from the cluster, not from static config. The daemon already does this (HandshakeAck field 8 self-sync); we extend the pattern and shrink the static part to a single stable name.
- **One source of truth per node.** The daemon authoritatively knows the leader. The plugin should not re-derive it; it should go through the daemon (Consul-style local-agent gateway).
- **Keep what's correct.** The daemon's gRPC redirect-to-leader stays (its bidirectional command stream must live *on* the leader). Only the plugin's REST path is reworked.

> **Module note.** The active plugin modules are the short-named dirs — `:cloud-plugins:internal` (`plugin/common/*`), `:cloud-plugins:server:shared`, `:cloud-plugins:proxy:shared`. The `java/cloud-plugins/cloud-plugins-*` tree is the dead v1.0 orphan (not in the build) — do not edit it.

## Review corrections (verified against code — supersede the optimistic spots below)

1. **The gateway *absorbs* the 307; it doesn't make it disappear.** During the failover window `controllerApiUrl()` still points at the old leader (now a follower → 307/503), and at cold start it is `""`. The gateway must hold-and-retry against `controllerApiUrl()` until the daemon's *own* gRPC redirect settles — it must **not** follow the 307 itself (that re-introduces dual discovery). The win is consolidation: the window is solved **once per node**, not once per plugin, and the bearer-strip-on-307 class can't reach the plugin. The gateway can even be smarter than a plugin — it knows from the channel state whether a redirect is in flight and can *hold* the request rather than bounce it.
2. **Gateway listener = Netty (decided), not `com.sun.net.httpserver`.** `com.sun` has no real backpressure, blocking I/O, a fixed pool — wrong for long-lived SSE pass-through. The daemon already has Netty via grpc-netty; use it for the listener from 2a so 2a/2b share one stack. Prefer a **Unix Domain Socket** over loopback-TCP (Netty supports it: filesystem-permission-scoped, no port).
3. **2a–2c is robustness + security + topology-blind plugins, NOT scaling.** Per-plugin SSE pass-through keeps the controller's connection count constant (just moves the origin). The throughput win is **2d only** (coalesced node-scoped SSE + shared cache). Set expectations accordingly.
4. **Plan 2 makes the daemon a hard dependency for the plugin's whole control-plane path.** The nasty case is *gateway alive but no leader reachable*. Mitigation (chosen): a **circuit-breaker** in the gateway — no leader for N s → fast `503` so the plugin's own retry/backoff engages instead of hanging. Deliberately **no** plugin-side direct fallback (that would break the single-egress invariant).
5. **1a DNS — verified dial path + the JVM cache trap.** `DaemonGrpcClient` dials via `NettyChannelBuilder.forAddress(new InetSocketAddress(host, port))` (≈L212–217) — a deliberate *direct-address* path that bypasses gRPC's name resolver (keeps the mTLS authority exactly `host:port` and owns rotation/redirect). Consequences: (a) `new InetSocketAddress(name,port)` resolves **eagerly to one IP via `InetAddress`**, subject to `networkaddress.cache.ttl`; (b) it takes **one** A record, not all. → **Do not switch to a `dns:///` gRPC target** (it would hand rotation to gRPC's LB and fight the redirect). Instead **expand the DNS name into multiple `ControllerEndpoint` candidates ourselves** at candidate-build time (`PrexorDaemon` ≈L91), and **SRV-first** (JNDI bypasses the `InetAddress` cache *and* returns the port → drop the separate `grpcPort` for the SRV path); A/AAAA only as fallback with an explicitly short `networkaddress.cache.ttl` set in `main()`. Multi-candidate expansion + the existing self-sync handle the "stale IP across reconnects" case.

**Sequencing change:** drop **1b** — its only purpose (a name for the plugin) is deleted by 2c. Do **1a + 1c** only, unless Plan 2 slips.

---

## Current state (verified)

**Plugin (`:cloud-plugins:internal`, `cloud-api`)**
- `PluginEnv.controllerUrl()` (`cloud-api/.../client/env/PluginEnv.java`): builds `http://CLOUD_CONTROLLER_HOST:CLOUD_CONTROLLER_PORT` and appends `CLOUD_CONTROLLER_SEEDS` (the comma list).
- `BaseControllerClient` (`internal/.../plugin/common/BaseControllerClient.java`): REST base; `apiPrefix()` = `/api/plugin` or `/api/proxy`; bearer in an `AtomicReference`; single-flight refresh on 401 via `apiPrefix()+"/auth/refresh"`; **seed-list rotation + manual 307-follow re-attaching the bearer** (shipped `bf8d2e7`).
- `CloudStateStreamClient` / `CloudStateCache`: SSE via `POST apiPrefix()+"/events/ticket"` → ticket → `GET /api/v1/events/stream?ticket=…&lastSequence=N` (note: the stream is under `/api/v1`, not `/api/plugin`). Cache polls `fetchInstances/groups/networks` only when the stream is inactive.
- Constructed in `server/shared/.../bukkit/AbstractCloudPlugin`, `proxy/shared/.../AbstractProxyCloudPlugin`, neoforge, fabric — all via `new …ControllerClient(PluginEnv.controllerUrl(), PluginEnv.pluginToken())`.

**Daemon (`cloud-daemon`)**
- `ControllerConnectionConfig` (`daemon/config/…`): **already** a `host` + `grpcPort` **plus an `endpoints: List<String>`** with `resolvedEndpoints()` (dedup, drop loopback). So a multi-controller seed list already exists in config.
- `DaemonGrpcClient`: gRPC only (9090). Stores `controllerApiUrl`/`controllerApiPort` from HandshakeAck field 3. Self-heals dial candidates from HandshakeAck field 8 (`mergeAdvertisedControllers`). Follows `leader_grpc_addr` (field 6).
- `HealthServer` (`daemon/health/…`): a minimal `com.sun.net.httpserver` listener (`/health`, `/ready`) — proof the daemon can host HTTP; **today not a gateway**.

**Controller (`cloud-controller`)**
- `PluginRoutes` (`rest/route/PluginRoutes.java`): `/api/plugin/{auth/refresh, events/ticket, ready, player-join, player-leave, events, instances, groups, players, transfer, transfer-to-group, metrics, message/send}`; proxy mirror under `/api/proxy`.
- SSE at `/api/v1/events/stream` (`rest/sse/SseEventStreamer.java`), ticket-authenticated.
- `LeaderRedirectMiddleware`: followers return **307** (before auth; exempts `/health`,`/ready`,`/metrics`) or **503** when no leader is known.
- `PrexorCloudBootstrap.resolveControllerGrpcAddresses()` / `resolveControllerRestSeedUrls()` (≈L1374–1414): member-list → addresses; the REST one feeds `CLOUD_CONTROLLER_SEEDS`.
- HandshakeAck proto (`cloud-protocol/.../daemon_service.proto`): `controller_api_port=3`, `leader_grpc_addr=6`, `epoch=7`, `controller_grpc_addrs=8`.

---

## Plan 1 — DNS/SRV bootstrap (small, do first)

**Goal:** replace the enumerated IP list with **one stable name** for cold-start; the cluster's self-sync remains the live truth. Mostly daemon-side + ops.

### 1a. Daemon: DNS-aware bootstrap resolution
- In `ControllerConnectionConfig.resolvedEndpoints()` (or a new `ControllerEndpointResolver`), when an endpoint's host is **not an IP literal**, resolve it:
  - **A/AAAA:** `InetAddress.getAllByName(host)` → expand to all records (no new dependency).
  - **SRV (preferred):** `_prexor-controller._tcp.<domain>` via JNDI (`javax.naming.directory.InitialDirContext`, `"SRV"` lookup) → host+port+priority+weight, no new dependency. Add a `controller.dnsSrv` config key.
- Order: SRV (if configured) → A/AAAA of `host` → literal `endpoints[]` (back-compat). Cache the resolution briefly (respect TTL-ish; re-resolve on full reconnect failure).
- The daemon dials any resolved record; **after connect, HandshakeAck field 8 supplies the live members** (already implemented) — so DNS is strictly the cold-start hint.
- Files: `daemon/config/ControllerConnectionConfig.java`, `ControllerEndpoint.java`, `daemon/grpc/DaemonGrpcClient.java` (candidate seeding), setup/`InteractiveSetup`.
- **Effort:** ~0.5–1 day. **Risk:** low (additive; literal IPs still work).

### 1b. (Interim) Controller: advertise a name, not an IP list, to the plugin
- Only needed *until* Plan 2 lands (Plan 2 removes plugin seeds entirely).
- Add config `controller.advertisedRestName` (e.g. `controllers.prexor.internal:8080`). When set, `resolveControllerRestSeedUrls()` emits the **single name** instead of enumerated member `restAddr`s.
- File: `PrexorCloudBootstrap.java` (the resolver added for `CLOUD_CONTROLLER_SEEDS`).
- **Effort:** ~0.5 day. **Risk:** low.

### 1c. Ops: provision DNS
- Internal zone record `controllers.prexor.internal` → A records for all controllers (or an SRV record). On the current Hetzner fleet: a private-DNS entry or, minimally, a managed `/etc/hosts`/dnsmasq on each node. Health-aware DNS (only healthy controllers) is a later upgrade (VIP/LB or a health-checked resolver).
- **Effort:** ~0.5 day + runbook.

**Plan 1 outcome:** adding/removing a controller is a **DNS change**, no client re-deploy. One name replaces the IP list. The self-sync already handles live membership.

---

## Plan 2 — Plugin via local-daemon gateway (bigger, the real modernisation)

**Goal:** the plugin talks only to `127.0.0.1` (the local daemon); the daemon forwards to its current leader. The plugin becomes topology-oblivious — no seeds, no 307 handling, no plugin-side failover window. The plugin code barely changes (it already builds `controllerUrl` from `CLOUD_CONTROLLER_HOST:PORT`).

### 2a. Daemon: loopback REST gateway (request/response)
- Add a loopback HTTP listener (bind `127.0.0.1:<gatewayPort>`). Reuse the `HealthServer` `com.sun.net.httpserver` stack, or pull in a small embedded server (Javalin/Netty) if SSE streaming in 2b proves awkward with `com.sun`.
- Forward `/api/plugin/*`, `/api/proxy/*`, `/auth/refresh` to the **leader's REST** = `DaemonGrpcClient.controllerApiUrl()` (already tracked, already updated on redirect). **Pass the plugin's `Authorization` bearer through verbatim** — the controller still validates the per-instance token (least-privilege preserved).
- Auto-retarget: because the forward target is `controllerApiUrl()`, a leader change (which the daemon already follows via gRPC redirect) transparently moves forwarded requests to the new leader. The plugin sees nothing.
- **No 307 to handle** — the daemon forwards straight to the leader, never a follower.
- Files: new `daemon/gateway/ControllerGatewayServer.java`, wired in daemon bootstrap; reads `controllerApiUrl()` from `DaemonGrpcClient`.
- **Effort:** ~2–3 days. **Risk:** medium (new network surface; keep it loopback-only).

### 2b. Daemon: SSE proxy (per-plugin pass-through first)
- Proxy `POST /api/plugin/events/ticket` then stream `GET /api/v1/events/stream` through the gateway: open the upstream stream to the leader, copy bytes to the local client, flush per event. `com.sun.net.httpserver` can stream with manual flushing; validate keep-alive/backpressure or switch to Netty.
- On leader change mid-stream, the daemon re-opens upstream and resumes from `lastSequence` (the protocol already supports resume) — invisible to the plugin.
- **Effort:** ~2–3 days. **Risk:** medium (streaming correctness).

### 2c. Spawn env: point plugins at the gateway
- Behind a flag (`daemon.gateway.enabled`): when on, `ServerProcess.start()` injects `CLOUD_CONTROLLER_HOST=127.0.0.1` + `CLOUD_CONTROLLER_PORT=<gatewayPort>` and **omits `CLOUD_CONTROLLER_URL`/`CLOUD_CONTROLLER_SEEDS`**. Plugin code unchanged — `PluginEnv.controllerUrl()` already builds from HOST:PORT; the `bf8d2e7` seed/307 logic becomes an unused fallback (kept for non-gateway mode).
- File: `daemon/process/ServerProcess.java` (the existing `CLOUD_CONTROLLER_*` injection block).
- **Effort:** ~0.5 day. **Risk:** low; per-node rollout via the flag.

### 2d. (Later, scaling) coalesced node-scoped SSE + shared cache
- The win at density: the daemon holds **one** upstream SSE per node and fans out filtered deltas to all local plugins; one shared cache serves `instances/groups/networks` to every local plugin. Turns N-per-node controller load into 1. Requires a node-scoped subscription on the controller + per-instance demux in the daemon.
- **Effort:** ~1 week. **Risk:** higher; defer until per-node instance density justifies it.

**Plan 2 outcome:** plugins are topology-oblivious (localhost only); the daemon is the single leader-aware egress on the node; the bearer-stripped-on-307 and adopt-gap-401 bug classes can't reach the plugin; loopback-only plugin traffic is a security win; 2d unlocks sublinear control-plane load.

---

## Sequencing & how the two interact

```
Plan 1a (daemon DNS bootstrap)   ── foundation: daemon finds a controller via one name
        │
        ├─ Plan 1b/1c (interim plugin name + DNS ops)   ── only until Plan 2 lands
        │
        └─ Plan 2a→2b→2c (plugin → local-daemon gateway) ── removes plugin discovery entirely
                   │
                   └─ Plan 2d (coalesced SSE/cache)       ── scaling, later
```

- **Do Plan 1a first** — small, unblocks IP churn, no client redeploy on membership change, and it's the daemon's cold-start regardless of Plan 2.
- **Plan 2 subsumes the plugin half of the seed problem** — once plugins go through the daemon, `CLOUD_CONTROLLER_SEEDS` and the plugin's seed-rotation/307 logic are no longer on the hot path (keep as a fallback for gateway-disabled mode).
- **Keep the daemon's gRPC redirect-to-leader** unchanged — correct for its stateful bidirectional stream.

## What to keep as a safety net
- `bf8d2e7` plugin seed-rotation + 307-follow: retained as the gateway-disabled fallback.
- The convergence-window token grace (`52f617c`): still correct and belt-and-suspenders even with the gateway.
- Literal `endpoints[]` in `ControllerConnectionConfig`: retained behind DNS for air-gapped/no-DNS setups.

## Rough total
- Plan 1: ~1.5–2 days + ops.
- Plan 2 (2a–2c): ~5–7 days; 2d another ~1 week when needed.
