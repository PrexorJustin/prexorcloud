---
title: Group + Instance Commands
description: prexorctl group, instance, and crash subcommands — flags, request bodies, JSON output, and API endpoints for group CRUD, instance lifecycle, and crash inspection.
---

A **group** is the scheduling unit (analogous to a Deployment); an **instance**
is a single Minecraft server process scheduled into a group. This page documents
every `prexorctl group`, `prexorctl instance`, and `prexorctl crash` subcommand:
its arguments, flags, the request it issues, and its JSON shape.

The CLI talks to the controller REST API. The endpoints each command calls are
listed per subcommand. Instances are served under `/api/v1/services` (the
internal name for a scheduled server process).

## Global flags

These persistent flags apply to every subcommand below (defined on the root
command):

| Flag | Short | Default | Effect |
| --- | --- | --- | --- |
| `--json` | `-j` | `false` | Emit the raw API response as JSON instead of the rendered table/card. |
| `--controller <url>` | `-c` | (active context) | Override the controller URL for this invocation. |
| `--token <token>` | `-t` | (active context) | Override the auth token. |
| `--context <name>` | | (active context) | Override the active context for this invocation. |
| `--no-color` | | `false` | Disable colored output. |
| `--ascii` | | `false` | Use ASCII glyphs only (no unicode box drawing, sparklines). |
| `--verbose` | `-v` | `false` | Show HTTP request/response details. |

Setting the environment variable `PREXOR_OUTPUT=json` is equivalent to passing
`--json` on every command.

All subcommands require authentication; an unauthenticated invocation fails
before any request is sent.

## `prexorctl group`

Manage server groups. CRUD plus maintenance toggling.

### `group list`

```bash
prexorctl group list
prexorctl group list --filter lobby --sort players
prexorctl group list --watch
```

`GET /api/v1/groups` → `[]GroupResponse`.

Flags:

- `--filter <substr>` — case-insensitive substring match against group name.
  Default empty (no filter). Applied client-side after fetch.
- `--sort <key>` — `name` (default), `players`, or `instances`. Any other value
  falls back to `name`. `players` sorts by `totalPlayers` descending;
  `instances` by `runningInstances` descending; `name` ascending.
- `--watch` — clears the screen and re-renders every 2s. Exit with `Ctrl-C`.

`--filter`, `--sort`, and `--watch` are ignored when `--json` is set; the raw
unfiltered, unsorted list is printed.

Rendered columns: `GROUP`, `TYPE` (`GAME` or `STATIC`), `STATUS`, `INSTANCES`
(`runningInstances/maxInstances`), `PLAYERS`, `VERSION` (`platform-platformVersion`),
`UPDATED`. The footer counts groups by status. Status is derived client-side:
`DRAIN` if `maintenance` is true, `DOWN` if `runningInstances == 0 && minInstances > 0`,
otherwise `UP`.

JSON example:

```bash
prexorctl group list --json
```

```json
[
  {
    "name": "lobby",
    "platform": "paper",
    "platformVersion": "1.21.4",
    "scalingMode": "DYNAMIC",
    "minInstances": 1,
    "maxInstances": 4,
    "maxPlayers": 100,
    "runningInstances": 2,
    "totalPlayers": 37,
    "maintenance": false,
    "static": false
  }
]
```

### `group info`

```bash
prexorctl group info <name>
```

`GET /api/v1/groups/<name>` → group object (full document, rendered as a map).
With `--json`, prints the raw object. Argument count: exactly 1.

Without `--json`, opens an interactive view with three cards (config, scaling,
template) and the running-instance table for the group. Instances are fetched
from `GET /api/v1/services?group=<name>`. In the interactive view:

- `d` drains an instance → `POST /api/v1/services/<id>/stop` (graceful).
- `r` restarts an instance → `POST /api/v1/services/<id>/force-stop`; the group
  scheduler respawns it (the controller exposes no per-instance restart verb).
- `↵` on an instance attaches its console (see `instance console`).

Card fields read from the group object: `platform`, `platformVersion`,
`memoryMb`, `routing`, `scalingMode`, `minInstances`, `maxInstances`,
`maxPlayers`, `templates`, `updateStrategy`, `parent`.

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

`POST /api/v1/groups` → created group object.

Flags:

| Flag | Type | Default | Required | Body field |
| --- | --- | --- | --- | --- |
| `--name` | string | `""` | yes | `name` |
| `--platform` | string | `""` | yes | `platform` |
| `--platform-version` | string | `""` | no | `platformVersion` |
| `--template` | string (repeatable) | `nil` | no | `templates` (ordered) |
| `--scaling-mode` | string | `DYNAMIC` | no | `scalingMode` |
| `--min` | int | `1` | no | `minInstances` |
| `--max` | int | `10` | no | `maxInstances` |
| `--memory` | int (MB) | `1024` | no | `memoryMb` |
| `--routing` | string | `LOWEST_PLAYERS` | no | `routing` |
| `--port-start` | int | `30000` | no | `portRange.start` |
| `--port-end` | int | `30100` | no | `portRange.end` |

