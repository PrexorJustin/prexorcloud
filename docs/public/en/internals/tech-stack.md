---
title: Tech Stack
description: Languages, frameworks, runtimes, and infrastructure pieces that make up PrexorCloud — what runs where and why.
---

A short, honest list of every load-bearing technology in PrexorCloud,
the role it plays, and the reasoning behind picking it. We do not
pile on dependencies — every entry below is something we'd argue for
on its own.

## What you'll learn

- The languages and runtimes for each process
- The frameworks and libraries that show up in the build files
- Operational dependencies: MongoDB, Valkey, cosign, Prometheus
- Why we picked each one over the obvious alternative

## Process matrix

| Process | Language | Runtime | Framework / library |
|---|---|---|---|
| Controller | Java 25 | OpenJDK 25 | Javalin 7 (HTTP), grpc-java (gRPC), Mongo Java driver, Lettuce (Valkey/Redis), Logback + SLF4J, Jackson, Prometheus simpleclient |
| Daemon | Java 21 | OpenJDK 21 | grpc-java (client), `ProcessBuilder` (no framework), Logback + SLF4J |
| Dashboard | TypeScript | Node 22 + browser | Nuxt 4, Vue 3, OpenAPI-generated SDK, Pinia stores, ESLint + Vitest + Playwright |
| CLI (`prexorctl`) | Go 1.22+ | static binary | Cobra (commands), Charmbracelet libraries (TUI bits), goreleaser (cross-build + sign) |
| Server plugins (Paper / Spigot / Folia) | Java 21 | host server JVM | Bukkit / Paper API |
| Proxy plugins (Velocity / Bungee) | Java 21 | host proxy JVM | Velocity / Bungee API |

The controller targets Java 25 because the controller-side code base
opts in to recent JVM ergonomics (records, pattern matching, virtual
threads). `cloud-common` and the daemon target Java 21 so plugin-side
modules running on long-tail server JDKs can consume the shared API
jar.

## External services

| Service | Purpose | Why this one |
|---|---|---|
| MongoDB 6.0+ | Durable platform state. Self-hosted; PrexorCloud does not embed it. | Document shape maps onto deeply-nested per-feature variable data (composition plans, module manifests, workflow intent). Switching to Postgres would mean either a tableful-of-JSON anti-pattern or a rigid schema that fights every new feature. |
| Valkey 7.2+ (Redis 7+) | Coordination. Required in production profile. | TTL semantics, lease primitives, pub/sub. Mongo's TTL index works but is much heavier per contention. Valkey is BSD-3; Redis BSL. We default to Valkey for the licensing question, but the controller speaks the Redis protocol so operators can keep using Redis if they already do. |
| Prometheus | Metric scrape target | Stable exposition format, ubiquitous, low operational overhead. |
| Cosign + Fulcio + Rekor | Release and module signing | No long-lived signing keys. Closest thing to a standard for signing artefacts in 2026. |

## What we deliberately don't ship

| Not in v1 | Why |
|---|---|
| Spring / Guice / Dagger | DI frameworks hide the dependency graph. Hand-wired `PrexorCloudBootstrap` keeps the ~80-component graph readable in one file. |
| OpenTelemetry | Distributed tracing earns its keep with dozens of services. PrexorCloud is two services with one well-defined gRPC contract. Prometheus + structured logs cover the questions operators ask. |
| Helm / Kubernetes operator | Compose-first install fits the audience (MC operator teams). K8s pods around per-MC-instance JVMs is awkward and slow. |
| Grafana dashboard pack | Maintaining dashboards as code is a real burden. Metrics are stable and well-named; build the panels you need. |
| OIDC / SAML / SCIM / MFA | Single-tenant local-user + JWT only. The audience is 1–10-operator teams; SSO complexity outweighs benefit at that scale. |
| WASM modules | Modules ship as JVM jars. Operators already have JVM expertise, and the threat model is signed-bundle integrity, not hostile-module sandboxing. |
| GitOps reconciliation loop | Imperative templates and groups via REST / CLI / dashboard. GitOps helps at one scale and hurts at another. |

Each of these is an explicit architectural decision, not an accident
of timeline.

## Build and CI

| Tool | Purpose |
|---|---|
| Gradle (Kotlin DSL) | Multi-project Java build. Toolchains pin Java 25 / 21 per module. |
| pnpm | Dashboard + module-SDK + auxiliary Node projects. |
| Go modules | CLI build. |
| GitHub Actions | CI for tests, builds, releases. `release.yml` ships cosign-signed prexorctl binaries; `release-images.yml` ships cosign-signed multi-arch GHCR images on `v*`. |
| goreleaser | CLI cross-compile + sign + publish. |
| Cosign | Keyless signing of release artefacts and operator-side verification. |
| Trivy | Vulnerability scan against built images. |
| Syft | CycloneDX SBOM per image. |
| Sigstore Rekor | Transparency log; offline SET enforcement available via `modules.signing.rekor.policy=REQUIRE_SET`. |

## Test strategy

