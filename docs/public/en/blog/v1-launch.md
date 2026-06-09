---
title: PrexorCloud v1.0 — a cloud system built for Minecraft
description: PrexorCloud v1.0 ships — single-tenant Minecraft orchestrator with active-active HA, signed modules, daemon-side modules, and a curated first-party module set.
date: 2026-05-10
authors:
  - name: PrexorCloud Team
tags:
  - release
  - announcement
---

PrexorCloud v1.0 is out. The release was tagged on 2026-05-05, and the
pipeline cosign-signed every artifact under that tag. This post is the
human-readable companion to the [changelog](/changelog/): what v1.0 is,
what shipped in the box, what it leaves out on purpose, and how to try
it.

The one-paragraph version, from the
[orientation page](/getting-started/what-is-prexorcloud/): PrexorCloud
is a self-hosted, Apache 2.0-licensed orchestrator for Minecraft
networks. One Controller, one operator team, 50 to 5000 Instances, on
bare metal or a small VM fleet. It is not a hosting panel and not a
generic container scheduler. It is the smallest viable replacement for
the `screen` sessions, the bash wrappers, and the hand-edited
`server.properties` that run most Minecraft networks.

v1.0 closes a year of work whose deliverable was the same product on a
foundation worth putting production miles on. The features under the
line — active-active HA, lease-scoped fencing, signed Modules, the
nightly DR drill — are the ones we wanted in the box before attaching a
"1.0" to anything.

## What this post covers

- What v1.0 is and who it is for.
- What ships today, grouped by surface.
- What is deliberately out of scope.
- How to try it on a laptop or a real fleet.
- Where the project goes next.

## What v1.0 is

A v1 release means three things, in order of how seriously we take them.

