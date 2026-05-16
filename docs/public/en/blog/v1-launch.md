---
title: PrexorCloud v1.0 — A Cloud System Built for Minecraft
description: PrexorCloud v1.0 ships — single-tenant Minecraft orchestrator with active-active HA, signed modules, daemon-side modules, and a curated first-party module set.
date: 2026-05-10
authors:
  - name: PrexorCloud Team
tags:
  - release
  - announcement
---

PrexorCloud v1.0 is out. The [v1.0 changelog entry](/changelog/) was
tagged on 2026-05-05 and the release pipeline cosign-signed every
artefact under that tag. This post is the human-readable companion: what
v1.0 is, what we shipped in the box, what we deliberately left out, and
how to try it.

If you have arrived here without context, the one-paragraph version is
on the [orientation page](/getting-started/what-is-prexorcloud/):
PrexorCloud is a self-hosted, Apache 2.0-licensed orchestrator for Minecraft
networks. One controller, one operator team, 50–5000 servers, on bare
metal or a small VM fleet. It is not a hosting panel and it is not a
generic container scheduler. It is the smallest viable replacement for
the `screen` sessions, the bash wrappers, and the hand-edited
`server.properties` that operate every Minecraft network we have ever
been on call for.

v1.0 closes out a year of work whose deliverable was *the same product
on a foundation we are willing to put production miles on*. The features
underneath the line — active-active HA, lease-scoped fencing, signed
modules, the nightly DR drill — are the ones we wanted in the box before
attaching a "1.0" to anything.

## What this post covers

- What v1.0 is and who it is for.
- What ships today, grouped by surface area.
- What is deliberately out of scope.
- How to try it on a laptop or on a real fleet.
- Where the project goes from here.

## What v1.0 is

A v1 release means three things, in order of how seriously we take them:

