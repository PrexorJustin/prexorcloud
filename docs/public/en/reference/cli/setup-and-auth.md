---
title: Setup and auth
description: prexorctl setup, login, logout, context, and config — install a controller, daemon, or dashboard, authenticate an operator, and manage per-cluster contexts.
---

These commands cover first contact with PrexorCloud: install a component on a
fresh host, exchange credentials for a session token, and store one or more
named `(controller URL + token)` contexts so a single CLI install can drive
multiple clusters.

State lives in `~/.prexorcloud/config.yml` (mode `0600`, directory `0700`). The
file holds a map of named contexts, a pointer to the active one, and the brand
`accent` preference. Pre-context flat configs (`controller:`/`token:` at the
root) are migrated transparently into a `default` context on first load.

## What you'll learn

- The browser-wizard and TTY install paths exposed by `prexorctl setup`.
- How `prexorctl login` exchanges credentials for a session token.
- How contexts resolve and how `--controller`, `--token`, `--context`, and the
  `PREXOR_*` environment variables override the stored values.
- How to issue and revoke node join tokens for the daemon bootstrap flow.

## Configuration resolution

Every controller-bound command resolves its target in this precedence order:

| Value | Precedence (highest first) |
|---|---|
| Controller URL | `--controller`/`-c` flag → `PREXOR_CONTROLLER` env → active context's `controller` |
| Auth token | `--token`/`-t` flag → `PREXOR_TOKEN` env → active context's `token` |
| Active context | `--context` flag → `PREXOR_CONTEXT` env → stored `currentContext` |

The config file path is fixed at `~/.prexorcloud/config.yml`. There is no flag
to relocate it; `setup` running under `sudo` writes to the invoking user's home
and `chown`s the result back to that user.

### Pre-link gate

Until at least one context exists (or a controller is supplied via flag/env),
only `setup`, `login`, `logout`, `version`, `help`, `completion`, `context`, and
`cluster` run. Any other command fails with:

```text
no cluster connected — run 'prexorctl setup' to install a component, or 'prexorctl login' to link this CLI to an existing controller
```

## `prexorctl setup`

Install and configure the Controller, Daemon, or Dashboard. Opens a browser
wizard by default (loopback `127.0.0.1:9100`) and falls back to TTY prompts on
headless hosts. The CLI auto-links to the controller after install — no separate
`prexorctl login` on the same host.

```bash
prexorctl setup
sudo prexorctl setup --no-browser --component controller --install-mode native --non-interactive
sudo prexorctl setup --no-browser \
    --component daemon \
    --install-mode native \
    --daemon-controller-host controller.example.com \
    --daemon-controller-grpc-port 9090 \
    --daemon-join-token prxn_xxx \
    --non-interactive
```

Native installs that provision packages or register systemd units must run as
root. Compose mode runs as the invoking user. Server-side install targets only
exist on Linux; on macOS/Windows `setup` (TTY mode) refuses and redirects to
`login`.

### Wizard vs TTY selection

`setup` hands off to the browser wizard unless `--non-interactive` is set or
`--browser=false`/`--no-browser` is passed. Headless hosts — no `DISPLAY`,
`WAYLAND_DISPLAY`, or `BROWSER`; inside a container (`/.dockerenv` present); or
with `CI`/`PREXOR_NO_BROWSER` set — fall back to TTY automatically. Inside an SSH
session (`SSH_CONNECTION` with four fields) the wizard is preferred and
`--ssh-tunnel` is auto-enabled.

### Browser-wizard flags

- `--browser` (default `true`) — open a loopback wizard in the default browser.
- `--no-browser` (default `false`) — force the TTY prompt flow. Shorthand for
  `--browser=false`.
- `--browser-addr <host:port>` — wizard listen address. Empty defaults to
  `127.0.0.1:9100`, or `0.0.0.0:9100` when `--public` is set.
- `--browser-open` (default `true`) — try to launch the system browser at the
  wizard URL. Disable on headless hosts.
- `--ssh-tunnel` (default `false`) — bind `127.0.0.1` (no TLS, no browser
  warning) and print the laptop-side `ssh -L` command. Auto-enabled when
  `SSH_CONNECTION` is set on a headless box. Overrides `--public`.
- `--public` (default `false`) — bind a non-loopback address with TLS + token
  auth so a remote browser can connect. Triggers a self-signed-cert warning
  unless fronted by a trusted certificate, and exposes the wizard port for the
  setup window.
- `--public-host <host>` — hostname or IP printed in the wizard URL under
  `--public`. Defaults to the first non-loopback IPv4 detected.
- `--browser-idle-timeout <duration>` — auto-shutdown after this much
  inactivity. Empty (`0`) = 30m default.
- `--manage-firewall` (default `true`) — in `--public` mode, open the wizard's
  port via `ufw`/`firewall-cmd`/`iptables` and remove the rule on shutdown.

### Common install flags

