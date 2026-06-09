---
title: Changelog
description: Release notes for PrexorCloud, following the Keep a Changelog conventions.
---

Every notable change ships here, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Each entry
names the surface it touches — controller, daemon, CLI, dashboard, modules,
plugins — so you can scan for the parts you actually run.

Upgrading? Read [Upgrading](/operations/upgrading/) for the routine v1.x
path. The v1.0 → v1.1 hop is a one-time, one-way migration with its own
[runbook](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/upgrade-v1.0-to-v1.1.md).

## [Unreleased]

Work landed on `main` after v1.1, not yet cut as a release.

### Added

- **OpenTelemetry tracing, opt-in.** Off by default; enable with
  `telemetry.enabled=true` (controller and daemon each carry their own
  block). When disabled, a no-op tracer is installed and the SDK is never
  built, so there is no runtime cost. When enabled, spans export over OTLP
  to any compatible collector and trace context propagates controller →
  daemon over the gRPC command stream. Prometheus stays the metrics surface.
  See ADR 30.
- **Signed module registries.** `modules.registries` takes a list of static,
  signed JSON index URLs. The controller can browse, resolve, download, and
  install a module by id via `prexorctl module search / install / upgrade`.
  The index is discovery only — every install still verifies sha256 and
  signature against the controller's own trust root. See ADR 31.
- **Bedrock support via Geyser.** A `GEYSER` platform target plus a daemon
  that provisions a Geyser sidecar with dynamic remote-proxy resolution. The
  controller tracks each player's edition (Java vs Bedrock) and the dashboard
  shows it.
- **Edition-aware Bedrock routing** in Network Composition. Networks can route
  Bedrock players to dedicated lobby and fallback groups, separate from the
  Java chain.
- **Modded server targets.** Fabric (Loom) and NeoForge (ModDevGradle) server
  mods join the Paper / Spigot / Folia and Velocity / BungeeCord plugin set.

### Changed

- **Design tokens are CI-guarded, not imported.** `design-system/` is the
  canonical token source; each surface mirrors the tokens in its own stack and
  a parity suite fails CI on drift. See ADR 32 and ADR 33.

## [1.1.0] — 2026-05-31

v1.1 replaces the v1.0 MongoDB-based cluster join story with an embedded
Apache Ratis Raft control plane shared across the controllers. Cluster
identity, versioned shared config, members, join tokens, and leader leases
move into a Raft state machine. MongoDB stays the system of record for
business state; Valkey stays for ephemeral fan-out. Moving from v1.0 is a
one-time, one-way migration — follow the
[v1.0 → v1.1 runbook](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/upgrade-v1.0-to-v1.1.md).

### Added

- **Embedded Ratis Raft cluster control plane.** The N controllers form one
  Raft group with a typed state machine holding cluster identity, versioned
  config, members, join tokens, and named leader leases. No external
  coordinator — no etcd, Consul, or ZooKeeper. See ADR 29.
- **New `controller.yml` keys** `raft.host` / `raft.port` (default `9190`) and
  `raft.dir`. The port only needs to be reachable peer-to-peer between
  controllers. New on-disk state lives under `data/raft/` and
  `config/security/cluster/`.
- **Automatic controller join.** A new controller boots into join mode with a
  single-use token (`prexorctl cluster join-token create`), submits a CSR over
  the gRPC `ClusterMembership.RequestJoin` handshake, and receives a cluster-CA
  signed leaf certificate. Cluster-CA-pinned mTLS protects both the Raft
  transport and the gRPC channel.
- **`prexorctl cluster` commands** — `status`, `members`, `config show / patch
  / history`, `join-token create / revoke`, `leave`, and `eject`.
- **Raft-replicated live config.** `PATCH /api/v1/cluster/config` writes a new
  config version through the Raft log; subscribers (CORS allow-list, JWT
  manager, rate limiter, signing policy) react to a typed change event with no
  Redis round-trip.
- **Coarse leader leases** via `ClusterLeaseManager` — scheduler, deployment
  reconciler, DR drill, and audit pruner are leader-elected through the state
  machine.

### Changed

- **Lease + fencing moves from Valkey TTL to Raft.** The active-active
  architecture is unchanged; the coordination substrate is now the Raft state
  machine, which closes the lease-expiry races of the Valkey-TTL path. See
  ADR 4 (mechanism superseded by ADR 29).
- **New cluster permissions** `CLUSTER_VIEW`, `CLUSTER_CONFIG_WRITE`, and
  `CLUSTER_MANAGE` replace the v1.0 `CLUSTER_JOIN` permission. The built-in
  admin role gains them automatically on first v1.1 boot; custom roles bound to
  `CLUSTER_JOIN` must be re-bound.

### Removed

- **The v1.0 Mongo cluster-join path.** The `cluster_meta` collection and
  `GET /api/v1/admin/cluster/join-template` are gone — the join endpoint now
  returns `404`.

## [1.0.0] — 2026-05-05

The first stable release. v1.0 closes out the API and shared-layer overhaul
that reshaped the module SDK, the event bus, and the daemon-side extension
surface, and lands the production-hardening work — signed modules,
lease-scoped HA, the nightly DR drill.

### Added

- **Active-active controller HA** with lease-scoped work and fencing tokens.
  Multiple controllers run against the same MongoDB and Valkey; any healthy
  controller serves REST and gRPC. Failover is exercised by `RecoveryTest` at
  four points (drain, deployment, placement-time, in-flight module mutation).
- **Cosign-signed module bundles**, fail-closed in production by default.
  `modules.signing.required: true` is the production default;
  `<jar>.cosign.bundle` is the sidecar format alongside the legacy `<jar>.sig`.