`--template` may be repeated; layers apply in the order given. The request body
always sets `jarFile` to `"server.jar"` (not exposed as a flag). The full body:

```json
{
  "name": "lobby",
  "platform": "paper",
  "platformVersion": "1.21.4",
  "jarFile": "server.jar",
  "templates": ["lobby-base", "lobby-prod"],
  "scalingMode": "DYNAMIC",
  "minInstances": 1,
  "maxInstances": 4,
  "memoryMb": 2048,
  "routing": "LOWEST_PLAYERS",
  "portRange": { "start": 30000, "end": 30100 }
}
```

`--scaling-mode` is documented as one of `STATIC`, `DYNAMIC`, `MANUAL`; the CLI
does not validate the value before sending. On success without `--json`, prints
`Group '<name>' created`. With `--json`, prints the created object.

### `group update`

```bash
prexorctl group update <name> --max 12 --memory 3072
```

`PATCH /api/v1/groups/<name>` → updated group object. Argument count: exactly 1.

Only flags you explicitly pass are included in the request body (detected via
cobra's `Changed`); unset flags are omitted, leaving the existing value
untouched. Supported flags:

| Flag | Type | Default | Body field |
| --- | --- | --- | --- |
| `--min` | int | `0` | `minInstances` |
| `--max` | int | `0` | `maxInstances` |
| `--memory` | int (MB) | `0` | `memoryMb` |
| `--routing` | string | `""` | `routing` |
| `--scaling-mode` | string | `""` | `scalingMode` |

Note that the defaults shown are placeholder values; because only changed flags
are sent, the defaults are never transmitted. `update` does not patch
`platform`, `platformVersion`, `templates`, `maxPlayers`, or `portRange`; and
maintenance is toggled separately (see `group maintenance`). On success without
`--json`, prints `Group '<name>' updated`.

Example sending only memory:

```bash
prexorctl group update lobby --memory 4096
# PATCH body: {"memoryMb": 4096}
```

### `group delete`

```bash
prexorctl group delete <name>
```

`DELETE /api/v1/groups/<name>`. Argument count: exactly 1.

Prompts interactively for confirmation:
`Delete group '<name>'?` with the description
`This will stop all running instances in the group. This action cannot be undone.`
Answering no prints `Cancelled.` and exits 0 without deleting. On confirm,
deletes and prints `Group '<name>' deleted`.

The confirmation is a TUI prompt and is not suppressed by `--json`. For
non-interactive deletes, drive the DELETE endpoint directly or pipe a `yes`
response.

### `group maintenance`

```bash
prexorctl group maintenance <name> on
prexorctl group maintenance <name> off
```

`PATCH /api/v1/groups/<name>` with body `{"maintenance": <bool>}`. Argument
count: exactly 2 (`<name>` and the on/off token).

The second argument is parsed truthy: `on`, `true`, or `1` enable maintenance;
any other value (including `off`) disables it. On success without `--json`,
prints `Maintenance enabled for group '<name>'` or `Maintenance disabled for
group '<name>'`. While enabled, the scheduler skips the group and it accepts no
new players.

## `prexorctl instance`

Aliased to `inst`. Manage server instances. Instances live under the
`/api/v1/services` endpoint family.

### `instance list`

```bash
prexorctl instance list
prexorctl instance list --group lobby --state RUNNING
prexorctl instance list --node node-1 --json
```

`GET /api/v1/services` with query params `group`, `node`, `state` → `[]InstanceResponse`.

Flags (all filters are passed straight to the API as query params; empty values
are omitted by the client):

- `--group <name>` — filter by group.
- `--node <id>` — filter by node.
- `--state <state>` — filter by lifecycle state. The CLI does not validate the
  value; the controller's instance states include `SCHEDULED`, `PREPARING`,
  `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, `CRASHED`, `DRAINING`.

Rendered columns: `ID`, `GROUP`, `NODE`, `STATE`, `PORT`, `PLAYERS`, `UPTIME`.
The footer counts `RUNNING` instances versus all others.

JSON example:

```bash
prexorctl instance list --group lobby --json
```

```json
[
  {
    "id": "lobby-7fdc",
    "group": "lobby",
    "node": "node-1",
    "state": "RUNNING",
    "port": 30001,
    "playerCount": 18,
    "uptimeMs": 5400000,
    "startedAt": "2026-06-07T09:00:00Z",
    "deploymentRevision": 3
  }
]
```

### `instance info`

```bash
prexorctl instance info <id>
```

`GET /api/v1/services/<id>` → instance object. Argument count: exactly 1. With
`--json`, prints the raw object. The rendered card shows `port`, `playerCount`,
`memoryMb`, and `uptimeMs` (formatted), plus a header line with `state`, `group`,
`node`, and `startedAt`.

### `instance start`

```bash
prexorctl instance start <group>
```

`POST /api/v1/groups/<group>/start` → result object. Argument count: exactly 1.
Note the argument is a **group name**, not an instance id.

Asks the controller to schedule new instances in the group. How many actually
start depends on the group's scaling mode and current state; the response
carries a `count` field. Without `--json`, prints
`<count> instance(s) scheduled in group <group>` (defaulting the displayed count
to `1` when the response omits it).

### `instance stop`

```bash
prexorctl instance stop <id>
prexorctl instance stop <id> --force
```

Argument count: exactly 1 (an instance id).

- Without `--force`: `POST /api/v1/services/<id>/stop` (graceful daemon shutdown
  sequence). Prints `Instance <id> stopping`.
- With `--force`: `POST /api/v1/services/<id>/force-stop` (`SIGKILL`
  immediately). Prints `Instance <id> force-stopped`.

Flags:

- `--force` — bool, default `false`. Route the request to `/force-stop`.

### `instance exec`

```bash
prexorctl instance exec <id> say Hello world
prexorctl instance exec <id> stop
```

`POST /api/v1/services/<id>/command` with body `{"command": "<joined args>"}`.
Argument count: minimum 2 (the id plus at least one command token). Tokens after
the id are joined with single spaces into one command string and written to the
server's stdin via the daemon. Prints `Sent to <id>: <command>`.

### `instance console`

```bash
prexorctl instance console <id>
```

Live console attach. Argument count: exactly 1.

Opens an SSE stream from `GET /api/v1/services/<id>/console` and renders
stdout/stderr in real time with level coloring (`ERROR` red, `WARN` amber,
`INFO` cyan; unmatched lines pass through). The view accepts input: a typed line
is sent as a command via `POST /api/v1/services/<id>/command`. Before streaming,
a best-effort `GET /api/v1/services/<id>` populates the header (node, group,
state).

Detach with `Ctrl-Q`; the instance keeps running. (The same console view is
reachable via `↵` in `group info`.)

## `prexorctl crash`

View crash reports. The daemon emits a crash report when an instance exits
unexpectedly.

### `crash list`

```bash
prexorctl crash list
prexorctl crash list --group lobby --since 2026-05-01T00:00:00Z
prexorctl crash list --node node-1 --json
```

`GET /api/v1/crashes` with query params `group`, `node`, and `from` →
`[]CrashResponse`.

Flags:

- `--group <name>` — filter by group (query param `group`).
- `--node <id>` — filter by node (query param `node`).
- `--since <iso8601>` — show crashes at or after the timestamp. Mapped to the
  `from` query parameter.

Rendered columns: `ID`, `INSTANCE`, `GROUP`, `NODE`, `EXIT` (red when nonzero),
`CLASS` (classification), `CRASHED AT`, `UPTIME`.

### `crash info`

```bash
prexorctl crash info <id>
prexorctl crash info <id> --json
prexorctl crash info <id> --share --expiry 1d
```

`GET /api/v1/crashes/<id>` → crash object. Argument count: exactly 1.

Renders a `CONTEXT` card (`instanceId`, `group`, `node`, `exitCode`, `uptimeMs`)
and the `LAST LOG LINES` block from `logTail`. With `--json`, prints the raw
object.

Share flags (upload a redacted copy to the configured paste service and print
the link; routes to `POST /api/v1/crashes/<id>/share`):

| Flag | Type | Default | Effect |
| --- | --- | --- | --- |
| `--share` | bool | `false` | Enable sharing; short-circuits normal output and posts the share request. |
| `--expiry <preset>` | string | `""` | Paste expiry preset: `1h`, `1d`, `30d`, or `never`. |
| `--public` | bool | `false` | Mark the paste public (overrides `share.defaultPrivate=true`). |
| `--burn-after-read` | bool | `false` | Destroy the paste on first read. |

The crash JSON shape (`CrashResponse`):

```json
{
  "id": "crash-1a2b",
  "instanceId": "lobby-7fdc",
  "group": "lobby",
  "nodeId": "node-1",
  "exitCode": 134,
  "classification": "OOM",
  "logTail": ["...", "..."],
  "uptimeMs": 5400000,
  "timestamp": "2026-06-07T09:30:00Z"
}
```

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
  strategies, maintenance behavior.
- [Instance lifecycle](/concepts/groups-instances-templates/) — what each
  state means.
