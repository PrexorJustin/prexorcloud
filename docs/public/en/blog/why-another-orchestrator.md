---
title: Why we built another cloud orchestrator
description: The framing question every Minecraft operator asks — why another one — answered with the trade-offs PrexorCloud v1 picked deliberately.
date: 2026-05-10
authors:
  - name: PrexorCloud Team
tags:
  - design
  - vision
---

Every operator we have shown PrexorCloud to has asked some version of
the same question. *CloudNet has been around for years. SimpleCloud
shipped V2. Pterodactyl exists. Why another one?* It is the right
question, and the honest answer is not "ours is better." We are a small
team and the existing options have a track record we will not match for
years. The honest answer is "ours is shaped differently, on purpose, and
the shape matters."

This post is the long-form version of that answer. It is not a feature
matrix — those live on
[the comparison pages](/compare/cloudnet-4/) — and it is not a
manifesto. It is a walk through six decisions that, taken together,
made PrexorCloud a different product category from the existing
options. Whether that category is the right one for your network is a
real question, and we tried to make it answerable from public docs
alone.

If you are looking for the headline release notes, the
[v1.0 launch post](/blog/v1-launch/) and the
[changelog](/changelog/) are the right starting points. This post sits
underneath them.

## What this post covers

- The frame: what the product is *for* and what it explicitly is not.
- Single-tenant by design, and what that buys us.
- The data layer choice: MongoDB plus Valkey, not a generic SQL store.
- The signing pipeline as a v1 default, not an opt-in.
- Active-active HA with lease-scoped fencing, not leader election.
- The trade-offs we said no to, and what they cost.

## The frame

Before any of the trade-offs make sense, the frame:

PrexorCloud is **a self-hosted orchestrator for the Minecraft network
of one operator team**. One controller, one operator team, fifty to
five thousand Minecraft instances, on bare metal or a small VM fleet.
That sentence is from the [orientation
page](/getting-started/what-is-prexorcloud/) and it is the most
load-bearing sentence we have written about the project. Every choice
below follows from it.

The frame rules out three adjacent products:

- **It is not a hosting panel.** Pterodactyl and its descendants give
  end-users a panel to spin up servers on shared infrastructure. That
  is a multi-tenant product where the panel operator is not the network
  operator. PrexorCloud assumes those are the same person.
- **It is not a generic container scheduler.** Kubernetes and Nomad
  schedule arbitrary workloads, and they do that well. They do not know
  what a Minecraft instance is, what a player connect event is, or what
  network composition means. We use Compose as the reference deployment
  shape because the inner loop the orchestrator runs is
  Minecraft-specific, not container-generic.
- **It is not a billing layer.** There is no signup, no pricing, no
  managed offering. If you want a hosting business, the orchestrator is
  one component of it; we do not ship the others.

What is left is the gap that the existing OSS Minecraft orchestrators
also occupy. So why shape ours differently?

## Single-tenant by design

The first decision was that PrexorCloud would not try to be
multi-tenant. One operator team, one cluster, one durable store. The
RBAC model has roles for operator and viewer, but it does not pretend
that two organisations share the same controller — the audit log, the
secret rotation flow, and the JWT revocation surface all assume a
single team's threat model.

Single-tenant is not a feature, it is a load-bearing constraint. It
lets us make several other choices that are awkward in a multi-tenant
product:

- **Trusted workloads.** A Minecraft instance is operator-controlled
  code; the operator chose the platform jar, the plugins, the world.
  Process isolation between instances is the host kernel's job. We do
  not need per-instance containers, and not having them takes a
  substantial dependency surface out of the v1 supply chain.
- **One audit log, one operator surface.** Every state-changing REST
  call writes to one MongoDB `audit` collection
  ([Cluster Model](/concepts/cluster-model/) lists it). There is no
  per-tenant view, no row-level filter, no "did this operator from
  organisation X have permission to read organisation Y's events"
  question to answer.
- **Aggressive default fail-closed posture.** The
  [security page](/concepts/security/) lists every public route the
  controller exposes — there are six, and four of them are
  health/version/login. Everything else is JWT-gated for operators or
  mTLS-gated for daemons or plugin-token-gated for in-server code. We
  can ship that posture by default because there is no marketplace of
  third-party tenants whose use-cases need to override it.

If your operating model is one team running one network, single-tenant
is the closer fit. If you are running infrastructure for multiple
unrelated teams who must not see each other's state, you want a
different product.

## The data layer choice

PrexorCloud uses MongoDB for durable state and Valkey (or any
Redis-protocol-compatible store) for coordination. Two stores, both
first-party, neither one optional in the production profile. The
[cluster model page](/concepts/cluster-model/) is the reference; this
section is the *why*.

