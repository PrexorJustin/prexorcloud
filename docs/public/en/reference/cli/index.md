---
title: CLI Reference
description: prexorctl command-line reference — global flags, command map, environment variables, and exit codes for every operator workflow.
---

`prexorctl` is the operator command-line for the PrexorCloud control plane.
Every action available through the dashboard — and a handful that aren't —
is exposed as a subcommand here. The CLI talks to the controller's REST
API at `/api/v1`; auth state and the resolved controller URL live in
`~/.config/prexorctl/config.json`.

## What you'll learn

- The global flags every subcommand inherits.
- How the CLI is laid out into eight reference pages (one per surface).
- Environment variables for headless / scripted use.
- The exit-code contract for CI pipelines.

## Global flags

These are inherited by every subcommand. They override `config.json` and
`PREXOR_*` environment variables.

| Flag | Short | Description |
|---|---|---|
| `--json` | `-j` | Emit machine-readable JSON instead of the styled TUI. Implies `--no-color`. |
| `--controller <url>` | `-c` | Override the configured controller URL (e.g. `https://controller:8080`). |
| `--token <token>` | `-t` | Override the stored auth token. |
| `--no-color` | | Disable ANSI colors. Auto-set when stdout isn't a TTY. |
| `--ascii` | | Use ASCII glyphs only — no unicode box drawing or sparklines. For terminals without nerd-font / wide-char support. |
| `--verbose` | `-v` | Print the underlying HTTP request and response. |

## Command map

| Page | Commands |
|---|---|
| [Setup + Auth](/reference/cli/setup-and-auth/) | `setup`, `login`, `logout`, `config`, `token` |
| [Cluster](/reference/cli/cluster/) | `node`, `status`, `version` |
| [Group + Instance](/reference/cli/group-and-instance/) | `group`, `instance`, `crash` |
| [Templates](/reference/cli/templates/) | `template` |
| [Users + Roles](/reference/cli/users-and-roles/) | `user`, `role` |
| [Modules + Plugins](/reference/cli/modules-and-plugins/) | `module`, `plugin` |
| [Utilities](/reference/cli/utilities/) | `logs`, `diagnostics`, `deploy`, `backup`, `restore`, `completion`, scripting patterns |

## Environment variables

| Variable | Effect |
|---|---|
| `PREXOR_OUTPUT=json` | Same as passing `--json` on every invocation. |
| `PREXOR_CONTROLLER` | Default controller URL. Equivalent to `config set controller`. |
| `PREXOR_TOKEN` | Default auth token. Equivalent to `config set token`. |

`PREXOR_CONTROLLER` and `PREXOR_TOKEN` are the supported way to drive the
CLI from CI — they sidestep the on-disk config entirely.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | Generic error (validation, IO, parsing) |
| `2` | Authentication failure (`401`) |
| `3` | Authorization failure (`403`) |
| `4` | Resource not found (`404`) |
| `5` | Conflict / invalid state (`409`, `422`) |
| `6` | Network / unreachable controller |

API-mapped codes (`2`–`5`) are derived from the controller's HTTP response
so scripts can branch on them without parsing JSON.

## Next up

- [Setup + Auth](/reference/cli/setup-and-auth/) — first contact with a fresh controller.
- [Cluster](/reference/cli/cluster/) — node and version commands.
- [Utilities](/reference/cli/utilities/) — `--output json` patterns and CI snippets.