| Tier | Where | What |
|---|---|---|
| Unit | per-module `src/test/java` | Fast, no external services, mock at boundaries only. |
| Integration | `cloud-test-harness` | Boots a real controller against real Mongo + Valkey; covers REST, gRPC, scheduler, recovery, modules. |
| Recovery harness | `cloud-test-harness/RecoveryTest` | Standby promotion drills (drain, deployment, placement-time, in-flight module mutation). |
| DR drill | `:cloud-test-harness:drDrill` (`@Tag("dr")`) | Backup → wipe → restore → state match. Runs nightly via `.github/workflows/nightly.yml :: dr-drill`. |
| Perf baselines | `:cloud-test-harness:perfBaselines` (`@Tag("perf")`) | Cold start, coordination latency, SSE latency, scheduler tick at 1k groups. Runs nightly; reports drift > 25%. |
| Dashboard | Vitest + Playwright | Component + e2e against a mocked controller and a real dev controller. |
| CLI | Go test | Includes `goreleaser-check` to keep release config from drifting. |

## Runtime requirements (bare metal)

| Component | Minimum | Notes |
|---|---|---|
| Controller host | Linux x86_64 (Debian/Ubuntu, RHEL/Fedora, openSUSE, Arch) | macOS / Windows hosts are not supported as controllers. |
| Daemon host | Linux x86_64 | Same. |
| Java | OpenJDK 21+ for daemon, 25+ for controller | `prexorctl setup` installs via the distro package manager when missing. |
| MongoDB | 6.0+ | Self-hosted; replica set recommended for HA. |
| Valkey / Redis | Valkey 7.2+ / Redis 7+ | Required in production profile. |

## Library highlights

A non-exhaustive list of dependencies that shape behaviour:

- **Javalin 7** — small Kotlin/Java HTTP framework with first-class
  WebSocket / SSE. We disable WS (see ADR 11)
  and lean on SSE.
- **grpc-java + Netty** — bidirectional streaming for daemon
  connections, with `ReloadableServerSslContext` for hot CA rotation.
- **Lettuce** — non-blocking Redis-protocol client. We use synchronous
  primitives at the call sites; Lettuce's connection multiplexing
  keeps that cheap.
- **Mongo Java driver** — synchronous flavour. Connection pool tuned
  via the URI; we don't wrap it.
- **Logback + SLF4J + JsonLogEncoder** — `HUMAN` and `JSON` log
  formats; `RequestIdMiddleware` plumbs `requestId` through MDC.
- **Jackson** — only Jackson, no Gson or alternatives. Records map
  cleanly without runtime type-magic.
- **Prometheus simpleclient** — metric registration site is
  `MetricsCollector`. Module metrics are hand-rendered exposition,
  not via the client library, to keep module dependencies minimal
  (ADR 16).
- **bcrypt** — password hashing.
- **Bouncy Castle (where needed)** — Cosign / Rekor signature
  verification, mTLS CA operations.
- **Nuxt 4 / Vue 3 / Pinia** — dashboard. SDK is generated from the
  controller's OpenAPI spec.
- **Cobra** — CLI command surface. Output formatters live in
  `cli/internal/output/`.

## Versioning

- **PrexorCloud release versions** — semver; minor versions are
  additive, major versions can break.
- **gRPC contract** — `cloud-protocol` is bumped only on incompatible
  changes; the controller and daemon negotiate the protocol version
  at handshake.
- **Module SDK** — `dashboard/packages/module-sdk` ships its own
  version. The compat matrix lives at
  `dashboard/packages/module-sdk/COMPAT.md`.
- **Schema** — Mongo schema migrations run on startup and are logged
  (`migration applied: <name>`). Release notes call out migrations
  that need data backfill.

## Why we chose each thing — short list

| Pick | Over | Why |
|---|---|---|
| Java 25 controller | Java 21 | Records + pattern matching + virtual threads pay back the upgrade cost. Daemon stays on 21 for plugin-side compat. |
| Hand-wired `PrexorCloudBootstrap` | Spring / Guice | Readable graph, faster startup, no annotation magic. |
| Javalin 7 | Spring Boot | Minimal HTTP layer, native SSE, no auto-config. |
| MongoDB | PostgreSQL | Document model fits composition plans + workflow intent + module storage. |
| Valkey | Redis | BSD-3 license; same protocol. |
| Cosign keyless | Custom signing scheme | We do not maintain a private key. |
| Prometheus | OpenTelemetry | Two-service control plane, one gRPC contract. |
| SSE | WebSocket | Server → client only; Last-Event-ID resumption built in. |
| Compose | Helm chart | Operator audience already has Docker; K8s around MC processes is awkward. |
| Capability handles | Shared "internal" types module | Avoids the "every module depends on every module" trap. |

## Next up

- [Architecture](/internals/architecture/) — process boundaries and how the stack fits together
- [Storage Schema](/internals/storage-schema/) — what each store holds
- [Cosign Pipeline](/internals/cosign-pipeline/) — release + module signing