We considered three alternatives during the v1 design:

1. **A single SQL store.** Postgres or MySQL for everything,
   coordination via SELECT-FOR-UPDATE or advisory locks. The argument
   was simplicity — one connection pool, one backup story, one query
   language. We rejected it for two reasons. First, the workload split
   is real: groups, templates, and composition plans want a document
   shape with embedded layered structure (the template chain has
   variable depth and SHA-256-keyed snapshots), and forcing them into
   relational tables produces JSONB columns with no actual schema
   benefit. Second, fencing tokens with monotonic counters and
   sub-second TTLs are exactly what Redis is for, and emulating them
   in SQL with row-level locks is slower and less obviously correct.
2. **Embedded H2 or SQLite for the small case.** This is what
   [CloudNet 4](/compare/cloudnet-4/) does and it is operationally
   nice for a single-host network. We rejected it because we wanted
   one supported storage shape. An operator who runs a single host
   today and adds a second host tomorrow should not have to migrate
   the durable store. MongoDB scales down to a single-replica
   single-shard deployment — that is the
   [reference Compose stack](/getting-started/installation/) — and
   scales up without a migration.
3. **Kafka or NATS as the coordination layer.** A real event bus
   between controllers. We rejected it on operational surface area:
   the things we actually want to coordinate (leases, fencing tokens,
   JWT revocation, SSE replay buffers, rate-limit windows) are all
   small, TTL-shaped, and bounded; running a broker for them is
   over-engineered. Valkey solves the problem in 200ms of cold-start
   latency.

The result is the
[three-tier memory model](/concepts/cluster-model/) on the cluster
model page — process memory, Valkey, MongoDB — with a documented rule
for which tier owns which piece of state. There is exactly one
override on the rule: never split a single piece of conceptual state
across two stores. A workflow intent lives in MongoDB or in Valkey,
not half-and-half.

The cost of this choice is that operators must run a real MongoDB and
(in production) a real Valkey. There is no embedded-everything path.
We documented this trade-off explicitly on the
[CloudNet comparison page](/compare/cloudnet-4/) and we are not
planning to add an embedded fallback for v1.

## The signing pipeline

PrexorCloud signs everything. `prexorctl` binaries are cosign-signed
with the GitHub Actions OIDC identity, multi-arch GHCR images are
signed the same way, and module bundles use cosign sign-blob with an
optional Rekor SET enforcement layer. In the production profile,
`modules.signing.required` defaults to `true` and unsigned bundles
fail to install with a 422 `SIGNATURE_VERIFICATION_FAILED` response.
The full mechanics are on the
[security page](/concepts/security/).

The decision to make signing a v1 *default* rather than a v2
opt-in was load-bearing. Two reasons:

- **Default-on or never.** A security posture that is opt-in becomes
  evidence that you can choose not to opt in, and the next operator who
  evaluates the project will note that they could turn it off. The
  cost of making it default-on at v1 is that we had to land the full
  pipeline (`<jar>.cosign.bundle` plus the legacy `<jar>.sig`,
  fail-closed verifier, the
  `CosignSignedModuleInstallTest` integration test, daemon-side
  re-verification on redistribution) before the v1 tag.
- **Offline by default.** Operators run PrexorCloud on hosts that may
  not have outbound internet at all. Cosign keyless verification needs
  Sigstore endpoints; that is fine for `prexorctl` install, where the
  operator's workstation is online, but it is not fine for
  module-install on an air-gapped controller. We bundle Rekor's public
  key in the controller and verify the SignedEntryTimestamp offline
  ([REQUIRE_SET](/concepts/security/) policy). Inclusion-proof Merkle
  verification is *not* implemented — the SET is enough, and adding
  the rest of the verifier requires online Rekor access we explicitly
  did not want to require.

The cost is that operators who install third-party modules must have a
trust root configured. There is no path that says "install whatever a
URL gave me." That is the point.

## Active-active HA

Controller HA in PrexorCloud is **active-active with lease-scoped
work**. Multiple controllers run simultaneously against the same
MongoDB and Valkey. Any healthy controller serves REST and gRPC.
Mutation paths must hold the relevant lease and carry the current
fencing token before they write. There is no single standby waiting
for a leader to fail.

The competing model — one active controller, one standby, leader
election via the coordination store — is simpler to reason about for a
single piece of work and worse to operate. Two reasons:

- **Failover latency is the lease TTL, not the leader-election
  timeout.** When a controller stops, work scoped to its leases
  resumes on whichever surviving controller acquires the lease next.
  There is no all-controllers-pause-while-electing window.
- **Read traffic spreads naturally.** Both controllers serve the
  dashboard and the CLI at the same time. With a single active
  controller, every read funnels through one node and the standby is
  cold capacity.

