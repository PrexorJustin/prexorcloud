---
title: Changelog
description: Release notes for PrexorCloud, following the Keep a Changelog conventions.
---

All notable changes to PrexorCloud are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Each
entry references the surfaces it touches — controller, daemon, CLI,
dashboard, modules, plugins — so operators can scan for the parts they
actually run.

## [Unreleased]

Tracking work after v1.0. No entries yet.

## [1.0.0] — 2026-05-05

The first stable release. v1.0 closes out the API + shared-layer
overhaul (Layers 1 through 9) that re-shaped the module SDK, the
event bus, and the daemon-side extension surface. It also lands the
production-hardening work — signed modules, lease-scoped HA, the
nightly DR drill — that we wanted in the box before calling anything
"v1."

### Added

- **Active-active controller HA** with lease-scoped work and fencing
  tokens. Multiple controllers run against the same MongoDB and
  Valkey; any healthy controller can serve REST and gRPC. Failover
  goes through `RecoveryTest` at four exercise points (drain,
  deployment, placement-time, in-flight module mutation).
- **Cosign-signed module bundles**, fail-closed in production by
  default. `modules.signing.required: true` is the production default;
  `<jar>.cosign.bundle` is the new sidecar format alongside the legacy
  `<jar>.sig`.
- **Offline Rekor SET enforcement** via
  `modules.signing.rekor.policy=REQUIRE_SET`. The controller verifies
  the bundle's `SignedEntryTimestamp` against a locally-bundled Rekor
  public key — no network access required at install time.
- **Daemon-side modules** (Layer 7). New `DaemonModule` interface in
  `cloud-api` with instance-lifecycle hooks
  (`onInstanceStarting / Started / Stopping / Stopped`),
  manifest-driven `hosts: [controller, daemon]`, gRPC-based
  `ModuleDistributor` that fans installs to every connected daemon,
  and a controller→daemon `DaemonEventForwarder` with
  subscribe-registration semantics (no event firehose).
- **Network Composition** end-to-end. Controller-side persistence and
  REST surface, proxy-plugin cache, dashboard editor, and
  proxy-plugin routing all ship together. Operators define lobby and
  fallback chains once, and the proxy plugin walks them on every
  player connect and on every kick.
- **First-party reference module: `stats-aggregator`** under
  `java/cloud-modules/stats-aggregator/`. Demonstrates
  REST routes, capability registration, MongoDB-backed storage,
  workload extensions, and a frontend manifest in one well-documented
  example.
- **Module REST dispatcher.** A `ModuleRouteRegistry` plus a wildcard
  Javalin handler per HTTP method. Modules register routes via
  `PlatformModule#onRegisterRoutes` and they appear at
  `/api/v1/modules/<moduleId>/<sub>` atomically.
- **Capability registry events** (Layer 8).
  `CapabilityRegisteredEvent`, `CapabilityUnregisteredEvent`, and
  `CapabilityProviderChangedEvent` flow through the SSE bus and the
  new dedicated `/api/v1/modules/platform/capabilities/stream`. The
  dashboard's `useCapability` composable seeds from the registry and
  reactively updates when providers come and go.
- **`prexorctl plugin new`** for scaffolding standalone `@CloudPlugin`
  jars (Path A) symmetrically to `prexorctl module new`. Supports
  Paper / Spigot / Folia / Velocity / BungeeCord targets.
- **Email-based password reset** (off by default, off-switch is
  `security.passwordReset.enabled`). Single-use 30-minute tokens,
  STARTTLS / implicit-TLS / AUTH support via `jakarta.mail`,
  log-only `LogMailer` for dry runs.
- **Performance baseline harness** with a CI drift comparator.
  Nightly run measures controller cold start, coordination-store
  latency, SSE latency, and scheduler tick at 1k groups; warns at
  >25 % drift (does not fail — see ADR 23).
- **Nightly disaster-recovery drill.** The `dr-drill` job in
  `.github/workflows/nightly.yml` boots an in-process controller
  against a real Mongo and Valkey, takes a backup, drops the
  database, restores from the manifest, and asserts the restored
  state matches the seed.
- **Cosign-signed release pipeline.** `release.yml` ships
  cosign-signed `prexorctl` binaries; `release-images.yml` ships
  cosign-signed multi-arch GHCR controller and daemon images on every
  `v*` tag.
- **Cosign install integration test.**
  `CosignSignedModuleInstallTest` covers the negative path —
  signature-mismatch installs return `422 SIGNATURE_VERIFICATION_FAILED`.
- **Cross-cutting `cloud-common` infrastructure.** `HttpClients.defaults()` /
  `defaultClient()`, `ObjectMappers.standard() / strict() / yaml()`, and
  `Backoff.withRetries(...)` are the shared building blocks every
  module and plugin component now uses.
