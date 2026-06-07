---
title: Quickstart (10 min)
description: From an empty cluster to a running Paper instance in about ten minutes — log in, create a group, watch it schedule, connect, scale, and tear down.
---

You have a Controller running and at least one Daemon connected
([Installation](/getting-started/installation/)). This page takes you from an
empty cluster to a running Paper Instance, then scales, drains, and deletes it.
Every command below is a real `prexorctl` command verified against the CLI
source.

## Before you start

- Controller reachable (default `http://localhost:8080`).
- At least one Daemon `ONLINE` in `prexorctl node list`.
- A catalog entry for the Paper version you want (the Controller ships a
  default catalog; see [Step 2](#step-2-confirm-a-platform-version-in-the-catalog)).
- About 1 GB free RAM on the Daemon host for a Paper Instance.

prexorctl gates every cluster command until it has a controller context. Before
you log in, only `setup`, `login`, `logout`, `version`, `help`, `completion`,
`context`, and `cluster` work; anything else prints:

```
no cluster connected — run 'prexorctl setup' to install a component, or 'prexorctl login' to link this CLI to an existing controller
```

## Step 1 — Log in

`prexorctl login` opens a form for the Controller URL, username, and password.
If a context already has the URL, only username and password are asked.

```bash
prexorctl login
```

```
Sign in to PrexorCloud
Enter your controller URL and credentials below.

Controller URL  http://localhost:8080
Username        admin
Password        ••••••••

✓ Logged in to http://localhost:8080 as admin
```

The CLI POSTs to `/api/v1/auth/login`, stores the returned bearer token in the
active context, and saves the config. Confirm the cluster:

```bash
prexorctl status
```

```
PrexorCloud  v1.1.0  •  cluster localhost:8080  •  logged in as (authenticated)

CLUSTER                       NODES                INSTANCES
● HEALTHY  0 groups 0 players  1 online             0 running

LIVE METRICS
  …
GROUPS
  (none)
```

If `status` errors with `not authenticated`, your token is missing — rerun
`prexorctl login`. You can also point a single command at a different cluster
with `--controller <url>` / `--token <token>`, or `--context <name>`.

## Step 2 — Confirm a platform version in the catalog

The **catalog** maps a platform + version to a download URL and an optional
`sha256`. A Group provisions its server JAR from a catalog version, so the
version you name in Step 3 must exist in the catalog.

There is no `prexorctl catalog` subcommand. The catalog lives behind REST at
`/api/v1/catalog`. List it with the bearer token from your saved context:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/catalog | jq '.[] | {platform, version, recommended}'
```

```json
{ "platform": "PAPER", "version": "1.21.4", "recommended": true }
{ "platform": "PAPER", "version": "1.21.1", "recommended": false }
{ "platform": "VELOCITY", "version": "3.4.0", "recommended": true }
```

Each entry is a `CatalogEntry`: `platform`, `category` (`SERVER` or `PROXY`),
`configFormat`, `version`, `downloadUrl`, `sha256`, `recommended`. Platform
names are upper-cased by the Controller (`PAPER`, `VELOCITY`, `FOLIA`,
`FABRIC`, `NEOFORGE`, `BUNGEECORD`, `GEYSER`).

Adding a version is also REST-only and needs the `CATALOG_MANAGE` permission:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"version":"1.21.4","downloadUrl":"https://.../paper-1.21.4.jar","sha256":"…"}' \
  http://localhost:8080/api/v1/catalog/paper/versions
```

Pick a `recommended` Paper version. This guide uses `1.21.4`.

## Step 3 — Create a group

A **Group** is a scalable set of Instances that share one configuration:
platform, version, templates, scaling rules, port range, memory, env. It is the
closest analog to a Kubernetes Deployment.

```bash
prexorctl group create \
  --name survival-lobby \
  --platform paper \
  --platform-version 1.21.4 \
  --min 1 \
  --max 3 \
  --memory 1024 \
  --port-start 30000 \
  --port-end 30100
```

```
✓ Group 'survival-lobby' created
```

`--name` and `--platform` are required. The other flags and their defaults
(from the CLI):

| Flag | Default | Maps to (`GroupConfig`) |
|---|---|---|
| `--name` | — (required) | `name` — alphanumeric, hyphen, underscore, 1–64 chars |
| `--platform` | — (required) | `platform` (upper-cased server-side) |
| `--platform-version` | `""` | `platformVersion` — must match a catalog entry |
| `--template` | none | `templates` (ordered layers; repeatable) |
| `--scaling-mode` | `DYNAMIC` | `scalingMode` — `STATIC`, `DYNAMIC`, or `MANUAL` |
| `--min` | `1` | `minInstances` |
| `--max` | `10` | `maxInstances` |
| `--memory` | `1024` | `memoryMb` (MB per Instance) |
| `--routing` | `LOWEST_PLAYERS` | routing strategy |
| `--port-start` | `30000` | port range start |
| `--port-end` | `30100` | port range end |

The CLI POSTs to `/api/v1/groups`. `jarFile` defaults to `server.jar`. The
Controller writes the `GroupConfig`, persists it, and registers it with the
scheduler. Unset `GroupConfig` fields fall back to record defaults —
`maxPlayers` 100, `scaleUpThreshold` 0.8, `startupTimeoutSeconds` 120,
`shutdownGraceSeconds` 30, `updateStrategy` `ROLLING`.

### Scaling modes

The mode you pick decides who controls the Instance count:

- **`STATIC`** — the scheduler holds the Group at `minInstances`. Use this for
  a fixed lobby.
- **`DYNAMIC`** — the scheduler scales between `minInstances` and
  `maxInstances` on load (`scaleUpThreshold`, `scaleDownAfterSeconds`,
  `scaleCooldownSeconds`). Default.
- **`MANUAL`** — the scheduler holds the current count; you add Instances with
  `instance start`.

For a single predictable lobby, `STATIC` with `--min 1` is the simplest choice:

```bash
prexorctl group create --name survival-lobby --platform paper \
  --platform-version 1.21.4 --scaling-mode STATIC --min 1 --max 1 --memory 1024
```

## Step 4 — Watch the instance come up

The scheduler evaluates on a fixed tick — `scheduler.evaluationIntervalSeconds`,
default **15s**. Within one tick it sees the Group below `minInstances` and
places an Instance. Watch the Group:

```bash
prexorctl group info survival-lobby
```

This opens an interactive view: config, scaling, templates, recent events, and
the Instance table. Press `↵` on an Instance to attach its console, `d` to
drain (graceful stop), `r` to restart (force-stop; the scheduler respawns it).

Prefer a flat list? Use the Instance table directly:

```bash
prexorctl instance list --group survival-lobby
```

```
ID                  GROUP           NODE      STATE     PORT    PLAYERS  UPTIME
survival-lobby-1    survival-lobby  node-1    RUNNING   30000   0        4s
```

An Instance moves through these states (`InstanceState`):

| State | Meaning |
|---|---|
| `SCHEDULED` | Placed on a node; Daemon not yet preparing |
| `PREPARING` | Daemon materializing the template chain and JAR |
| `STARTING` | JVM spawned, not yet ready |
| `RUNNING` | Accepting players |
| `DRAINING` | Still serving, no new players (graceful stop) |
| `STOPPING` | Shutting down |
| `STOPPED` | Clean terminal state |
| `CRASHED` | Non-zero exit; recorded as a crash report |

What happens behind the table:

1. The scheduler notices `survival-lobby` is below `min` and picks a node.
2. It sends a start frame to the Daemon over gRPC.
3. The Daemon materializes the template chain, layers the Paper JAR resolved
   from the catalog, and spawns the JVM.
4. The bundled cloud-plugin boots and the Instance reports `RUNNING`.

To watch Controller activity while it schedules, tail logs in a second
terminal:

```bash
prexorctl logs controller --follow
```

(`prexorctl logs --follow` with no subcommand tails the Controller too.)

## Step 5 — Connect a Minecraft client

Find the Instance's node and port:

```bash
prexorctl instance info survival-lobby-1
```

```
survival-lobby-1   ● RUNNING   group survival-lobby • node node-1
Started at 2026-06-07T… — uptime 1m4s

INSTANCE
  port     30000
  players  0
  memory   1024 MB
  uptime   1m4s
```

Connect a Paper-compatible client to `<node-ip>:30000`. Get the node IP from
`prexorctl node info node-1`.

Need to run a server command? Send one without attaching:

```bash
prexorctl instance exec survival-lobby-1 say hello from prexorctl
```

Or attach the live console (`Ctrl-Q` to detach; type to send commands):

```bash
prexorctl instance console survival-lobby-1
```

For production, front the lobby with a Velocity proxy — see
[Your first network](/getting-started/your-first-network/).

## Step 6 — Scale, drain, delete

There is no `group scale` command. You change the desired count by updating the
Group's bounds, or schedule one-off Instances directly.

**Raise the ceiling** (and floor, for `STATIC`):

```bash
prexorctl group update survival-lobby --max 3 --min 3
```

```
✓ Group 'survival-lobby' updated
```

`group update` PATCHes only the flags you pass (`--min`, `--max`, `--memory`,
`--routing`, `--scaling-mode`); omitted fields are unchanged. The scheduler
reconciles to the new bounds on its next tick.

**Schedule extra Instances on demand** (1–50, clamped):

```bash
prexorctl instance start survival-lobby
```

```
✓ 1 instance(s) scheduled in group survival-lobby
```

**Drain one Instance** — graceful stop:

```bash
prexorctl instance stop survival-lobby-2
```

Force-kill immediately instead:

```bash
prexorctl instance stop survival-lobby-2 --force
```

**Put the Group in maintenance** (drains and stops new placement):

```bash
prexorctl group maintenance survival-lobby on
prexorctl group maintenance survival-lobby off
```

**Tear the Group down.** This prompts for confirmation and stops every running
Instance:

```bash
prexorctl group delete survival-lobby
```

```
Delete group 'survival-lobby'?
This will stop all running instances in the group. This action cannot be undone.

✓ Group 'survival-lobby' deleted
```

## What can go wrong

| Symptom | Likely cause |
|---|---|
| `no cluster connected` | No controller context. Run `prexorctl login` or pass `--controller`. |
| `not authenticated` | Token missing or expired. Rerun `prexorctl login`. |
| Instance stuck in `SCHEDULED` | No node has free capacity, or all nodes are `DRAINING`. Check `prexorctl node list`. |
| Instance never leaves `PREPARING` | Template materialization or catalog JAR download failing on the Daemon. Check `prexorctl logs daemon <node-id>`. |
| Group create rejected `400` | Invalid name (must be alphanumeric / `-` / `_`, 1–64 chars) or invalid config. |
| Instance lands in `CRASHED` | Bad version or template. Inspect with `prexorctl crash list --group survival-lobby` then `prexorctl crash info <id>`. |
| Repeated crashes | The crash-loop detector trips at **3 crashes in 300s** (defaults `crashLoopThreshold` / `crashLoopWindowSeconds`). Fix the cause, then start fresh Instances. |

Inspect a crash:

```bash
prexorctl crash list --group survival-lobby
prexorctl crash info <crash-id>
```

`crash info` shows the exit code, classification, and the last log lines.

## Next steps

- **[Core concepts](/getting-started/core-concepts/)** — Controller, Daemon,
  Group, Instance, Template, Network, Module, Plugin.
- **[Your first network](/getting-started/your-first-network/)** — proxy +
  lobby + game-mode, end to end.
- **[Groups, instances, and templates](/concepts/groups-instances-templates/)**
  — the layered template chain and how to author your own.
