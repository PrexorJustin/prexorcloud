---
title: Quickstart (10 min)
description: From a fresh install to a running Paper instance in ten minutes — token, daemon, group, scale.
---

You have a controller and at least one daemon already
([Installation](/getting-started/installation/)). This page takes you from
"empty cluster" to "Paper 1.21 lobby instance running" in ten minutes.

## What you'll learn

- The five-step path from empty cluster to a running instance.
- How a `GroupConfig` translates into instances, and how the scheduler
  decides where they run.
- How to watch state in real time with the dashboard or `prexorctl`.

## What you need

- Controller reachable on `https://<host>:8080`, daemon reporting `READY`
  in `prexorctl node list`.
- Logged in via `prexorctl login` (you should see the cluster in
  `prexorctl status`).
- About 1 GB free RAM on the daemon host for a Paper instance.

## Step 1 — Pick a platform JAR from the catalog

The **catalog** maps platform + version to a download URL. The controller
ships with built-in entries for Paper, Velocity, Folia, and friends.

```bash
prexorctl catalog list
```

You'll see entries like:

```
PLATFORM    VERSION   ARTEFACT
paper       1.21.4    paper-1.21.4-build-NNN.jar
paper       1.21.1    paper-1.21.1-build-NNN.jar
velocity    3.4.0     velocity-3.4.0.jar
folia       1.21.4    folia-1.21.4-build-NNN.jar
```

Pick the latest stable Paper. We'll wire it into a `lobby` group below.

## Step 2 — Create a group

A **group** is a logical collection of instances that share configuration:
platform, templates, scaling rules, port range, env. It's the closest thing
PrexorCloud has to a Kubernetes Deployment.

```bash
prexorctl group create lobby \
    --platform paper \
    --version 1.21.4 \
    --min 1 \
    --max 3 \
    --port-range 25600-25699 \
    --memory 1024
```

What just happened:

- The controller wrote a `GroupConfig` to MongoDB (`groups` collection).
- It registered the group with the scheduler. Within one scheduler tick
  (~5s), it'll notice the group is below `min` and start placing instances.

You can also create the group declaratively:

```bash
prexorctl group apply -f lobby.yml
```

Where `lobby.yml` looks like:

```yaml
name: lobby
platform: paper
version: "1.21.4"
scaling:
  mode: STATIC
  min: 1
  max: 3
ports: { from: 25600, to: 25699 }
resources: { memoryMB: 1024 }
```

## Step 3 — Watch the instance come up

Open a second terminal and stream events:

```bash
prexorctl events follow --filter instance
```

You'll see the lifecycle in real time:

```
INSTANCE_SCHEDULED   lobby-1  node-1
INSTANCE_PREPARING   lobby-1  template chain materialised
INSTANCE_STARTING    lobby-1  jvm spawned (pid 12345)
INSTANCE_RUNNING     lobby-1  ready in 4.1s
```

Behind the scenes:

1. **Scheduler** noticed `lobby` had 0 instances and `min=1`. Picked
   `node-1` via the weighted node selector (capacity, affinity, spread).
2. **Composition planner** generated a plan with the template-chain hashes,
   runtime jar reference, and a fresh per-instance plugin token.
3. Controller sent a `Start` gRPC frame to the daemon on `node-1`.
4. Daemon materialised the template chain (`base → base-paper → lobby`),
   layered the Paper jar, and spawned the JVM via `ProcessBuilder`.
5. The bundled cloud-plugin booted, exchanged its plugin token for an
   authenticated REST session, and reported `RUNNING`.

If you'd rather watch in the dashboard, open
`https://<host>:8080/instances` — the same event stream drives the UI.

## Step 4 — Connect a Minecraft client

The instance is bound to the daemon's port range. To find its address:

```bash
prexorctl instance describe lobby-1
```

You'll see something like:

```
INSTANCE        lobby-1
NODE            node-1   (10.0.0.5)
PORT            25600
STATE           RUNNING
PLATFORM        paper 1.21.4
```

Connect from a Paper-compatible client to `<node-ip>:25600`. You're in.

For production you want this fronted by a Velocity or Bungee proxy — see
[Your First Network](/getting-started/your-first-network/) for the lobby +
proxy setup.

## Step 5 — Scale, drain, delete

```bash
# Scale up
prexorctl group scale lobby --target 3

# See instances spread across nodes
prexorctl instance list --group lobby

# Drain one instance gracefully (players migrate away first)
prexorctl instance stop lobby-2

# Tear the group down
prexorctl group delete lobby
```

Scaling triggers the scheduler immediately. Instances spread across
available nodes per the weighted selector — by default it favors nodes with
more free capacity and fewer instances of the same group (anti-affinity).

`group delete` is idempotent: instances are stopped gracefully, the group
record is removed from MongoDB, and any in-flight scaling is cancelled.

## What can go wrong

| Symptom | Likely cause |
|---|---|
| Instance stuck in `SCHEDULED` | No daemon has free capacity, or all daemons are draining. `prexorctl node list` shows why. |
| Instance crashes immediately | Catalog version mismatch or bad template. Look at `prexorctl crash list --group lobby` for the classified exit. |
| Group reports `paused` reason `crash-loop` | The crash-loop detector tripped (default: 3 crashes in 60s). Fix the underlying issue, then `prexorctl group resume lobby`. |
| `events follow` shows nothing | You're filtering too aggressively. Drop `--filter` to see everything. |

## Next up

- **[Core Concepts](/getting-started/core-concepts/)** — the orientation
  reading: groups vs instances vs templates vs nodes vs daemons vs modules.
- **[Your First Network](/getting-started/your-first-network/)** — proxy +
  lobby + game-mode in 30 minutes, end-to-end.
- **[Templates](/concepts/groups-instances-templates/)** — how the layered
  template chain works and how to author your own.