- `--non-interactive` (default `false`) — run without prompts using flags and
  defaults; every required value must be supplied.
- `--component <controller|daemon|dashboard>` — what to install (TTY mode reads
  this; the wizard asks).
- `--install-mode <native|compose>` — systemd + distro packages, or a generated
  Compose project.
- `--service-mode <prompt|enable|disable>` — whether to register and enable the
  systemd unit at the end.
- `--startup-validation-mode <prompt|enable|disable>` — whether to run the
  controller's startup validation after native controller service registration.

### Controller flags

- `--controller-install-dir <path>` — install directory. Default
  `/opt/prexorcloud/controller`.
- `--controller-mongo-mode <local|remote>` — MongoDB source.
- `--controller-mongo-uri <uri>` — MongoDB URI for remote mode.
- `--controller-redis-mode <local|remote>` — Redis source.
- `--controller-redis-uri <uri>` — Redis URI for remote mode.
- `--controller-http-port <port>` — controller HTTP port.
- `--controller-grpc-port <port>` — controller gRPC port.
- `--controller-cors-origin <origin>` — dashboard CORS origin.

### Daemon flags

- `--daemon-install-dir <path>` — install directory. Default
  `/opt/prexorcloud/daemon`.
- `--daemon-node-id <id>` — node ID for this daemon.
- `--daemon-controller-host <host>` — controller host.
- `--daemon-controller-grpc-port <port>` — controller gRPC port.
- `--daemon-controller-http-port <port>` — controller HTTP port for join-token
  redemption. Default `8080`.
