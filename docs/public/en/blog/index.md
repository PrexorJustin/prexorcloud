---
title: Blog
description: Engineering posts, release notes, and architecture deep-dives from the PrexorCloud team.
---

The PrexorCloud blog covers release notes, architectural decisions we
think are worth a long-form explanation, and engineering deep-dives into
the parts of the system we are most often asked about. Posts are written
by the people building PrexorCloud and live in the same git repository as
the source — issue a PR if you want to suggest a correction.

If you are looking for a structured changelog, see the
[changelog](/changelog/) instead. The blog is for stories the changelog
is too terse to tell.

## Launch posts

Three posts ship alongside the v1.0 launch. They are listed below in the
recommended reading order — start with the announcement, then the vision
piece if you want to know *why* this project exists, then the technical
deep-dive if you want to know *how* one of its more interesting layers
works.

### [PrexorCloud v1.0 — A cloud system built for Minecraft](/blog/v1-launch/)

The launch post. What v1.0 is, who it is for, what it includes, and what
it deliberately leaves out. If you are evaluating PrexorCloud against
[CloudNet 4](/compare/cloudnet-4/) or
[SimpleCloud V2](/compare/simplecloud-v2/), this is the right starting
point — it covers the headline features, the Apache 2.0 licence, the release
artifacts, the cosign-signed install path, and the five things v1
deliberately leaves out of scope.

### [Why we built another cloud orchestrator](/blog/why-another-orchestrator/)

A longer essay on the framing question every Minecraft operator asks
when they meet a new orchestrator: *did we really need another one?* The
post walks through six trade-offs PrexorCloud picked deliberately —
single-tenant by design, MongoDB plus Valkey as the data layer,
signing as a v1 default rather than a v2 opt-in, active-active HA
without leader election, no per-instance containers, no SSO — and
argues why those choices add up to a different product category from
the existing options.

### [Daemon-side modules — how Layer 7 unlocks node-local extension](/blog/daemon-side-modules/)

A walk through the design of daemon-side modules: the new
`DaemonModule` interface, the controller-side `ModuleDistributor`
that fans installs out to every connected daemon over gRPC, the
event bridge that lets a module on a host subscribe to controller
events without polling, and the instance-lifecycle hooks
(`onInstanceStarting / Started / Stopping / Stopped`) that make
node-local logic — JVM args, env mutation, profiling injection,
custom crash handling — possible without forking the daemon. The
canonical reference is [the daemon module concept page](/concepts/modules/daemon/);
the post puts the design choices in context, with sequence and
state diagrams of the install and lifecycle flows.

## Subscribing

There is no email list yet. The blog ships as part of this site, so any
RSS reader pointed at `/blog/rss.xml` will pick up new posts as they
land. GitHub watchers of the
[main repository](https://github.com/prexorjustin/prexorcloud) get
release notifications, which line up with the bigger blog posts.
