---
title: CLI reference
description: prexorctl overview — global flags, config and contexts, the auth model, the command map, and JSON output. The operator command-line for the PrexorCloud control plane.
---

`prexorctl` is the operator command-line for the PrexorCloud control plane.
Every subcommand talks to a controller's REST API under `/api/v1`. Auth state
and the resolved controller URL live in a per-user config file at
`~/.prexorcloud/config.yml`, organized as named contexts (one controller +
token pair each).

This page documents the surface every subcommand shares: global flags, the
config and context model, the auth model, output formats, and the exit-code
contract. Per-command detail lives on the linked reference pages.

## What you'll learn

- The global flags every subcommand inherits, with defaults.
- How `config.yml`, contexts, flags, and environment variables resolve.
- The auth model — JWT bearer tokens, `login`, `logout`, and the pre-link gate.
- The command map across the reference pages.
- Output formats: the styled TUI versus `--json`.
- The exit-code contract for scripts.

## Global flags

Defined as persistent flags on the root command in `cli/cmd/root.go`. Every
subcommand inherits them. Flags override `config.yml` values and `PREXOR_*`
environment variables.

| Flag | Short | Default | Description |
|---|---|---|---|
| `--json` | `-j` | `false` | Emit machine-readable JSON to stdout instead of the styled TUI. Implies no color. |
| `--controller <url>` | `-c` | `""` | Override the resolved controller URL (e.g. `https://controller:8080`). |
| `--token <token>` | `-t` | `""` | Override the resolved auth token. |
| `--context <name>` | | `""` | Override the active context for this one invocation. |
| `--no-color` | | `false` | Disable ANSI color. Auto-enabled when stdout is not a character device (a pipe or file). |
| `--ascii` | | `false` | Use ASCII glyphs only — no unicode box drawing, sparklines, or wide-char output. For terminals without nerd-font support. |
| `--verbose` | `-v` | `false` | Print each underlying HTTP request and response line (`→ GET …` / `← 200 …`). |

`--no-color` is forced on whenever `--json` is set, and whenever stdout is not
a TTY (the root `PersistentPreRunE` calls `os.Stdout.Stat()` and checks
`os.ModeCharDevice`).

```bash
prexorctl status --controller https://controller.internal:8080 --token "$TOKEN" --json
```

## Config and contexts

### File location

| Function | Path |
|---|---|
| `config.Dir()` | `~/.prexorcloud` |
| `config.Path()` | `~/.prexorcloud/config.yml` |

The directory is created with mode `0700` and the file written with mode
`0600` on every save (`saveTo` in `cli/internal/config/config.go`). Tokens are
stored as plaintext JWTs protected by those file permissions, per ADR 27. When
`setup` runs under `sudo`, it writes the config back to the invoking user's
home and chowns it to that user (`SaveAs(home, uid, gid)`).

### File shape

A context is a `(controller, token)` pair. The config holds a map of named
contexts, a pointer to the active one, and an optional accent preference.

```yaml
currentContext: prod
contexts:
  prod:
    controller: https://controller.prod.internal:8080
    token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
  staging:
    controller: https://controller.staging.internal:8080
    token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
accent: purple
```

Field reference (`config.Config` / `config.Context`):

| Field | YAML key | Type | Notes |
|---|---|---|---|
| `CurrentContext` | `currentContext` | `string` | Name of the active context. Empty until the first `login`, `context add`, or `setup`. |
| `Contexts` | `contexts` | `map[string]*Context` | Named contexts. |
| `Context.Controller` | `controller` | `string` | Controller base URL. Trailing slashes are trimmed by the API client. |
| `Context.Token` | `token` | `string` | JWT bearer token. Omitted from the file when empty. |
| `Accent` | `accent` | `string` | Brand accent: `purple` (default), `cyan`, `green`, `amber`. |

The loader migrates a legacy v1 flat config (top-level `controller:` / `token:`)
into a single `default` context transparently on read (`rawConfig.migrate()`).

### Managing contexts

The `context` command group manages contexts. Use multiple contexts to point
one CLI install at several clusters (prod, staging) and switch without
reauthenticating.