- **Gradle platform `cloud-platform`.** A `java-platform` subproject
  pinning Jackson, SLF4J, Logback, Paper / Velocity / Bungee /
  Adventure versions in one place. Every consumer pulls
  `implementation(platform(project(":cloud-platform")))`.
- **`AbstractPluginContext` shared base** for plugin platforms.
  `BukkitPluginContext` and `VelocityPluginContext` shrunk from
  ~100-line constructors to ~10.
- **`prexorctl setup`** with native and Compose install modes,
  including cosign verification of the downloaded controller / daemon
  jars.
- **OpenAPI 3.0 spec** at `docs/openapi.json`. Auto-generated from
  `@OpenApi` annotations on the controller's route handlers via the
  `javalin-openapi-plugin` annotation processor — 155 paths, 179
  operations. Drives the rendered REST reference and the Scalar
  `/playground`.

### Changed

- **`PlatformModuleContext` is now `ModuleContext`** (Layer 3+4). The
  context interface adds `events()`, `logger()`, `scheduler()`,
  `httpClient()`, `json()`, and `host()`. The legacy `PlatformModuleContext`
  record was deleted with no compat shim — every first-party caller
  was migrated in the same change.
- **One `EventBus` contract.** The controller-internal `EventBus`
  now implements `api.event.EventBus`, which means modules can subscribe
  to controller events with the same interface plugins already used.
  Subscriptions return `EventSubscription` handles for explicit teardown.
- **Module manifest reshape.** `backend.entrypoint` is now
  `backend.controller.entrypoint` and `backend.daemon.entrypoint`,
  with a top-level `hosts: [controller, daemon]` field. Legacy
  manifests still parse (mapped to `controller`).
- **Player Journey is now a first-party module**, not a controller
  built-in. The new `cloud-module-player-journey` module owns the
  `prexor.player.journey@1.0.0` capability and the underlying Mongo
  storage.
- **Webhook alerts is now a first-party module.** The new
  `cloud-module-webhook-alerts` module owns webhook configuration in
  its own Mongo collection. Operators with the old YAML `webhooks:`
  block need to migrate their entries to the new module's storage —
  this is a breaking change.
- **Platform module signature verifier moved to `cloud-security/signing`**
  so the daemon can reuse it without importing `cloud-controller`.
  Verifier input is now a small `VerificationInput(sourceJar, moduleId,
  moduleVersion, sha256)` record.
- **`cloud-common` runs on Java 21** (was Java 25 preview) so the
  plugin-side modules can consume it without unlocking preview
  features.
- **Documentation rewritten in English** with an explicit
  architectural decisions register.
  The pre-v1 German `CLOUD_GUIDE.md` was removed in favour of one
  source of truth — see ADR 24.

### Removed

- **OIDC / SAML / SSO support.** The OIDC code path was removed in
  the v1.0 cleanup. Operator auth is now strictly username +
  password + JWT, with optional email-based password reset. See
  ADR 8.
- **Bundled Grafana dashboard pack.** `/metrics` exposition stays
  stable; the dashboard pack and provisioning manifests were
  dropped. See ADR 10 and
  [observability](/operations/monitoring/) for PromQL examples
  operators can drop into their own Grafana boards.
- **`PlatformModuleContext` record** — replaced by the
  `ModuleContext` interface. No deprecation window; first-party
  callers migrated in the same change.
- **Controller-side `PlayerJourneyService`** — extracted into the
  `cloud-module-player-journey` module.
- **Controller-side `WebhookAlertService`** and `webhooks:` config
  block — extracted into the `cloud-module-webhook-alerts` module.
- **`@controller` sentinel module id** — no longer used for
  built-in capability registration.

### Fixed

- **Module classloader pinning** on capability rebind. The dynamic
  `CapabilityHandle` proxy cache used to retain `Class<?>` keys from
  unloaded modules; the registry now nulls the delegate and clears
  the cache on deactivation, so the classloader collects.
- **Frontend cache pinning** on module unload. `ModuleFrontendManager`
  now deletes the on-disk asset directory and removes the cached
  `LoadedFrontend` so module unload no longer leaks dashboard pages.
- **EventBus retention on daemon disconnect.**
  `DaemonServiceImpl.cleanup()` calls `forwarder.onDisconnect(nodeId)`
  so the controller's event bus does not retain references to dead
  session observers.

### Security

- Module bundles are signed with Cosign and verified fail-closed in
  production (see *Added*). Operators who upgrade from a pre-v1
  development build with unsigned modules need to re-sign and
  re-install before the controller will load them in production
  profile.
- mTLS is now the only daemon authentication path. Per-node
  certificate revocation via
  `POST /api/v1/nodes/{id}/revoke-cert` is enforced by the mTLS
  interceptor immediately.
- JWT revocation is shared across controllers via Valkey in production
  profile. In development profile it remains in-memory and is lost on
  restart.

[Unreleased]: https://github.com/prexorjustin/prexorcloud/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/prexorjustin/prexorcloud/releases/tag/v1.0.0