1. **The wire and storage shapes are stable.** Composition plans, the
   gRPC contract between Controller and Daemon, the Module manifest, the
   REST surface, and every MongoDB collection are committed contracts.
   Breaking one is a v2 conversation. The
   [`proto-contracts.sha256`](https://github.com/prexorjustin/prexorcloud/blob/main/java/cloud-protocol/contracts/proto-contracts.sha256)
   hash is the canonical record on the gRPC side; the
   [OpenAPI spec](https://github.com/prexorjustin/prexorcloud/blob/main/docs/openapi.json)
   is the same on the REST side.
2. **The HA story is real.** Multiple Controllers run active-active
   against the same MongoDB and Valkey, gated by lease-scoped fencing
   tokens. `RecoveryTest` exercises standby pickup at four points —
   drain, deployment, placement, and in-flight Module mutation. The
   model is on [Cluster model](/concepts/cluster-model/).
3. **Supply-chain integrity is enforced, not aspirational.** Every
   `prexorctl` binary is cosign-signed through keyless GitHub Actions
   OIDC. Every Controller and Daemon image on GHCR is signed the same
   way. Module bundles use cosign sign-blob with optional
   [offline Rekor SET enforcement](/concepts/security/), and the
   production default is `modules.signing.required: true` — fail-closed.

The audience is operators who already know their way around a Linux box
and want the orchestrator next to them to know its way around the JVM.
If that is not you, the
[orientation page](/getting-started/what-is-prexorcloud/) and the two
comparison pages — [vs. CloudNet 4](/compare/cloudnet-4/) and
[vs. SimpleCloud V2](/compare/simplecloud-v2/) — are the honest starting
points.

## What ships today

Grouped by where the work lives.

### Controller and scheduler

- **Active-active HA** with `prexor:v1:lease:*` ownership and monotonic
  fencing tokens. Drain, deployment, placement, and in-flight Module
  mutation all resume from durable state when a standby takes over. See
  [Cluster model](/concepts/cluster-model/).
- **Weighted-scoring scheduler** with affinity, anti-affinity, per-Group
  anti-spread, capacity headroom, and three scaling modes (`STATIC`,
  `DYNAMIC`, `MANUAL`). Cooldowns and crash-loop auto-pause are part of
  the engine. See [Scheduling and scaling](/concepts/scheduling-and-scaling/).
- **Rolling deployments** with `maxUnavailable`, plan-hash idempotency,
  pause/resume, and explicit rollback. There is no automatic rollback —
  [intentional](/concepts/deployments/).
- **An SSE event stream** at `/api/v1/events/stream` with monotonic
  sequencing and `Last-Event-ID` replay. In production the replay buffer
  lives in Valkey and survives a Controller restart. See
  [Events](/concepts/events/).
- **An OpenAPI-documented REST surface**, browsable in the dashboard at
  `/playground` through Scalar.

### Modules and capabilities

- **Daemon modules.** A new `DaemonModule` interface in `cloud-api`
  installs from one `prexorctl module install`, fans out to every
  connected Daemon over gRPC, and gives node-local code instance
  lifecycle hooks (`onInstanceStarting` / `Started` / `Stopping` /
  `Stopped`). The deep-dive is [here](/blog/daemon-side-modules/).
- **The Capability API.** Modules link to one another only through
  named, typed capabilities — never each other's classes — and the
  Controller registers a built-in `prexor.instance.files` capability
  that lets a Module read files inside a running Instance without opening
  its own Daemon channel. See [Capabilities](/concepts/modules/capabilities/).
- **Cosign-signed Module bundles** with `<jar>.cosign.bundle` sidecars,
  optional Rekor SET enforcement, and a fail-closed production default.
  Verification runs on the Controller at install and on the Daemon at
  redistribution.
- **A first-party reference Module, `stats-aggregator`**, under
  `java/cloud-modules/stats-aggregator/`. It exercises REST routes,
  capability registration, MongoDB-backed storage, workload extensions,
  and a frontend manifest in one place.
- **Capability registry events** (`CapabilityRegisteredEvent`,
  `CapabilityUnregisteredEvent`, `CapabilityProviderChangedEvent`) on
  the SSE bus, plus a dedicated stream at
  `/api/v1/modules/platform/capabilities/stream`. The dashboard's
  `useCapability` composable reflects provider changes without a reload.

### Daemons and plugins

- **mTLS Daemon onboarding** through a single-use join token. The
  Controller's CA issues a per-node certificate at first contact; every
  gRPC call after that is mTLS-authenticated. Per-node revocation is one
  REST call. See [Security](/concepts/security/).
- **`prexorctl plugin new`**, the symmetric counterpart of
  `prexorctl module new`, scaffolds standalone `@CloudPlugin` jars for
  Paper / Spigot / Folia / Velocity / BungeeCord. The plugin-vs-module
  decision tree is on [Plugins](/concepts/plugins/).
- **Network composition** end-to-end: Controller-side persistence and
  REST, proxy-plugin cache, dashboard editor, and proxy-plugin routing.
  Operators define lobby and fallback chains once; the proxy Plugin
  walks them on every connect and every kick.

### Operations

- **A cosign-signed release pipeline.** `release.yml` ships
  cosign-signed `prexorctl` binaries; `release-images.yml` ships
  cosign-signed multi-arch GHCR images on every `v*` tag. The release
  workflow runs `cosign verify` against its own freshly-signed images as
  the final step.
- **A performance baseline harness** with a CI drift comparator. The
  nightly run measures Controller cold start, coordination-store
  latency, SSE latency, and scheduler tick at 1k Groups, and warns past
  25 % drift.
- **A nightly disaster-recovery drill.** The `dr-drill` job in
  `nightly.yml` boots an in-process Controller against a real Mongo and
  Valkey, takes a backup, drops the database, restores from the
  manifest, and asserts the restored state matches the seed. See
  [the disaster drill page](/operations/disaster-drill/).
- **First-class backup and restore.** `prexorctl backup` and
  `prexorctl restore` are part of v1, with a manifest-based restore
  format and a dry-run validator.

### CLI, dashboard, SDK

- **`prexorctl setup`** with native and Compose install modes, including
  cosign verification of the downloaded Controller and Daemon jars
  before they run.
- **A first-party Nuxt 4 dashboard** with SSE-driven state, console
  streaming, a file editor, and reactive capability resolution.
- **Email-based password reset** (off by default), single-use 30-minute
  tokens, STARTTLS / implicit-TLS / AUTH through `jakarta.mail`. There is
  no OIDC, no SAML, no MFA in v1, on purpose.

The [changelog](/changelog/) is the line-by-line ledger.

## What is deliberately out of scope

A v1 is also defined by what is not there. Five things we said no to, on
purpose:

- **No per-instance containers.** Daemons supervise JVMs with
  `ProcessBuilder`, not Docker. Process isolation is the host OS's job.
  Containers earn their keep when you do not trust your workloads;
  Minecraft Instances are operator-controlled, not multi-tenant.
- **No Bedrock support.** Paper / Spigot / Folia for servers, Velocity /
  BungeeCord for proxies. WaterDogPE and Nukkit are not in v1.
- **No Kubernetes operator.** Compose is the reference install path. A
  Helm chart and an operator are a v2 conversation.
- **No SSO.** Operator auth is username + password + JWT, with optional
  email-based password reset. OIDC and SAML were removed during the v1
  cleanup.
- **No bundled Grafana dashboards.** The `/metrics` exposition is stable
  and the [monitoring page](/operations/monitoring/) ships PromQL
  examples; the previously bundled dashboard pack was dropped to keep
  the supported surface narrow.

Each of those is a decision, not an oversight. The reasoning is in
[Why we built another cloud orchestrator](/blog/why-another-orchestrator/).

## Trying it

**On a laptop, ten minutes.** Follow the
[quickstart](/getting-started/quickstart/). The development profile runs
without Valkey — single-Controller correctness, lost SSE replay on
restart, no HA — but it is enough to spin up a lobby, scale a Group,
edit a Template, and watch the dashboard track every change.

**On a real fleet.** The
[Compose stack](/getting-started/installation/) is the reference. It
ships Valkey, runs the production profile, and is the configuration the
nightly DR drill exercises. After install, walk the
[production checklist](/operations/production-checklist/) in the first
five minutes: rotate the bootstrap admin password, shred
`.initial-admin-password`, set `network.allowedSubnets` to your operator
and Daemon ranges, terminate TLS at a reverse proxy, and enable
`modules.signing.required` if you plan to install third-party Modules.

Either path verifies the install with cosign:

```bash
prexorctl version --verify
```

That runs cosign keyless verification against the GitHub Actions OIDC
identity that signed your binary. If verification fails, the binary
refuses to continue.

## Roadmap

We do not commit to a v2 timeline today. The work staged for after v1
sits in three buckets:

- **Cross-node capability visibility.** A v1 daemon Module sees only
  capabilities registered on its own host. A Controller-mediated,
  cluster-wide view — with the consistency and lease model that goes
  with it — is a v2 conversation, documented on the
  [daemon modules page](/concepts/modules/daemon/).
- **Blue-green and traffic-split deployments.** v1 does rolling restart
  only. Proxy-side traffic weighting is the missing primitive; once it
  lands, blue-green falls out as a special case.
- **Maven Central distribution for the Module SDK.** v1 publishes the
  SDK with the release artifacts; Maven Central is staged for after the
  surface settles.

If any of those move, they land first as an architectural decision and
then as code.

## Where to go next

- [Why we built another cloud orchestrator](/blog/why-another-orchestrator/)
  — the trade-offs that make v1 a different product category from
  CloudNet 4 and SimpleCloud V2.
- [Daemon-side modules](/blog/daemon-side-modules/) — the deep-dive on
  the most interesting structural change in v1.
- [Architecture](/concepts/architecture/) — Controller subsystems, the
  gRPC shape, and the classloader rules.
- [Cluster model](/concepts/cluster-model/) — runtime profiles, the
  memory tiers, leases, and fencing.
- [Changelog](/changelog/) — the line-by-line ledger of v1.0.
</content>
