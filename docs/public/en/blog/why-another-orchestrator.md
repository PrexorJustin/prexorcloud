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

Every operator we show PrexorCloud to asks a version of the same
question. CloudNet has been around for years. SimpleCloud shipped V2.
Pterodactyl exists. Kubernetes exists. Why another one?

It is the right question, and the honest answer is not "ours is better."
We are a small team, and the existing options have a track record we
will not match for years. The answer is "ours is shaped differently, on
purpose, and the shape is the point."

This is the long-form version of that answer. It is not a feature
matrix — those live on the [comparison pages](/compare/cloudnet-4/) —
and it is not a manifesto. It is a walk through six decisions that, taken
together, made PrexorCloud a different product category. Whether that
category fits your network is a real question, and we tried to make it
answerable from public docs alone. For the headline release notes, start
with the [v1.0 launch post](/blog/v1-launch/).

## What this post covers

- The frame: what the product is for, and what it is not.
- Single-tenant by design, and what that buys.
- The data layer: MongoDB plus Valkey, not a generic SQL store.
- Signing as a v1 default, not a v2 opt-in.
- Active-active HA with lease-scoped fencing, not leader election.
- The trade-offs we said no to, and what they cost.

## The frame

Before the trade-offs make sense, the frame:

PrexorCloud is **a self-hosted orchestrator for the Minecraft network of
one operator team**. One Controller, one operator team, 50 to 5000
Instances, on bare metal or a small VM fleet. That sentence is from the
[orientation page](/getting-started/what-is-prexorcloud/) and it is the
most load-bearing thing we have written about the project. Every choice
below follows from it.

The frame rules out three adjacent products:

- **It is not a hosting panel.** Pterodactyl and its descendants give
  end users a panel to spin up servers on shared infrastructure. That is
  multi-tenant, where the panel operator is not the network operator.
  PrexorCloud assumes those are the same person.
- **It is not a generic container scheduler.** Kubernetes and Nomad
  schedule arbitrary workloads, and they do it well. They do not know
  what an Instance is, what a player-connect event is, or what a Network
  composition means. We use Compose as the reference deployment shape
  because the inner loop here is Minecraft-specific, not
  container-generic.
- **It is not a billing layer.** No signup, no pricing, no managed
  offering. If you want a hosting business, the orchestrator is one
  component of it; we do not ship the others.

What is left is the gap the existing OSS Minecraft orchestrators also
occupy. So why shape ours differently?

## Single-tenant by design

The first decision was that PrexorCloud would not try to be
multi-tenant. One operator team, one cluster, one durable store. The
RBAC model has roles for operator and viewer, but it does not pretend
two organizations share a Controller — the audit log, the secret
rotation flow, and the JWT revocation surface all assume one team's
threat model.

Single-tenant is not a feature, it is a load-bearing constraint. It
unlocks several choices that are awkward in a multi-tenant product:

- **Trusted workloads.** An Instance runs operator-controlled code; the
  operator chose the platform jar, the Plugins, the world. Isolation
  between Instances is the host kernel's job. No per-instance containers,
  and not having them takes a large dependency surface out of the v1
  supply chain.
- **One audit log, one operator surface.** Every state-changing REST
  call writes to one MongoDB `audit` collection. There is no per-tenant
  view and no "did operator from org X have permission to read org Y's
  events" question to answer.
- **A fail-closed default posture.** The three auth paths
  ([Security](/concepts/security/)) — JWT for operators, mTLS for
  Daemons, plugin tokens for in-server code — gate everything except a
  short public exemption list (login, health, version). We ship that
  posture by default because there is no marketplace of third-party
  tenants whose use cases need to override it.

If your operating model is one team running one Network, single-tenant
fits. If you run infrastructure for multiple unrelated teams that must
not see each other's state, you want a different product.

## The data layer

PrexorCloud uses MongoDB for durable state and Valkey (or any
Redis-protocol store) for coordination. Two stores, both first-party,
neither optional in the production profile. The
[cluster model page](/concepts/cluster-model/) is the reference; this is
the why.

We considered three alternatives during the v1 design:

1. **A single SQL store.** Postgres or MySQL for everything,
   coordination through SELECT-FOR-UPDATE or advisory locks. The
   argument was simplicity — one pool, one backup story, one query
   language. We rejected it for two reasons. The workload split is real:
   Groups, Templates, and composition plans want a document shape with
   embedded layered structure (the Template chain has variable depth and
   SHA-256-keyed snapshots), and forcing them into relational tables
   produces JSONB columns with no schema benefit. And fencing tokens
   with monotonic counters and sub-second TTLs are what Redis is for;
   emulating them in SQL with row locks is slower and less obviously
   correct.
2. **Embedded H2 or SQLite for the small case.** This is what
   [CloudNet 4](/compare/cloudnet-4/) does, and it is operationally nice
   for a single host. We rejected it because we wanted one supported
   storage shape. An operator who runs one host today and adds a second
   tomorrow should not migrate the durable store. MongoDB scales down to
   a single-replica deployment — the
   [reference Compose stack](/getting-started/installation/) — and up
   without a migration.
3. **Kafka or NATS as the coordination layer.** A real broker between
   Controllers. We rejected it on operational surface: the things we
   coordinate (leases, fencing tokens, JWT revocation, SSE replay
   buffers, rate-limit windows) are all small, TTL-shaped, and bounded.
   Running a broker for them is over-engineered; Valkey solves it with a
   sub-second cold start.

The result is the
[memory model](/concepts/cluster-model/) on the cluster model page —
process memory, Valkey, MongoDB — with a documented rule for which tier
owns which state, and one override: never split a single piece of
conceptual state across two stores.

The cost is that operators must run a real MongoDB and, in production, a
real Valkey. There is no embedded-everything path, and we are not adding
one for v1. The trade-off is spelled out on the
[CloudNet comparison page](/compare/cloudnet-4/).

## The signing pipeline

PrexorCloud signs everything. `prexorctl` binaries are cosign-signed
with the GitHub Actions OIDC identity, multi-arch GHCR images the same
way, and Module bundles use cosign sign-blob with an optional Rekor SET
enforcement layer. In the production profile,
`modules.signing.required` defaults to `true`, and an unsigned bundle
fails to install with `422 SIGNATURE_VERIFICATION_FAILED`. The mechanics
are on the [security page](/concepts/security/).

Making signing a v1 default rather than a v2 opt-in was load-bearing.
Two reasons:

- **Default-on or never.** An opt-in security posture is evidence that
  you can choose not to opt in, and the next operator who evaluates the
  project will note they could turn it off. The cost of default-on at v1
  was landing the full pipeline — the `<jar>.cosign.bundle` sidecar, the
  fail-closed verifier, the `CosignSignedModuleInstallTest` integration
  test, and Daemon-side re-verification on redistribution — before the
  v1 tag.
- **Offline by default.** Operators run PrexorCloud on hosts that may
  have no outbound internet. Cosign keyless verification needs Sigstore
  endpoints, which is fine for `prexorctl` install from an online
  workstation but not for a Module install on an air-gapped Controller.
  We bundle Rekor's public key and verify the SignedEntryTimestamp
  offline ([`REQUIRE_SET`](/concepts/security/)). Inclusion-proof Merkle
  verification is not implemented — the SET is the enforced control, and
  the rest of the verifier would require online Rekor access we did not
  want to require.

The cost is that operators who install third-party Modules must
configure a trust root. There is no "install whatever a URL gave me"
path. That is the point.

## Active-active HA

Controller HA in PrexorCloud is **active-active with lease-scoped
work**. Multiple Controllers run at once against the same MongoDB and
Valkey. Any healthy Controller serves REST and gRPC. A mutation path
must hold the relevant lease and carry the current fencing token before
it writes. There is no single standby waiting for a leader to fail.

The competing model — one active Controller, one standby, leader
election through the coordination store — is simpler to reason about for
one piece of work and worse to operate. Two reasons:

- **Failover latency is the lease TTL, not an election timeout.** When a
  Controller stops, work scoped to its leases resumes on whichever
  survivor acquires the lease next. There is no
  all-Controllers-pause-while-electing window.
- **Read traffic spreads.** Both Controllers serve the dashboard and the
  CLI at the same time. With a single active Controller, every read
  funnels through one node and the standby is cold capacity.