The cost is that the model only works if every mutation path is
gated by a lease and a fencing token. We did not retrofit this — the
[lease scopes](/concepts/cluster-model/) are documented up front
(`group:<name>`, `platform-module`, `workflow:<scope>`,
`node:<id>`), and `RecoveryTest` exercises standby promotion at four
points: drain, deployment, placement-time, and in-flight module
mutation. The `prexor:v1:lease-token:` family of monotonic counters
is the write-safety mechanism — clock skew can move expiry timing
around but cannot cause two controllers to issue conflicting writes
against the same scope.

If your network runs on one controller you will never see the active
side of this. The cost is not paid; the safety is. If your network
runs on two controllers you do not have to think about leader
elections — the
[recovery runbook](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/recover-controller.md)
is the operational story.

## The trade-offs we said no to

A v1 is defined by what it does *and* what it does not. The five
no-to things in v1 are deliberate and each carries a cost we accepted
on purpose:

- **No per-instance containers.** Trade-off: you do not get cgroup-
  bounded memory or CPU per Minecraft instance unless you put the
  daemon itself inside a container with limits. Mitigation: the
  daemon enforces JVM heap limits via `-Xmx` and the
  [scheduler](/concepts/scheduling-and-scaling/) does node-side
  capacity headroom. That is enough for a single-tenant network. It
  is *not* enough if you want to run untrusted workloads on shared
  infrastructure, which is why this decision rests on the
  single-tenant frame.
- **No Bedrock support.** Trade-off: networks with Bedrock players
  need a different orchestrator. We will not pretend Geyser inside a
  Java server is a substitute for first-class Bedrock platform
  support. Mitigation: the
  [comparison page with CloudNet 4](/compare/cloudnet-4/) is honest
  about this — if you need Bedrock, CloudNet 4 is the better fit.
- **No Kubernetes operator.** Trade-off: operators with an existing
  Kubernetes investment must run the controller and daemons as
  workloads they configure themselves. Mitigation: the GHCR images
  are multi-arch and signed; running them under any container
  scheduler is a config exercise, not a porting exercise. We do not
  ship a Helm chart because we are not yet confident we can support
  one.
- **No SSO at v1.** Trade-off: operator teams who want OIDC have to
  wait. Mitigation: the optional email-based password reset, account
  lockout, JWT revocation, and the small (six) public-route surface
  are the minimum viable operator-auth story for a single-team
  product.
- **No bundled Grafana dashboards.** Trade-off: operators paste
  PromQL into their own Grafana boards instead of importing ours.
  Mitigation: `/metrics` exposition is stable, label names are
  committed, and the
  [observability page](/operations/monitoring/) ships PromQL
  examples. We dropped the bundled pack because we could not commit
  to a support contract on every revision of every Grafana panel
  shape.

We expect to revisit some of these. Cross-node capability visibility
and proxy-side traffic-split routing (the substrate for blue-green
deployments — see [Deployments](/concepts/deployments/)) are the two
deferrals most likely to land in v2. SSO and Bedrock are not on the
near roadmap.

## What this all adds up to

If you take the six decisions together — single-tenant frame,
two-store data layer, signing as default, active-active HA without
leader election, no per-instance containers, no SSO — what you get is
a product whose moving parts are limited by intent. We can write down
every public REST route, every gRPC frame, every event type, every
lease scope, every Valkey key prefix, and every MongoDB collection on
fewer than ten pages. That is what a v1 is supposed to mean.

It also rules things out. If you operate a network that needs Bedrock,
or if your team needs OIDC, or if you cannot run a real MongoDB,
PrexorCloud v1 is not the right tool. The
[comparison pages](/compare/cloudnet-4/) say so explicitly, and we
encourage operators to read them with the same scepticism they would
read a vendor pitch.

The phrase we keep coming back to is *boring infrastructure*. A
Minecraft network operator is on call for player-facing problems —
crashes, lag, version compatibility, plugin bugs, the proxy that
isn't routing right. The orchestrator is supposed to be the part that
does not produce on-call incidents. v1 is our attempt to make
PrexorCloud that part.

## Where to go next

- [PrexorCloud v1.0 launch post](/blog/v1-launch/) — the headline
  release notes for v1.0.
- [Daemon-Side Modules deep-dive](/blog/daemon-side-modules/) — the
  most interesting structural change in v1, written for module
  authors.
- [Cluster Model](/concepts/cluster-model/) — the canonical reference
  for the three-tier memory model and lease/fencing rules cited above.
- [Compare: CloudNet 4](/compare/cloudnet-4/) and
  [Compare: SimpleCloud V2](/compare/simplecloud-v2/) — diplomatic
  side-by-sides if you are evaluating the field.