- `--daemon-join-token <token>` — join token to redeem (see
  [`prexorctl token create`](#token-create)).

### Dashboard flags

- `--dashboard-install-dir <path>` — install directory. Default
  `/opt/prexorcloud/dashboard`.
- `--dashboard-public-url <url>` — public URL the dashboard is served at, e.g.
  `https://dash.example.com`.
- `--dashboard-serve-mode <nginx|systemd-nginx|behind-existing-proxy>` — how to
  serve the bundle. Default `nginx`.
- `--dashboard-tls-mode <none|letsencrypt|custom|terminated-upstream>` — TLS
  mode. Default `none`.
- `--dashboard-tls-email <email>` — ACME registration email (`letsencrypt` mode
  only).
- `--dashboard-controller-url <url>` — controller base URL, e.g.
  `https://controller.example.com:8080`.
- `--dashboard-admin-user <user>` — controller admin username. Default `admin`.
- `--dashboard-admin-password <pass>` — controller admin password, used once to
  register the CORS origin, then discarded.
- `--dashboard-listen-port <port>` — local port the dashboard listens on.
  Default `80`.

## `prexorctl login`

Exchange username + password for a controller-issued session token. `POST`s to
`/api/v1/auth/login` and stores the returned token plus the resolved controller
URL in the active context (creating a `default` context if none exists).

```bash
prexorctl login
prexorctl login --controller https://controller.example.com:8080
```

The form prompts for any field not already configured. If a controller URL is
already resolvable (from the active context, `--controller`, or
`PREXOR_CONTROLLER`), only username and password are asked. On success:

```text
✓ Logged in to https://controller.example.com:8080 as admin
```

Flags: none beyond the global flags. Username and password are entered through
the prompt only; the password field uses masked echo.

## `prexorctl logout`

Clear the stored token on the active context (resolved with `--context`). The
controller URL is left in place so you can re-`login` without retyping it. The
context entry itself is not removed.

```bash
prexorctl logout
```

```text
✓ Logged out
```

## `prexorctl context`

Manage named `(controller URL + token)` contexts. The global `--context` flag
(or `PREXOR_CONTEXT`) overrides the stored `currentContext` for a single
invocation.

### `context list`

Alias: `ls`. List configured contexts. The active one is marked with `*`.

```bash
prexorctl context list
```

```text
Listing contexts

   NAME      CONTROLLER
 * prod      https://controller.example.com:8080
   staging   https://staging.example.com:8080

2 contexts
```

With `--json`, returns an array of `{ "name", "controller", "current" }`. When
no contexts exist, prints a warning pointing to `context add`.

### `context current`

Print the active context name. Errors if none is selected.

```bash
prexorctl context current
```

```text
prod
```

With `--json`, returns `{ "name": "prod" }`. Resolution honors `--context` and
`PREXOR_CONTEXT`.

### `context use <name>`

Set the active context. `<name>` is required (exactly one argument) and must
already exist; otherwise fails with `unknown context "<name>"`.

```bash
prexorctl context use staging
```

```text
✓ Switched to context "staging"
```

### `context add <name>`

Add a new context. `<name>` is required (exactly one argument). Fails if the
name already exists.

```bash
prexorctl context add prod --controller https://controller.example.com:8080
prexorctl context add staging --controller https://staging.example.com:8080 --token prx_xxx
```

Flags:

- `--controller <url>` — controller URL. Required; must start with `http://` or
  `https://`.
- `--token <token>` — auth token. Optional; obtain it later via
  `prexorctl context use <name>` followed by `prexorctl login`.

If no context was active, the newly added one becomes `currentContext`.

### `context remove <name>`

Aliases: `rm`, `delete`. Remove a context. `<name>` is required (exactly one
argument).

```bash
prexorctl context remove staging
prexorctl context remove prod --force
```

Flags:

- `--force` (default `false`) — remove the context even if it is the current
  one. Without it, removing the active context fails:
  `"prod" is the current context — pass --force to remove it`. When the active
  context is force-removed, `currentContext` is cleared.

## `prexorctl config`

Inspect or modify the on-disk config. Operates on the active context. Valid
keys: `controller`, `token`, `accent`.

### `config view`

Show the active context name, its controller URL, the masked token, the accent,
and the config file path.

```bash
prexorctl config view
```

```text
Configuration
Stored on disk at ~/.prexorcloud/config.yml

┌ CLI CONFIG ────────────────────────────────────────────┐
  context     prod
  controller  https://controller.example.com:8080
  token       prx_ab...cd12
  accent      purple
└────────────────────────────────────────────────────────┘
```

Tokens are masked: empty renders `(not set)`; values of 10 characters or fewer
render `***`; longer values show the first 6 and last 4 characters. When the
effective controller (after flag/env resolution) differs from the stored value,
a hint line reports it. With `--json`:

```json
{
  "context": "prod",
  "controller": "https://controller.example.com:8080",
  "token": "prx_ab...cd12",
  "configPath": "/home/op/.prexorcloud/config.yml"
}
```

### `config set <key> <value>`

Set a value on the active context (exactly two arguments). If no context is
active, a `default` context is created first.

```bash
prexorctl config set controller https://controller.example.com:8080
prexorctl config set token prx_xxx
prexorctl config set accent cyan
```

- `controller` — validated to start with `http://` or `https://`.
- `token` — masked in the success line.
- `accent` — brand accent family: `purple` (default), `cyan`, `green`, `amber`.

An unknown key fails with
`unknown config key: <key> (valid: controller, token, accent)`.

### `config unset <key>`

Clear a value on the active context (exactly one argument).

```bash
prexorctl config unset token
```

```text
✓ Unset token
```

Valid keys: `controller`, `token`, `accent`. Clearing `controller` or `token`
leaves the context entry in place with an empty field.

## `prexorctl token`

Manage node join tokens that the daemon bootstrap flow trades for an mTLS
certificate. All subcommands require an authenticated context; without one they
fail with `not authenticated -- run 'prexorctl login'`.

### `token create`

`POST`s to `/api/v1/admin/tokens` and prints the result.

```bash
prexorctl token create
prexorctl token create --node node-fra-1 --ttl 24h
```

```text
Join Token Created
  Token ID    tk_01H...
  Join Token  prxn_xxx
  Node ID     node-fra-1
  Expires At  2026-06-08T12:00:00Z
```

Flags:

- `--node <id>` — bind the token to a specific node ID. Optional; default empty
  (any node).
- `--ttl <duration>` — token time-to-live, e.g. `1h`, `24h`. Default `1h`.

The output includes the raw join token exactly once — store it; the controller
persists only its hash. With `--json`, the full server response is returned.

### `token list`

`GET`s `/api/v1/admin/tokens` and lists tokens by ID, node binding, expiry, and
status.

```bash
prexorctl token list
```

```text
Listing join tokens · controller.example.com

 TOKEN ID    NODE         EXPIRES AT             STATUS
 tk_01H...   node-fra-1   2026-06-08T12:00:00Z   ● ACTIVE

1 token
```

With `--json`, returns the raw array. The raw token value is never listed.

### `token revoke <id>`

`DELETE`s `/api/v1/admin/tokens/<id>` by token ID (exactly one argument), not
the raw token string.

```bash
prexorctl token revoke tk_01H...
```

```text
✓ Token tk_01H... revoked
```

Future bootstrap attempts using a revoked token are rejected by the controller.

## Global flags

These persistent flags apply to every command above:

- `--json`, `-j` — JSON output. Also implied by `PREXOR_OUTPUT=json`. Forces
  no-color.
- `--controller`, `-c <url>` — override the controller URL for this invocation.
- `--token`, `-t <token>` — override the auth token for this invocation.
- `--context <name>` — override the active context for this invocation.
- `--no-color` — disable colored output.
- `--ascii` — use ASCII glyphs only (no Unicode box drawing or sparklines).
- `--verbose`, `-v` — show HTTP request/response details.

## Next up

- [Cluster commands](/reference/cli/cluster/) — `node`, `status`, `version`.
- [Utilities](/reference/cli/utilities/) — `--json` output patterns and CI
  snippets.
- [Installation guide](/getting-started/installation/) — a full walkthrough that
  drives `setup` end-to-end.
