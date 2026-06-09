---
title: Blog
description: Engineering posts, release notes, and architecture deep-dives from the PrexorCloud team.
---

The PrexorCloud blog is for the stories the [changelog](/changelog/) is
too terse to tell: release announcements, the reasoning behind a design
decision, and deep-dives into the parts of the system operators ask
about most. Posts are written by the people building PrexorCloud and
live in this repository next to the code — open a PR if you spot
something to fix.

If you want the line-by-line record of what changed in each release,
read the [changelog](/changelog/) instead.

## Launch posts

Three posts shipped with v1.0. Read them in order: the announcement
first, then the positioning piece if you want to know why the project
exists, then the deep-dive if you want to see how one of its layers
works.

### [PrexorCloud v1.0 — a cloud system built for Minecraft](/blog/v1-launch/)

What v1.0 is, what shipped in the box, and what it leaves out on
purpose. Start here if you are evaluating PrexorCloud against
[CloudNet 4](/compare/cloudnet-4/) or
[SimpleCloud V2](/compare/simplecloud-v2/).

### [Why we built another cloud orchestrator](/blog/why-another-orchestrator/)

The honest answer to "did we need another one?" — six trade-offs
PrexorCloud picked deliberately, and what each one costs.

### [Daemon-side modules — node-local extension on every host](/blog/daemon-side-modules/)

A technical deep-dive into running Module code inside the Daemon: the
`DaemonModule` contract, the Capability API, the controller-bridged
event bus, and the security model that keeps it isolated.

## Subscribing

There is no email list. The blog ships as part of this site, so point
any RSS reader at [`/blog/rss.xml`](/blog/rss.xml) to pick up new posts
as they land. Watchers of the
[main repository](https://github.com/prexorjustin/prexorcloud) get the
release notifications that line up with the larger posts.
</content>
</invoke>