| Command | Args | Flags | Effect |
|---|---|---|---|
| `context list` (alias `ls`) | — | — | List contexts; the active one is marked `*`. |
| `context current` | — | — | Print the active context name; errors if none is set. |
| `context use <name>` | `<name>` (exactly 1) | — | Set the active context. Errors on unknown name. |
| `context add <name>` | `<name>` (exactly 1) | `--controller` (required), `--token` | Add a context. Validates the URL. Sets it active if no current context exists. |
| `context remove <name>` (aliases `rm`, `delete`) | `<name>` (exactly 1) | `--force` | Remove a context. Removing the active one requires `--force` and clears `currentContext`. |

```bash
prexorctl context add prod --controller https://controller.prod.internal:8080
prexorctl context use prod
prexorctl context current
# prod
prexorctl context list
```

```bash
prexorctl context list --json
```
```json
[
  {
    "controller": "https://controller.prod.internal:8080",
    "current": true,
    "name": "prod"
  }
]
```

### Resolution order

For a given invocation the controller URL and token are resolved
independently. Flags win, then environment, then the selected context.

Controller URL — `Config.Resolve(flagController, flagContext)`:

1. `--controller` flag
2. `PREXOR_CONTROLLER` environment variable
3. `Controller` of the selected context

Token — `Config.ResolveToken(flagToken, flagContext)`:

1. `--token` flag
2. `PREXOR_TOKEN` environment variable
3. `Token` of the selected context

The *selected context* itself — `Config.SelectedContextName(flagContext)`:

1. `--context` flag
2. `PREXOR_CONTEXT` environment variable
3. stored `currentContext`

`PREXOR_CONTROLLER` and `PREXOR_TOKEN` sidestep the on-disk config entirely,
which is the supported way to drive the CLI from CI.

```bash
export PREXOR_CONTROLLER=https://controller.internal:8080
export PREXOR_TOKEN="$CI_PREXOR_TOKEN"
prexorctl status --json
```

## Auth model

The CLI authenticates to the controller with a JWT bearer token. The API
client sets `Authorization: Bearer <token>` on every request when a token is
resolved (`cli/internal/api/client.go`).

### Logging in

`login` posts credentials to `POST /api/v1/auth/login` and stores the returned
token in the active context (creating a `default` context if none exists, via
`SetCurrentAuth`). With no controller configured, the interactive form also
prompts for the controller URL.

```bash
prexorctl login
```
```text
Sign in to PrexorCloud
Enter your controller URL and credentials below.

Controller URL  http://localhost:8080
Username        admin
Password        ••••••••
✓ Logged in to http://localhost:8080 as admin
```

The username and password are not stored — only the resulting token is.

### Logging out

`logout` clears the token on the selected context (controller URL is kept) and
saves the config.

```bash
prexorctl logout
# ✓ Logged out
```

### How commands require auth

Command implementations build a client through one of two root helpers:

- `newClient()` — resolves the controller URL and token; errors only if no
  controller URL is configured. Used by commands that can run unauthenticated
  (for example `version`, which falls back to local-only output).
- `requireAuth()` — calls `newClient()`, then errors with
  `not authenticated -- run 'prexorctl login'` if the resolved token is empty.
  Used by every command that hits an authenticated endpoint.

### The pre-link gate

Before the first context exists, the root `PersistentPreRunE` blocks most
commands with:

```text
no cluster connected — run 'prexorctl setup' to install a component, or
'prexorctl login' to link this CLI to an existing controller
```

The gate is bypassed when a context resolves, when `--controller` / `--context`
(or `PREXOR_CONTROLLER`) is supplied, for purely local commands annotated
`local-only` (for example `module new` / `module scaffold`, which generate code
on disk), and for the allowlisted top-level commands: `setup`, `login`,
`logout`, `version`, `help`, `completion`, `context`, and `cluster`.

### Node join tokens are separate

Do not confuse the auth token above with node *join* tokens. The `token`
command group manages credentials a daemon uses to join the cluster, via
`/api/v1/admin/tokens`:

| Command | Args | Flags | Endpoint |
|---|---|---|---|
| `token create` | — | `--node`, `--ttl` (default `1h`) | `POST /api/v1/admin/tokens` |
| `token list` | — | — | `GET /api/v1/admin/tokens` |
| `token revoke <id>` | `<id>` (exactly 1) | — | `DELETE /api/v1/admin/tokens/<id>` |

## Command map

Top-level commands registered on the root command, grouped by reference page.

