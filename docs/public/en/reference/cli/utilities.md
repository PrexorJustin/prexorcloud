---
title: Utilities — status, version, diagnostics, logs, crash
description: prexorctl status, version, logs, diagnostics, and crash commands — every flag, the JSON output contract, share links, exit codes, and CI patterns.
---

Operator commands for observing a cluster: the at-a-glance overview, the
version handshake, log tailing, the diagnostics bundle, and crash reports.
Every command honours the global `--json` contract and the `PREXOR_*`
environment overrides documented below.

Per-command auto-generated pages live under
[`_generated/`](./_generated/); this page summarizes the surface and links
to them. The signatures here are taken from `cli/cmd/status.go`,
`version.go`, `logs.go`, `diagnostics.go`, and `crash.go`.

## Global flags

These persistent flags are defined on the root command and apply to every
subcommand on this page.

| Flag | Short | Default | Effect |
|---|---|---|---|
| `--json` | `-j` | `false` | Emit machine-readable JSON. Implies `--no-color` and never prompts. |
| `--controller <url>` | `-c` | from context | Override the controller URL for this invocation. |
| `--token <token>` | `-t` | from context | Override the auth token for this invocation. |
| `--context <name>` | | active context | Use a named context instead of the active one. |
| `--no-color` | | `false` | Disable ANSI color. Auto-enabled when stdout is not a TTY. |
| `--ascii` | | `false` | ASCII glyphs only — no box-drawing or sparklines. |
| `--verbose` | `-v` | `false` | Print HTTP request/response details. |

## `prexorctl status`

Cluster overview: cluster health, node/instance/player counts, live metric
sparklines, and a per-group table. Resolves the controller, then fetches
`GET /api/v1/overview`, `GET /api/v1/groups`, and `GET /api/v1/system/version`
behind a single spinner.

```bash
prexorctl status
```

Takes no arguments and no command-specific flags. Requires an authenticated
context (errors otherwise).

JSON mode returns the raw `GET /api/v1/overview` body byte-for-byte — the
legacy shape, kept stable for scripts:

```bash
prexorctl status --json
```

```json
{
  "nodeCount": 3,
  "instanceCount": 10,
  "playerCount": 42,
  "groupCount": 5
}
```

The four count fields (`nodeCount`, `instanceCount`, `playerCount`,
`groupCount`) are the contract. The TPS and memory sparklines in the
human-readable view are synthetic placeholders generated client-side; they
are not present in the JSON and are not served by `/api/v1/overview`.

Generated page: [status](./_generated/status/).

## `prexorctl version`

Print the CLI version (injected at build time via `ldflags`), the Go
toolchain, OS/arch, and — when a controller context is configured — the
controller version from `GET /api/v1/system/version`.

```bash
prexorctl version
```

The controller card is best-effort: it is shown only when a client can be
built and a token is present, and silently omitted if the version call
fails. `version` is in the allow-list of commands that run before any
cluster is linked, so it works on a fresh install.

JSON mode:

```bash
prexorctl version --json
```

```json
{
  "cli": "1.1.0",
  "go": "go1.23.0",
  "os": "linux",
  "arch": "amd64",
  "controller_version": "1.1.0",
  "controller_buildDate": "2026-06-01T00:00:00Z"
}
```

Fields `cli`, `go`, `os`, and `arch` are always present. Each key from the
controller's version response is prefixed with `controller_` and merged in;
the controller keys are only included when the version call succeeds.

Generated page: [version](./_generated/version/).

## `prexorctl logs`

Stream or page recent log records from PrexorCloud components over the
controller's REST + SSE surface. With no subcommand, `logs` proxies to
`logs controller`.

```bash
prexorctl logs
prexorctl logs --follow
prexorctl logs controller --level WARN --tail 500
prexorctl logs daemon node-fra-1 --follow
```

### Persistent flags

Defined on `logs` and inherited by both subcommands:

| Flag | Default | Effect |
|---|---|---|
| `--follow` | `false` | Open the live tail view and stream new records via SSE. |
| `--tail <n>` | `200` | Number of recent records to print/replay before streaming. |
| `--level <level>` | `INFO` | Minimum level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`. |
| `--logger <prefix>` | `""` | Only include records from loggers with this prefix. |

### `prexorctl logs controller`

Recent controller logs from `GET /api/v1/system/logs`. Takes no positional
arguments.

```bash
prexorctl logs controller --level ERROR --logger me.prexorjustin.cloud.scheduler
```

With `--follow`, it replays the fetched buffer and then attaches to
`GET /api/v1/system/logs/stream` (ticket-authenticated SSE). In the live
view: `/` filters, `p` pauses, `j`/`k` scroll, `Ctrl-C` quits.

In non-follow mode `--json` returns the full `logsResponse`:

```bash
prexorctl logs controller --tail 50 --json
```

```json
{
  "records": [
    {
      "seq": 41,
      "ts": 1749312000000,
      "level": "INFO",
      "logger": "me.prexorjustin.cloud.Controller",
      "thread": "main",
      "message": "controller ready",
      "mdc": {}
    }
  ],
  "size": 1,
  "capacity": 2000,
  "level": "INFO",
  "logger": ""
}
```

Each record carries `seq`, `ts` (epoch millis), `level`, `logger`,
`thread`, `message`, and optionally `throwable` and `mdc`. `--json` is
ignored when `--follow` is set.

Generated page: [logs-controller](./_generated/logs-controller/).

### `prexorctl logs daemon <node-id>`

Recent daemon logs from a connected node via
`GET /api/v1/nodes/<node-id>/logs`. Requires exactly one argument, the node
ID.

```bash
prexorctl logs daemon node-fra-1 --level DEBUG --tail 300
prexorctl logs daemon node-fra-1 --follow
```

Same flags, output shape, and SSE follow behaviour as the controller
subcommand; the stream attaches to `GET /api/v1/nodes/<node-id>/logs/stream`.

Generated page: [logs-daemon](./_generated/logs-daemon/).

## `prexorctl diagnostics`

Collect a redacted diagnostics bundle for support and postmortems. Aliased
as `diag`. The `diagnostics` parent has no run behaviour of its own — use
the `bundle` subcommand.

### `prexorctl diagnostics bundle`

Fetch `GET /api/v1/system/diagnostics` (and optionally recent logs) and
write a gzipped tarball. Takes no positional arguments.

```bash
prexorctl diagnostics bundle
prexorctl diagnostics bundle --out ./diag.tar.gz --log-lines 1000
```

```text
✓ Diagnostics bundle written: ./prexorctl-diag-20260607-153012.tar.gz (84.2 KB)
```

The archive contains these entries (sensitive fields — JWT secrets, admin
password, URI credentials — are redacted server-side before transport):

| Entry | Source |
|---|---|
| `manifest.json` | Bundle metadata + controller version/ID. |
| `readiness.json` | Readiness probe snapshot. |
| `overview.json` | Cluster counts (nodes, instances, players, groups). |
| `settings.json` | Non-sensitive runtime settings. |
| `config.json` | Controller config with secrets redacted. |
| `redis.json` | Redis-protocol keyspace summary. |
| `leases.json` | Distributed lease holders. |
| `logs.txt` | Recent controller log records (best-effort; omitted if `--log-lines 0`). |

#### Flags

| Flag | Short | Default | Effect |
|---|---|---|---|
| `--out <path>` | `-o` | `./prexorctl-diag-<timestamp>.tar.gz` | Output path for the archive. |
| `--log-lines <n>` | | `500` | Recent `DEBUG`-level log lines to include; `0` skips the `logs.txt` entry. |

When `--log-lines > 0`, logs are fetched at level `DEBUG`; a fetch failure
prints a warning and the bundle is written without `logs.txt`. Records older
than the controller's in-memory log buffer capacity cannot be retrieved
through this surface — scrape on-disk logs separately for older windows.

`diagnostics bundle` also accepts the [share flags](#share-flags). With
`--share`, the bundle is uploaded to the configured paste service via
`POST /api/v1/system/diagnostics/share`. Combine `--share --out <path>` to
upload and keep a local copy.

Generated page: [diagnostics-bundle](./_generated/diagnostics-bundle/).

## `prexorctl crash`

View crash reports. The `crash` parent has no run behaviour; use `list` or
`info`.

### `prexorctl crash list`

List crash reports from `GET /api/v1/crashes`. Takes no positional
arguments.

```bash
prexorctl crash list
prexorctl crash list --group survival-lobby --node node-fra-1 --since 2026-06-01T00:00:00Z
```

#### Flags

| Flag | Default | Effect |
|---|---|---|
| `--group <name>` | `""` | Filter by group (query `group`). |
| `--node <id>` | `""` | Filter by node (query `node`). |
| `--since <iso8601>` | `""` | Crashes since this ISO-8601 timestamp (query `from`). |

The human-readable table columns are `ID`, `INSTANCE`, `GROUP`, `NODE`,
`EXIT`, `CLASS`, `CRASHED AT`, `UPTIME`. `--json` returns the raw crash
array.

```bash
prexorctl crash list --json
```

```json
[
  {
    "id": "crash-7f3a",
    "instanceId": "survival-lobby-2",
    "group": "survival-lobby",
    "node": "node-fra-1",
    "exitCode": 137,
    "classification": "OOM_KILLED",
    "crashedAt": "2026-06-07T14:51:02Z",
    "uptimeMs": 3920000
  }
]
```

Generated page: [crash](./_generated/crash/).

### `prexorctl crash info <id>`

Show one crash report from `GET /api/v1/crashes/<id>`. Requires exactly one
argument, the crash ID. Renders a context card (instance, group, node, exit
code, uptime) and, when present, the captured `logTail` lines.

```bash
prexorctl crash info crash-7f3a
prexorctl crash info crash-7f3a --json
```

`crash info` accepts the [share flags](#share-flags); `--share` uploads the
redacted report via `POST /api/v1/crashes/<id>/share`.

## Share flags

`diagnostics bundle`, `crash info`, and the `logs` subcommands accept a
uniform share-flag set that uploads a redacted copy to the controller's
configured paste service and prints the link.

| Flag | Default | Effect |
|---|---|---|
| `--share` | `false` | Upload a redacted copy and print the share URL. |
| `--expiry <preset>` | `""` | Paste expiry: `1h`, `1d`, `30d`, or `never`. |
| `--public` | `false` | Mark the paste public (overrides `share.defaultPrivate=true`). |
| `--burn-after-read` | `false` | Destroy the paste on first read. |

```bash
prexorctl diagnostics bundle --share --expiry 1d
prexorctl logs controller --level ERROR --tail 200 --share --burn-after-read
```

```text
✓ Shared controller logs → https://paste.internal/p/a1b2c3
expires: 2026-06-08T15:30:12Z
burn-after-read enabled — link can only be opened once
revoke:  https://paste.internal/p/a1b2c3/delete?token=…
```

`--share` cannot be combined with `--follow` on `logs` commands; doing so
returns `--share cannot be combined with --follow`. If sharing is not
configured, the controller returns `409 SHARE_DISABLED`, surfaced as
`sharing is not configured on this controller (share.enabled=false)`. An
upstream paste failure (`502 PASTE_UPSTREAM_ERROR`) surfaces as
`paste service unreachable: …`.

## JSON output contract

Every command on this page accepts the global `-j` / `--json` flag and emits
one of:

- A JSON object — `status`, `version`, `crash info`, and the non-follow
  `logs` responses.
- A JSON array — `crash list`.

`--json` implies `--no-color` and never prompts. Setting the environment
variable `PREXOR_OUTPUT=json` flips the default to JSON for every command at
process start — useful in CI shells. `--json` is ignored when `--follow` is
active on `logs`.

## Environment overrides

These environment variables are read by the CLI's config resolver and take
effect without an on-disk context — the path used in CI.

| Variable | Overrides |
|---|---|
| `PREXOR_CONTROLLER` | Controller URL (same as `--controller`). |
| `PREXOR_TOKEN` | Auth token (same as `--token`). |
| `PREXOR_CONTEXT` | Active context name (same as `--context`). |
| `PREXOR_OUTPUT=json` | Forces `--json` for all commands. |

## Exit codes

The root handler maps API errors to exit codes via `APIError.ExitCode()`.

| Code | Constant | Meaning |
|---|---|---|
| `0` | `ExitSuccess` | Success. |
| `1` | `ExitError` | Generic error (default for any non-API failure). |
| `2` | `ExitAuthError` | `401` — not authenticated. |
| `3` | `ExitForbidden` | `403` — forbidden. |
| `4` | `ExitNotFound` | `404` — resource missing. |
| `5` | `ExitConnError` | Connection error. |

A typed `ExitCodeError` lets a command request a specific code directly; for
example, `module doctor` returns `2` when it completes with non-fatal
warnings. Any other API status falls through to `ExitError` (`1`).

## CI patterns

Drive the CLI from a CI job without touching the on-disk config:

```bash
export PREXOR_CONTROLLER=https://controller.internal:8080
export PREXOR_TOKEN=$CI_PREXOR_TOKEN
export PREXOR_OUTPUT=json

prexorctl status \
  | jq -e '.nodeCount > 0' \
  || { echo "no nodes online"; exit 1; }
```

Capture a diagnostics bundle on a failed deploy and attach the share link:

```bash
prexorctl diagnostics bundle --share --expiry 30d --json \
  | jq -r '.url'
```

Fail a pipeline when any `ERROR` records appeared since the last build:

```bash
COUNT=$(prexorctl logs controller --level ERROR --tail 1000 --json \
  | jq '[.records[] | select(.level == "ERROR")] | length')
[ "$COUNT" -eq 0 ] || { echo "$COUNT controller errors"; exit 1; }
```

## Next

- [Setup + Auth](/reference/cli/setup-and-auth/) — contexts, login, and the
  `PREXOR_*` overrides in full.
- [Operations runbook](/operations/production-checklist/) — playbooks built on
  these commands.