The cost is that the model only works if every mutation path is gated by
a lease and a fencing token. We did not retrofit it. The
[lease scopes](/concepts/cluster-model/) are documented up front, and
`RecoveryTest` exercises standby pickup at four points: drain,
deployment, placement-time, and in-flight Module mutation. The monotonic
fencing counters are the write-safety mechanism — clock skew can move
expiry timing but cannot let two Controllers issue conflicting writes
against the same scope.

If your Network runs on one Controller you never see the active side of
this; the cost is not paid and the safety is there. If it runs on two,
you do not think about leader elections.

## The trade-offs we said no to

A v1 is defined by what it does and what it does not. The five no-to
things each carry a cost we accepted on purpose:

- **No per-instance containers.** Trade-off: no cgroup-bounded memory or
  CPU per Instance unless you put the Daemon itself in a container with
  limits. Mitigation: the Daemon enforces JVM heap limits with `-Xmx`
  and the [scheduler](/concepts/scheduling-and-scaling/) does node-side
  capacity headroom. Enough for a single-tenant Network; not enough for
  untrusted workloads on shared infrastructure, which is why this rests
  on the single-tenant frame.
- **No Bedrock support.** Trade-off: Networks with Bedrock players need
  a different orchestrator. We will not pretend Geyser inside a Java
  server is first-class Bedrock support. Mitigation: the
  [CloudNet 4 comparison](/compare/cloudnet-4/) is honest — if you need
  Bedrock, CloudNet 4 is the better fit.
- **No Kubernetes operator.** Trade-off: operators with an existing
  Kubernetes investment run the Controller and Daemons as workloads they
  configure themselves. Mitigation: the GHCR images are multi-arch and
  signed; running them under any scheduler is a config exercise, not a
  porting one. We do not ship a Helm chart because we are not yet
  confident we can support one.
- **No SSO at v1.** Trade-off: teams that want OIDC wait. Mitigation:
  optional email-based password reset, account lockout, JWT revocation,
  and a small public-route surface are the minimum viable operator-auth
  story for a single-team product.
- **No bundled Grafana dashboards.** Trade-off: operators paste PromQL
  into their own boards instead of importing ours. Mitigation: the
  `/metrics` exposition is stable, label names are committed, and the
  [monitoring page](/operations/monitoring/) ships PromQL examples. We
  dropped the bundled pack because we could not commit to supporting
  every revision of every panel.

We expect to revisit some of these. Cross-node capability visibility and
proxy-side traffic-split routing (the substrate for blue-green — see
[Deployments](/concepts/deployments/)) are the two deferrals most likely
to land in v2. SSO and Bedrock are not on the near roadmap.

## What this adds up to

Take the six decisions together — single-tenant frame, two-store data
layer, signing as default, active-active HA without leader election, no
per-instance containers, no SSO — and you get a product whose moving
parts are limited by intent. Every public REST route, every gRPC frame,
every event type, every lease scope, every Valkey key prefix, and every
MongoDB collection fits on a handful of pages. That is what a v1 is
supposed to mean.

It also rules things out. If your Network needs Bedrock, if your team
needs OIDC, or if you cannot run a real MongoDB, PrexorCloud v1 is not
the right tool. The [comparison pages](/compare/cloudnet-4/) say so, and
we want operators to read them with the skepticism they would bring to a
vendor pitch.

The phrase we keep coming back to is *boring infrastructure*. A network
operator is on call for player-facing problems — crashes, lag, version
mismatches, Plugin bugs, a proxy that will not route. The orchestrator
is supposed to be the part that does not generate incidents. v1 is our
attempt to make PrexorCloud that part.

## Where to go next

- [PrexorCloud v1.0 launch post](/blog/v1-launch/) — the headline
  release notes.
- [Daemon-side modules](/blog/daemon-side-modules/) — the most
  interesting structural change in v1, written for Module authors.
- [Cluster model](/concepts/cluster-model/) — the canonical reference
  for the memory model and the lease/fencing rules cited above.
- [Compare: CloudNet 4](/compare/cloudnet-4/) and
  [Compare: SimpleCloud V2](/compare/simplecloud-v2/) — side-by-sides if
  you are evaluating the field.
</content>