| Page | Commands |
|---|---|
| [Setup + auth](/reference/cli/setup-and-auth/) | `setup`, `login`, `logout`, `config`, `context`, `token` |
| [Cluster](/reference/cli/cluster/) | `node`, `status`, `version` |
| [Group + instance](/reference/cli/group-and-instance/) | `group`, `instance`, `crash` |
| [Templates](/reference/cli/templates/) | `template` |
| [Users + roles](/reference/cli/users-and-roles/) | `user`, `role` |
| [Modules + plugins](/reference/cli/modules-and-plugins/) | `module`, `plugin` |
| [Utilities](/reference/cli/utilities/) | `logs`, `diagnostics`, `deploy`, `backup`, `restore`, `share`, `stop`, `completion` |

Run `prexorctl <command> --help` for the local flags and subcommands of any
command. The styled help groups output into `USAGE`, `COMMANDS`, `FLAGS`, and
`GLOBAL FLAGS` sections.

## Output formats

Every command renders one of two ways.

### Styled TUI (default)

Tables, cards, status pills, and sparklines, themed by the configured accent.
Color is dropped when stdout is not a TTY, when `--no-color` is set, or under
`--json`. `--ascii` swaps unicode glyphs for ASCII.

```bash
prexorctl version
```
```text
PrexorCloud CLI
Operator command-line for the PrexorCloud control plane.

┌ CLI ─────────────────────────────────────────┐
│ version    1.1.0                              │
│ go         go1.25.0                           │
│ os/arch    linux/amd64                        │
└───────────────────────────────────────────────┘
```

### JSON (`--json` / `-j`)

JSON mode short-circuits all styled output and prints indented JSON (two-space
indent) through the single `theme.PrintJSON` path. Use it for scripting; pipe
to `jq`. It is enabled per-invocation with `--json`, or globally by exporting
`PREXOR_OUTPUT=json` (read once at startup in `cli/cmd/root.go`).

```bash
prexorctl version --json
```
```json
{
  "arch": "amd64",
  "cli": "1.1.0",
  "controller_version": "1.1.0",
  "go": "go1.25.0",
  "os": "linux"
}
```

The controller fields appear only when a token is configured and the
`GET /api/v1/system/version` call succeeds.

## Verbose mode and retries

`--verbose` prints each request and response line. Idempotent verbs (`GET`,
`HEAD`) are retried up to 3 attempts total on connection errors and on retryable
5xx / 408 / 429 statuses, with exponential backoff (200 ms base, 2 s cap, ±20%
jitter). `POST`, `PATCH`, and `DELETE` are never retried automatically.

```bash
prexorctl status --verbose
```
```text
→ GET https://controller.internal:8080/api/v1/system/status
← 200 200 OK
```

A retry line reads `→ GET … (retry 1/2)`.

## Environment variables

| Variable | Effect |
|---|---|
| `PREXOR_OUTPUT=json` | Equivalent to passing `--json` on every invocation. Read once at startup. |
| `PREXOR_CONTROLLER` | Default controller URL. Overrides the stored context, beaten only by `--controller`. |
| `PREXOR_TOKEN` | Default auth token. Overrides the stored context, beaten only by `--token`. |
| `PREXOR_CONTEXT` | Selected context name. Overrides `currentContext`, beaten only by `--context`. |

`PREXOR_CONTROLLER` + `PREXOR_TOKEN` drive the CLI in CI without writing a
config file at all.

## Exit codes

Commands return an error to the root `Execute()` handler, which prints it and
maps it to a process exit code. Codes 2–4 come from `APIError.ExitCode()`,
derived from the controller's HTTP status, so scripts can branch without
parsing JSON.

| Code | Meaning | Source |
|---|---|---|
| `0` | Success | no error returned |
| `1` | Generic failure (validation, IO, parse, network/connection error) | default for any error |
| `2` | Authentication failure | API `401` |
| `3` | Authorization failure | API `403` |
| `4` | Resource not found | API `404` |

Commands may also return a typed `ExitCodeError{Code, Message}` to request a
specific code — for example `module doctor` exits `2` when it finds warnings
only. Any HTTP status outside `401` / `403` / `404` (including `409` and `422`)
maps to exit code `1`.

```bash
prexorctl group get nonexistent-group
echo "exit: $?"
# exit: 4
```

## Next

- [Setup + auth](/reference/cli/setup-and-auth/) — first contact with a fresh controller.
- [Cluster](/reference/cli/cluster/) — `node`, `status`, `version`.
- [Utilities](/reference/cli/utilities/) — `--json` scripting patterns and CI snippets.
