---
title: Group + Instance Commands
description: prexorctl group, instance, and crash — group create/scale/delete, instance start/stop/exec/console, and crash report inspection.
---

A **group** is the scheduling unit (think Deployment); an **instance**
is a single Minecraft server process scheduled into a group. These
commands cover the CRUD on groups, day-to-day instance ops, and the
crash-report viewer for post-mortems.

## What you'll learn

- How to declare a group with scaling, routing, and template layers.
- How to start, stop, exec into, and tail the console of an instance.
- How to find the most recent crashes and inspect their tail logs.

## `prexorctl group`

### `group list`

```bash
prexorctl group list
prexorctl group list --filter lobby --sort players
prexorctl group list --watch
```

Flags:

- `--filter <substr>` — substring match against group name.
- `--sort <key>` — `name` (default), `players`, or `instances`.
- `--watch` — re-render every 2s; press `Ctrl-C` to exit.

### `group info`

```bash
prexorctl group info <name>
```

Shows three side-by-side cards (config, scaling, template) plus the
running-instance table for the group.

### `group create`

```bash
prexorctl group create \
    --name lobby \
    --platform paper \
    --platform-version 1.21.4 \
    --template lobby-base --template lobby-prod \
    --scaling-mode DYNAMIC \
    --min 1 --max 4 \
    --memory 2048 \
    --routing LOWEST_PLAYERS \
    --port-start 30000 --port-end 30100
```

Flags:

- `--name` *(required)* — group identifier.
- `--platform` *(required)* — `paper`, `velocity`, etc.
- `--platform-version` — platform version string (e.g. `1.21.4`).
- `--template <name>` — template layers, applied in order. May be repeated.
- `--scaling-mode` — `STATIC`, `DYNAMIC`, or `MANUAL`. Default `DYNAMIC`.
- `--min`, `--max` — instance bounds. Defaults `1` and `10`.
- `--memory` — heap MB per instance. Default `1024`.
- `--routing` — routing strategy (e.g. `LOWEST_PLAYERS`).
- `--port-start`, `--port-end` — port range to allocate from.

### `group update`

```bash
prexorctl group update <name> --max 12 --memory 3072
```

Patches scaling, routing, or memory in place. Only flags you pass are
sent; the rest stay as configured.

### `group delete`

```bash
prexorctl group delete <name>
```

Stops every running instance in the group, then deletes it. Prompts
for confirmation; pair with `--json` and a wrapping script if you need
non-interactive deletes.

### `group maintenance`

```bash
prexorctl group maintenance <name> on
prexorctl group maintenance <name> off
```

Toggles maintenance mode. While on, the group accepts no new players
and the scheduler skips it.

## `prexorctl instance`

Aliased to `inst`. Targets one or many running instances.

### `instance list`

```bash
prexorctl instance list
prexorctl instance list --group lobby --state RUNNING
```

Flags:

- `--group <name>` — filter by group.
- `--node <id>` — filter by node.
- `--state <state>` — filter by lifecycle state (`SCHEDULED`,
  `PREPARING`, `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`,
  `CRASHED`, `DRAINING`).

### `instance info`

```bash
prexorctl instance info <id>
```

Single-instance card with port, player count, memory, and uptime.

### `instance start`

```bash
prexorctl instance start <group>
```

Asks the controller to schedule a new instance in the named group. The
group's scaling mode and current state determine how many instances
are actually started; the response carries the count.

### `instance stop`

```bash
prexorctl instance stop <id>
prexorctl instance stop <id> --force
```

Flags:

- `--force` — `SIGKILL` immediately rather than the daemon's graceful
  shutdown sequence.

### `instance exec`

```bash
prexorctl instance exec <id> say Hello world
prexorctl instance exec <id> stop
```

Sends a command string to the server's stdin via the daemon. Multi-word
commands are joined with spaces.

### `instance console`

```bash
prexorctl instance console <id>
```

Live SSE stream of the instance's stdout/stderr, level-coloured. Press
`Ctrl-C` to detach (the instance keeps running).

## `prexorctl crash`

Crash reports are emitted automatically by the daemon when an instance
exits unexpectedly.

### `crash list`

```bash
prexorctl crash list
prexorctl crash list --group lobby --since 2026-05-01T00:00:00Z
prexorctl crash list --node node-1 --json
```

Flags:

- `--group <name>` — filter by group.
- `--node <id>` — filter by node.
- `--since <iso8601>` — show crashes at or after the timestamp.

### `crash info`

```bash
prexorctl crash info <id>
```

Shows context (instance, group, node, exit code, uptime,
classification) and the tail of the instance's log right before exit.

## Scripting example

Restart every running instance in a group:

```bash
for id in $(prexorctl instance list --group lobby --state RUNNING --json \
              | jq -r '.[].id'); do
  prexorctl instance stop "$id"
done
prexorctl instance start lobby
```

## Next up

- [Templates](/reference/cli/templates/) — managing the layers `group create`
  references with `--template`.
- [Group concepts](/concepts/groups-instances-templates/) — scaling modes, routing
  strategies, maintenance behaviour.
- [Instance lifecycle](/concepts/groups-instances-templates/) — what each
  state means.