1. **The wire and storage shapes are stable.** Composition plans, the
   gRPC contract between controller and daemon, the module manifest, the
   REST surface, and every MongoDB collection are committed contracts.
   Breaking any of them will be a v2 conversation. The
   [java/cloud-protocol/contracts/proto-contracts.sha256 hash](https://github.com/prexorjustin/prexorcloud/blob/main/java/cloud-protocol/contracts/proto-contracts.sha256)
   is the canonical record on the gRPC side; the
   [OpenAPI 3.0 spec](https://github.com/prexorjustin/prexorcloud/blob/main/docs/openapi.json)
   is the same on the REST side.
2. **The HA story is real.** Multiple controllers run active-active
   against the same MongoDB and Valkey, gated by lease-scoped fencing.
   The recovery test harness exercises standby promotion at four points
   — drain, deployment, placement, and in-flight module mutation. If you
   have read [the cluster model page](/concepts/cluster-model/), you
   already know the model; the v1 line is that we are willing to ship it.
3. **Supply-chain integrity is enforced, not aspirational.** Every
   `prexorctl` binary is cosign-signed via keyless GitHub Actions OIDC
   identity. Every controller and daemon image on GHCR is signed the
   same way. Module bundles use cosign sign-blob with optional
   [offline Rekor SET enforcement](/concepts/security/), and the
   production default is `modules.signing.required: true` —
   fail-closed.

The audience is operators of self-hosted Minecraft networks who already
know what they are doing on a Linux box and want their cloud
orchestrator to know what it is doing on the JVM next to them. If that
is not you, the
[orientation page](/getting-started/what-is-prexorcloud/) and the two
comparison pages — [vs. CloudNet 4](/compare/cloudnet-4/) and
[vs. SimpleCloud V2](/compare/simplecloud-v2/) — are the honest
starting points.

## What ships today

Grouped by where the work lives:

### Controller and scheduler

- **Active-active HA** with `prexor:v1:lease:*` ownership and monotonic
  fencing tokens. Drain, deployment, placement, and in-flight module
  mutation all resume from durable state on standby promotion. The full
  lease and fencing model is on
  [Cluster Model](/concepts/cluster-model/).
- **Weighted-scoring scheduler** with affinity, anti-affinity,
  per-group anti-spread, capacity headroom, and three scaling modes
  (`STATIC`, `DYNAMIC`, `MANUAL`). Cooldowns and crash-loop auto-pause
  are part of the engine, not bolted on. See
  [Scheduling and Scaling](/concepts/scheduling-and-scaling/).
- **Rolling deployments** with `maxUnavailable`, plan-hash idempotency,
  pause / resume, and explicit rollback. No automatic rollback —
  [intentional, see the deployments page](/concepts/deployments/).
- **Twenty-two SSE event types** through `/api/v1/events/stream` with
  monotonic sequencing and `Last-Event-ID` replay. In production the
  replay buffer lives in Valkey and survives controller restart.
- **OpenAPI 3.1**: 151 hand-curated endpoints, mounted in the dashboard
  at `/playground` via Scalar.

### Modules and capabilities

- **Daemon-side modules** ([Layer 7](/concepts/modules/daemon/)). The
  new `DaemonModule` interface lives in `cloud-api`, ships from a
  controller-side `prexorctl module install`, fans out to every
  connected daemon over gRPC, and gives node-local code instance
  lifecycle hooks (`onInstanceStarting / Started / Stopping /
  Stopped`). The technical deep-dive is
  [here](/blog/daemon-side-modules/).
- **Cosign-signed module bundles** with `<jar>.cosign.bundle` sidecars,
  optional Rekor SET enforcement, and a fail-closed production default.
  Verification runs on the controller on install and on the daemon on
  re-distribution.
- **First-party reference module: `stats-aggregator`** under
  `java/cloud-modules/stats-aggregator/`. It exercises REST
  routes, capability registration, MongoDB-backed storage, workload
  extensions, and a frontend manifest in one place.
- **Capability registry events** (`CapabilityRegisteredEvent`,
  `CapabilityUnregisteredEvent`, `CapabilityProviderChangedEvent`) flow
  through the SSE bus and a dedicated stream at
  `/api/v1/modules/platform/capabilities/stream`. The dashboard's
  `useCapability` composable reactively reflects provider changes
  without a page reload.

### Daemons and plugins

- **mTLS daemon onboarding** through a single-use join token. The
  controller's CA issues a per-node certificate at first contact, the
  daemon writes it to disk, and from that point every gRPC call is
  mTLS-authenticated. Per-node revocation is one REST call —
  `POST /api/v1/nodes/{id}/revoke-cert`. See
  [Security](/concepts/security/).
- **`prexorctl plugin new`**, symmetrically with `prexorctl module new`,
  scaffolds standalone `@CloudPlugin` jars for Paper / Spigot / Folia
  / Velocity / BungeeCord. The plugin-vs-module decision tree lives at
  [Plugins](/concepts/plugins/).
- **Network Composition** end-to-end: controller-side persistence and
  REST, proxy-plugin cache, dashboard editor, and proxy-plugin routing
  all ship together. Operators define lobby and fallback chains once;
  the proxy plugin walks them on every connect and on every kick.

### Operations

- **Cosign-signed release pipeline.** `release.yml` ships
  cosign-signed `prexorctl` binaries; `release-images.yml` ships
  cosign-signed multi-arch GHCR images on every `v*` tag. The release
  workflow runs `cosign verify` against its own freshly-signed images
  as the last step.
- **Performance baseline harness** with a CI drift comparator. The
  nightly run measures controller cold start, coordination-store
  latency, SSE latency, and scheduler tick at 1k groups, and warns at
  greater than 25 % drift.
- **Nightly disaster-recovery drill.** The `dr-drill` job in
  `.github/workflows/nightly.yml` boots an in-process controller against
  a real Mongo and Valkey, takes a backup, drops the database, restores
  from the manifest, and asserts the restored state matches the seed.
  Documented at
  [Operations / Disaster Drill](/operations/disaster-drill/).
- **First-class backup and restore.** `prexorctl backup` and
  `prexorctl restore` are part of v1, with a manifest-based restore
  format and a dry-run validator. The
  [backup runbook](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/backup.md)
  and [restore runbook](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/restore.md)
  are the recovery story for the durable tier.

### CLI, dashboard, SDK

- **`prexorctl setup`** with native and Compose install modes,
  including cosign verification of the downloaded controller and daemon
  jars before they run.
- **First-party Nuxt 4 dashboard** with SSE-driven state, console
  streaming, file editor, and reactive capability resolution.
- **`AbstractPluginContext` shared base** for plugin platforms.
  `BukkitPluginContext` and `VelocityPluginContext` shrunk from
  ~100-line constructors to ~10.
- **Email-based password reset** (off by default), single-use
  30-minute tokens, STARTTLS / implicit-TLS / AUTH support via
  `jakarta.mail`. There is no OIDC, no SAML, no MFA in v1, on purpose.

The
[full v1.0 changelog entry](/changelog/) is the line-by-line ledger.

## What is deliberately out of scope

A v1 is also defined by what is *not* there. Five things we said no to,
on purpose:

- **No per-instance containers.** Daemons supervise JVMs with
  `ProcessBuilder`, not Docker. Process isolation is delegated to the
  host OS. Containers are valuable when you do not trust your
  workloads, and we do — Minecraft instances are operator-controlled,
  not multi-tenant.
- **No Bedrock support.** Paper / Spigot / Folia for servers; Velocity
  / BungeeCord for proxies. WaterDogPE and Nukkit are not in v1.
- **No Kubernetes operator.** Compose is the reference install path.
  Helm charts and an operator are a community-driven v2 conversation.
- **No SSO.** Operator auth is strictly username + password + JWT,
  with optional email-based password reset. OIDC and SAML were removed
  during the v1 cleanup.
- **No bundled Grafana dashboards.** `/metrics` exposition is stable
  and the [observability page](/operations/monitoring/) ships PromQL
  examples; the previously-bundled dashboard pack was deleted to keep
  the supported surface narrow.

Each of those is a decision, not an oversight. We would rather ship
fewer things well than half-ship a wider matrix.

## Trying it

**On a laptop, ten minutes.** Follow the
[quickstart](/getting-started/quickstart/). The development profile
runs without Valkey — single-controller correctness, lost SSE replay on
restart, no HA — but it is enough to spin up a lobby, scale a group,
edit a template, and watch the dashboard reflect every change.

**On a real fleet.** The
[Compose stack](/getting-started/installation/) is the reference. It
ships Valkey out of the box, runs the production profile, and is the
configuration the nightly DR drill exercises. After install, walk the
[production hardening checklist](/operations/production-checklist/)
within the first five minutes: rotate the bootstrap admin password,
shred `.initial-admin-password`, set `network.allowedSubnets` to your
operator and daemon ranges, terminate TLS at a reverse proxy, enable
`modules.signing.required` if you plan to install third-party modules.

Either path verifies the install with cosign:

```bash
prexorctl version --verify
```

That command runs cosign keyless verification against the GitHub
Actions OIDC identity that signed your binary. If the verification
fails, the binary refuses to continue.

## Roadmap

We do not commit to a v2 timeline today. The work that is staged for
post-v1 sits in three buckets:

- **Cross-node capability visibility.** v1 daemon modules see only
  capabilities registered on the same host. Cross-node visibility, with
  the lease and consistency model that goes with it, is a v2
  conversation. The constraint is documented on the
  [daemon modules page](/concepts/modules/daemon/).
- **Blue-green and traffic-split deployments.** v1 only does rolling
  restart. Proxy-side traffic weighting is the missing primitive; once
  it lands, blue-green falls out as a special case.
- **Maven Central distribution for the module SDK.** v1 publishes the
  SDK with the release artefacts; Maven Central is staged for after
  the surface settles, since publishing locks the artefact coordinates.

If any of those move, they will land first as architectural decisions
and then as code, not the other way around.

## Where to go next

- [Why we built another cloud orchestrator](/blog/why-another-orchestrator/)
  — the framing piece on what makes v1 a different product category
  from CloudNet 4 and SimpleCloud V2.
- [Daemon-Side Modules](/blog/daemon-side-modules/) — the technical
  deep-dive on Layer 7, the most interesting structural change in v1.
- [Architecture](/concepts/architecture/) — controller subsystems,
  gRPC shape, classloader rules, one level deeper than the orientation.
- [Cluster Model](/concepts/cluster-model/) — runtime profiles, the
  three memory tiers, lease and fencing.
- [Changelog](/changelog/) — the line-by-line ledger of what landed
  in v1.0.