- **Offline Rekor SET enforcement** via
  `modules.signing.rekor.policy=REQUIRE_SET`. The controller verifies the
  bundle's `SignedEntryTimestamp` against a locally bundled Rekor public key —
  no network access at install time.
- **Daemon-side modules.** A new `DaemonModule` interface in `cloud-api` with
  instance-lifecycle hooks, manifest-driven `hosts: [controller, daemon]`, a
  gRPC `ModuleDistributor` that fans installs to every connected daemon, and a
  controller → daemon event forwarder with subscribe-registration semantics
  (no event firehose).
- **Network Composition**, end to end. Controller persistence and REST surface,
  proxy-plugin cache, dashboard editor, and proxy-plugin routing ship together.
  Define lobby and fallback chains once; the proxy plugin walks them on every
  connect and every kick.
- **First-party reference module `stats-aggregator`** under
  `java/cloud-modules/stats-aggregator/`, demonstrating REST routes, capability
  registration, MongoDB-backed storage, workload extensions, and a frontend
  manifest in one example.
- **Module REST dispatcher.** A `ModuleRouteRegistry` plus a wildcard Javalin
  handler per HTTP method. Modules register routes via
  `PlatformModule#onRegisterRoutes`; they appear at
  `/api/v1/modules/<moduleId>/<sub>`.
- **Capability registry events** (`CapabilityRegisteredEvent`,
  `CapabilityUnregisteredEvent`, `CapabilityProviderChangedEvent`) over the SSE
  bus and a dedicated
  `/api/v1/modules/platform/capabilities/stream`. The dashboard's
  `useCapability` composable seeds from the registry and updates as providers
  come and go.
- **`prexorctl plugin new`** scaffolds standalone `@CloudPlugin` jars,
  symmetric to `prexorctl module new`. Targets Paper / Spigot / Folia /
  Velocity / BungeeCord.
- **Email-based password reset** (off by default,
  `security.passwordReset.enabled`). Single-use 30-minute tokens; STARTTLS /
  implicit-TLS / AUTH via `jakarta.mail`; a log-only `LogMailer` for dry runs.
- **Performance baseline harness** with a nightly CI drift comparator —
  controller cold start, coordination-store latency, SSE latency, and scheduler
  tick at 1k groups; warns at >25% drift, never fails. See
  [Performance benchmarks](/benchmarks/) and ADR 23.
- **Nightly disaster-recovery drill.** The `dr-drill` job boots an in-process
  controller against a real Mongo and Valkey, takes a backup, drops the
  database, restores from the manifest, and asserts the restored state matches
  the seed.
- **Cosign-signed release pipeline.** `release.yml` ships cosign-signed
  `prexorctl` binaries, `release-jars.yml` signs the controller and daemon
  jars, and `release-images.yml` ships cosign-signed multi-arch GHCR images on
  every `v*` tag.
- **`prexorctl setup`** with native and Docker Compose install modes, including
  cosign verification of the downloaded controller and daemon jars.
- **OpenAPI 3.0 spec** at `docs/openapi.json`, auto-generated from `@OpenApi`
  annotations on the controller's route handlers. It drives the rendered REST
  reference and the Scalar `/playground`.

### Changed

- **`PlatformModuleContext` is now the `ModuleContext` interface**, adding
  `events()`, `logger()`, `scheduler()`, `httpClient()`, `json()`, and
  `host()`. The legacy record was deleted with no compat shim; every
  first-party caller migrated in the same change.
- **One `EventBus` contract.** The controller-internal `EventBus` now
  implements `api.event.EventBus`, so modules subscribe to controller events
  with the same interface plugins already use. Subscriptions return
  `EventSubscription` handles for explicit teardown.
- **Module manifest reshape.** `backend.entrypoint` becomes
  `backend.controller.entrypoint` / `backend.daemon.entrypoint`, with a
  top-level `hosts: [controller, daemon]` field. Legacy manifests still parse
  (mapped to `controller`).
- **Player Journey and Webhook Alerts are now first-party modules**, not
  controller built-ins. The webhook change is breaking: operators with the old
  YAML `webhooks:` block must migrate entries into the
  `cloud-module-webhook-alerts` module's storage.
- **The module signature verifier moved to `cloud-security/signing`** so the
  daemon can reuse it without importing `cloud-controller`.
- **`cloud-common` runs on Java 21** (was Java 25 preview) so plugin-side
  modules can consume it without unlocking preview features.
- **Documentation rewritten in English** with an architectural decisions
  register; the pre-v1 German `CLOUD_GUIDE.md` was removed. See ADR 24.

### Removed

- **OIDC / SAML / SSO support.** Operator auth is now username + password + JWT,
  with optional email-based password reset. See ADR 8.
- **The bundled Grafana dashboard pack.** `/metrics` exposition stays stable;
  build your own panels from [Monitoring and metrics](/operations/monitoring/).
  See ADR 10.

### Security

- Module bundles are signed with Cosign and verified fail-closed in production.
  Upgrading from a pre-v1 build with unsigned modules requires re-signing and
  re-installing before the production-profile controller will load them.
- mTLS is the only daemon authentication path. Per-node certificate revocation
  via `POST /api/v1/nodes/{id}/revoke-cert` is enforced immediately by the mTLS
  interceptor.
- JWT revocation is shared across controllers via Valkey in production profile.
  In development profile it stays in-memory and is lost on restart.

[Unreleased]: https://github.com/prexorjustin/prexorcloud/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/prexorjustin/prexorcloud/releases/tag/v1.1.0
[1.0.0]: https://github.com/prexorjustin/prexorcloud/releases/tag/v1.0.0
</content>
</invoke>
