---
title: The prexorctl setup wizard
description: Install and link the PrexorCloud Controller, Daemon, and Dashboard with the browser-based prexorctl setup wizard — single-host lab or multi-VPS cluster, compose or native, with remote-VPS access modes.
---

`prexorctl setup` installs one PrexorCloud component at a time — Controller,
Daemon, or Dashboard — and links this CLI to the cluster as a side effect. By
default it opens a browser wizard bound to loopback; headless hosts fall back
to a TTY form. The same install pipeline (`cli/internal/setup`) runs behind
both UIs. The browser is presentation only.

This page covers the browser wizard end to end: how it picks UIs, the access
modes for remote VPSes, every install path (compose and native), the CLI-login
mode, the wire API the frontend drives, and worked single-host and multi-VPS
walkthroughs.

## What the command does

```sh
prexorctl setup
```

`setup` runs before any controller exists, so it overrides the CLI's normal
"you must be logged in" guard. It installs server-side components, which run on
Linux only; on macOS and Windows the binary ships for client verbs
(`prexorctl login`, `module install`, …) and `setup` refuses early with a
redirect to `prexorctl login`.

One run writes one install. Re-run `prexorctl setup` for the next component —
each run is a fresh process and a fresh wizard.

## UI selection: browser vs TTY

The wizard is the default. The decision logic (`browserSetupRequested` in
`cli/cmd/setup.go`) is:

| Condition | Result |
|---|---|
| `--no-browser` or `--non-interactive` | TTY / flag-driven, no wizard |
| `--public` or `--ssh-tunnel` set | Browser wizard (explicit remote override) |
| Not headless (local desktop) | Browser wizard, auto-opens default browser |
| Headless **and** inside an SSH session | Browser wizard in SSH-tunnel mode |
| Headless, no SSH | TTY fallback |

A host is "headless" (`isHeadless`) when any of these hold:

- `$CI` or `$PREXOR_NO_BROWSER` is set
- `/.dockerenv` exists (inside a container)
- Linux with no `$DISPLAY`, no `$WAYLAND_DISPLAY`, and no `$BROWSER`

An SSH session is detected when `$SSH_CONNECTION` carries the four fields
OpenSSH sets (`<client-ip> <client-port> <server-ip> <server-port>`).

Force the TTY flow anywhere with `--no-browser` (shorthand for
`--browser=false`).

## Access modes for the wizard server

The wizard binds an HTTP server on the host running `prexorctl`. How you reach
it from your browser depends on where that host is.

### Loopback (default)

```sh
prexorctl setup
```

Binds `127.0.0.1:9100`, plain HTTP, no auth. The OS keeps the port unreachable
from outside the host. `prexorctl` tries to launch your default browser at the
URL. Use this when `prexorctl` runs on the machine whose browser you'll use.

### SSH tunnel (recommended for remote VPSes)

```sh
prexorctl setup --ssh-tunnel
```

Binds loopback (no TLS, no browser warning), skips the local browser launch,
and prints the laptop-side command to run:

```
ssh -L 9100:127.0.0.1:9100 you@your-vps-host
```

Run that in a second terminal, then open the printed
`http://127.0.0.1:9100/...` URL in your laptop browser. The wizard traffic
rides your existing SSH connection.

This mode auto-enables when you're already inside an SSH session on a headless
box (`$SSH_CONNECTION` set, no local browser). The SSH user and server IP for
the printed command come from `$SUDO_USER`/`$USER` and `$SSH_CONNECTION`.
`--ssh-tunnel` overrides `--public`.

### Public bind (last resort)

```sh
prexorctl setup --public
```

Binds a non-loopback address (`0.0.0.0:9100` by default) and exposes setup
endpoints to the network for the setup window. Protections, all automatic:

- A 32-byte cryptographic token in the URL fragment (`#token=…`). The fragment
  never reaches the server in requests; the wizard JS reads it from
  `location.hash` and sends it as `Authorization: Bearer <token>`.
- An ephemeral self-signed TLS cert (the terminal prints its SHA-256
  fingerprint; expect a browser warning unless you front it with a trusted
  cert).
- Idle shutdown after the idle timeout (default 30m).
- Single-use: the wizard exits 60s after a successful install.
- Rate limiting: 10 failed token attempts from one IP → 60s lockout.

Binding a non-loopback address without `--public` is rejected with an error;
`--public` with a loopback address is also rejected. The wizard does not honor
`X-Forwarded-For` — it is not designed to sit behind a reverse proxy.

### Flags

| Flag | Default | Effect |
|---|---|---|
| `--browser` | `true` | Open the loopback wizard. `--browser=false` forces TTY. |
| `--no-browser` | `false` | Shorthand for `--browser=false`. |
| `--browser-addr` | `127.0.0.1:9100` (or `0.0.0.0:9100` with `--public`) | `host:port` the wizard binds. |
| `--browser-open` | `true` | Try to launch the system browser at the wizard URL. |
| `--ssh-tunnel` | `false` | No-TLS loopback bind + print the `ssh -L` command. Overrides `--public`. |
| `--public` | `false` | Non-loopback bind with TLS + token auth. |
| `--public-host` | first non-loopback IPv4 | Hostname/IP printed in the wizard URL under `--public`. |
| `--browser-idle-timeout` | `30m` | Auto-shutdown after this much inactivity. |
| `--manage-firewall` | `true` | In `--public` mode, open the port via ufw/firewall-cmd/iptables and remove the rule on shutdown. |

The wizard shuts down on any of: the frontend's `POST /api/exit`, a successful
install (single-use 60s timer), the idle timeout, or `Ctrl+C` in the terminal.

The install one-liner (`cli/install.sh`) launches the wizard automatically
after installing the binary unless you pass `--no-setup` (or set
`PREXORCTL_AUTO_SETUP=0`). When it detects an SSH session with no local
browser, it adds `--public --public-host <server-ip>` itself.

## First load: GET /api/info

On load the wizard reads `/api/info` to populate defaults and gate install
modes. Verified fields and defaults:

| Field | Value |
|---|---|
| `defaults.controllerHttpPort` | `8080` |
| `defaults.controllerGrpcPort` | `9090` |
| `defaults.controllerInstallDir` | `/opt/prexorcloud/controller` |
| `defaults.daemonInstallDir` | `/opt/prexorcloud/daemon` |
| `defaults.daemonControllerHost` | `127.0.0.1` |
| `defaults.daemonControllerGrpcPort` | `9090` |
| `defaults.dashboardInstallDir` | `/opt/prexorcloud/dashboard` |
| `defaults.dashboardListenPort` | `80` |
| `platform.os` / `platform.arch` | host `GOOS` / `GOARCH` |
| `platform.installSupported` | true on Linux; false elsewhere (with `installUnsupportedReason`) |
| `platform.nativeAllowed` | true on Linux **as root**; false otherwise (with `nativeReason`) |
| `features.cliLogin` | true when the CLI-login callback is wired (always true for the real `setup` command) |

When `installSupported` is false (non-Linux), every install card is disabled
and only CLI-login remains. When `nativeAllowed` is false (non-Linux, or not
root), the Docker-vs-Native toggle stays on Docker.

## Step 1: pick a mode

The first screen offers up to five cards. By purpose:

1. **Controller** — the control-plane component.
2. **Daemon** — a worker node.
3. **Dashboard** — the web UI + reverse proxy.
4. **CLI Login** — link this CLI to an existing controller (no install).

Install modes per component:

- **Docker (compose)** — always available on Linux. Writes a
  `docker-compose.yml` you bring up with `docker compose up -d`.
- **Native (systemd)** — requires Linux + root. Installs a JRE and any local
  storage packages, registers a systemd unit, starts it, and (for the
  controller) waits for health.

## Install the Controller

Wire endpoint: `POST /api/install/controller`. Request fields
(`controllerInstallRequest`):

| Field | Default | Notes |
|---|---|---|
| `installMode` | `compose` | `compose` or `native`. |
| `installDir` | `/opt/prexorcloud/controller` | |
| `httpPort` | `8080` | REST API port. |
| `grpcPort` | `9090` | Daemon-facing gRPC port. |
| `corsOrigins` | `["http://localhost:3000"]` | Dashboard origins allowed by CORS. |
| `mongoMode` | `local` | `local` uses a local/sidecar MongoDB; `remote` requires `mongoUri`. |
| `mongoUri` | — | Required when `mongoMode=remote`. Local default `mongodb://mongo:27017`. |
| `redisMode` | `local` | Same shape as Mongo. |
| `redisUri` | — | Required when `redisMode=remote`. Local default `redis://redis:6379`. |
| `yamlOverride` | — | Fully rendered `controller.yml`; written verbatim when set. |
| `joinToken` | — | Set to join an existing Raft cluster (see below). |

What the handler does, in order:

1. Refuse if the host can't install (non-Linux) or native is requested without
   root.
2. If `yamlOverride` is set, validate it with `setup.ValidateControllerYAML`.
   On failure it returns `422` with a structured `validationErrors` list — all
   findings at once — and does **no** side effects. This guards the
   production-profile + missing `modules.signing.trustRoot` crash-loop.
3. Install cosign when needed (native installs verify the JAR fail-closed; any
   path that auto-provisions a signing trust root also needs it).
4. Resolve the latest GitHub release (`PrexorJustin/prexorcloud`), find the
   `cloud-controller-*` asset, create install dirs, download + verify the JAR
   to `PrexorCloudController.jar`.
5. Auto-provision a cosign keypair at `config/security/module-trust-root.pem`
   when the YAML asks for signing (`runtime.profile=production` or
   `modules.signing.required=true`) **and** `trustRoot` points at that managed
   default path. Idempotent — reuses an existing keypair. Skipped if you point
   `trustRoot` at your own PEM.
6. Write `config/controller.yml` (verbatim from `yamlOverride`, or a reduced
   ports+URIs config). The CLI-written config sets `runtime.profile=production`.
7. **Compose:** write `docker-compose.yml` (with local Mongo/Redis sidecars
   when chosen). Next steps: `cd <dir>` → `docker compose up -d` → open
   `http://localhost:8080`.
8. **Native:** detect distro, ensure Temurin JRE 25, install MongoDB 8.0 /
   Redis when local, register the `prexorcloud-controller` systemd unit, start
   it, and poll the health endpoint (up to 4 minutes — cold JVM boot on a small
   VPS is slow). Next steps include `systemctl status prexorcloud-controller`
   and `prexorctl login`.

### Joining an existing cluster

Set `joinToken` (a `prexor-jt:v1:...` wire token) to add this controller to an
existing Raft cluster. The handler writes the token to
`config/security/pending-join-token` (owner-only perms). The controller's
bootstrap picks it up on first start and runs the join flow instead of a Day-0
bootstrap. Post-install steps then include:

```sh
prexorctl cluster members   # confirm this controller appears as READY
```

Raft tolerates `floor((N-1)/2)` controller failures — aim for an odd member
count ≥ 3 for HA. A 1- or 2-node cluster has zero fault tolerance.

## Install a Daemon

Wire endpoint: `POST /api/install/daemon`. Request fields
(`daemonInstallRequest`):

| Field | Default | Notes |
|---|---|---|
| `installMode` | `compose` | `compose` or `native`. |
| `installDir` | `/opt/prexorcloud/daemon` | |
| `nodeId` | — | **Required.** This node's ID in the cluster. |
| `controllerHost` | `127.0.0.1` | Controller host the daemon dials. |
| `grpcPort` | `9090` | Controller gRPC port. |
| `joinToken` | — | **Required.** Daemon join token (see below). |
| `yamlOverride` | — | Fully rendered `daemon.yml`; written verbatim when set. |

`nodeId` and `joinToken` are validated up front (`400` with
`NODE_ID_REQUIRED` / `JOIN_TOKEN_REQUIRED` if missing). The handler resolves
the release, finds the `cloud-daemon-*` asset, downloads + verifies
`PrexorCloudDaemon.jar`, writes `config/daemon.yml`, then either writes a
compose project or registers + starts the `prexorcloud-daemon` systemd unit
(native ensures Temurin JRE 25 first). Next steps confirm the node appears in
the controller's node list.

### Getting a daemon join token

On the controller host, mint a token before running the daemon wizard:

```sh
prexorctl token create --node my-first-daemon
```

The daemon redeems it during enrollment; the token is single-use and
short-lived.

## Install the Dashboard

Wire endpoint: `POST /api/install/dashboard`. Request fields
(`dashboardInstallRequest`):

| Field | Default | Notes |
|---|---|---|
| `installMode` | `compose` | `compose` or `native`. |
| `webServer` | `nginx` | Native only: `nginx` or `caddy`. |
| `installDir` | `/opt/prexorcloud/dashboard` | |
| `publicUrl` | — | **Required.** Public URL the dashboard serves at (e.g. `https://dash.example.com`). |
| `listenPort` | `80` | Local port the dashboard listens on. |
| `controllerUrl` | — | **Required.** Controller base URL (e.g. `https://controller.example.com:8080`). |
| `adminUser` | `admin` | Controller admin username. |
| `adminPassword` | — | **Required.** Used once to register CORS, then discarded. |
| `skipCorsRegister` | `false` | Skip the CORS registration step. |

Flow:

1. Authenticate against the controller via `POST /api/v1/auth/login`.
   **Hard-fails** if the controller is unreachable or credentials are wrong — a
   dashboard without a working controller link is useless.
2. Unless `skipCorsRegister` is set, register `publicUrl` in the controller's
   CORS allow-list via `PATCH /api/v1/admin/cors/origins`
   (`{"action":"add","origin":"<publicUrl>"}`). This is non-fatal: on failure
   the wizard tells you to add the origin to `controller.yml` manually. Applied
   live; no controller restart needed.
3. **Compose:** create dirs, write `docker-compose.yml` + a templated
   `nginx.conf` that proxies `/api/*` to `controllerUrl`. Next steps:
   `cd <dir>` → `docker compose up -d` → open `publicUrl`.
4. **Native:** download + verify the `dashboard-static-*` bundle, extract it to
   the web root, install nginx (or Caddy), write the vhost config, and reload.
   With Caddy, TLS is auto-provisioned on first HTTPS request.

## CLI Login mode

The CLI-login card links this CLI to an existing controller without installing
anything. Wire endpoint: `POST /api/cli/login` with `{controller, username,
password}`.

The handler normalizes the controller URL (adds `http://` if no scheme),
`POST`s to `/api/v1/auth/login`, and on success hands `{controller, token}` to
the `OnCliLogin` callback. That callback (`persistCliLogin` in
`cli/cmd/setup_browser.go`) writes the auth into the prexorctl config. Under
`sudo`, the config file is chowned to `$SUDO_USER` so the invoking operator can
use it without root.

Error codes:

- `AUTH_FAILED` (`401`) — controller rejected the credentials, or returned an
  empty token.
- `CONTROLLER_UNREACHABLE` (`502`) — DNS failure, timeout, or `5xx`. The
  message is rewritten to a friendly sentence ("can't resolve …", "timed out
  reaching …").

This is the same outcome as a TTY `prexorctl login`, driven from the wizard's
browser UI.

## Live install console

The wizard requests `Accept: application/x-ndjson` so install progress streams
as newline-delimited JSON events (`cli/internal/setupweb/stream.go`):

| Event `type` | Carries |
|---|---|
| `step` | A high-level progress line (`Downloaded + verified controller jar to …`). |
| `output` | Raw terminal bytes from VPS commands (apt progress bars, cosign output, systemctl chatter), rendered in an xterm.js console with colors and `\r` progress. |
| `done` | Terminal result: `ok`, `nextSteps`, or `error` + `errorCode` + `docsUrl`. |

A failed stream still returns HTTP `200`; the real outcome is in the `done`
event. A failure leaves the wizard running so you can fix and retry. The
single-use exit timer starts only on success. Clients that don't request NDJSON
get one buffered JSON document at the end.

## Error codes

Install responses carry a stable `errorCode` and a `docsUrl` deep link.
Selected codes from `cli/internal/setupweb/install.go`:

| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_REQUEST` | 400 | Malformed body or missing required field. |
| `MODE_UNSUPPORTED` | 400 | Unknown `installMode`. |
| `INVALID_MODE` | 400 | Storage mode not `local`/`remote`. |
| `MONGO_URI_REQUIRED` / `REDIS_URI_REQUIRED` | 400 | `remote` mode without a URI. |
| `NODE_ID_REQUIRED` / `JOIN_TOKEN_REQUIRED` | 400 | Daemon install missing those fields. |
| `INSTALL_UNSUPPORTED` | 422 | Non-Linux host. |
| `NATIVE_UNAVAILABLE` | 422 | Native requested off-Linux or without root. |
| `INVALID_CONFIG` | 422 | `controller.yml` failed pre-flight validation; full list in `validationErrors`. |
| `RELEASE_FETCH_FAILED` / `ASSET_NOT_FOUND` | — | Release lookup / asset selection failed. |
| `DOWNLOAD_FAILED` | — | JAR/bundle download or signature verify failed. |
| `CONFIG_WRITE_FAILED` / `COMPOSE_WRITE_FAILED` | — | Filesystem write failed. |
| `DEPENDENCY_INSTALL_FAILED` / `SERVICE_REGISTER_FAILED` | — | Native: package install or systemd registration failed. |
| `TRUST_ROOT_PROVISION_FAILED` | — | cosign keypair generation failed. |

## Walkthrough A — single-host lab (compose)

Everything on one VPS, Docker mode. Run from the VPS (local desktop browser) or
over an SSH tunnel.

Install the binary and launch the wizard:

```sh
curl -fsSL https://prexor.cloud/install.sh | sh
```

The one-liner launches `prexorctl setup --browser` automatically. If you're
SSH'd in with no local browser, it switches to `--public` and prints a token
URL; or run `prexorctl setup --ssh-tunnel` yourself for the tunnel flow.

1. **Controller** → Docker mode → keep defaults (HTTP `8080`, gRPC `9090`,
   local Mongo + Redis). Install, then:

   ```sh
   cd /opt/prexorcloud/controller
   docker compose up -d
   ```

   Open `http://localhost:8080` once healthy. Log the CLI in (CLI Login card,
   or `prexorctl login`), then mint a daemon token:

   ```sh
   prexorctl token create --node lab-daemon
   ```

2. Re-run `prexorctl setup`. **Daemon** → Docker mode → node ID `lab-daemon`,
   controller host `127.0.0.1`, gRPC `9090`, paste the join token. Install,
   then `docker compose up -d` in `/opt/prexorcloud/daemon`.

3. Re-run `prexorctl setup`. **Dashboard** → Docker mode → public URL (e.g.
   `http://<vps-ip>`), controller URL `http://127.0.0.1:8080`, admin
   credentials. Install, then `docker compose up -d` in the dashboard dir and
   open the public URL.

Confirm the node:

```sh
prexorctl node list
```

## Walkthrough B — three VPSes, native systemd

One component per host, native mode, each over its own SSH tunnel. Native needs
root, which the install one-liner elevates to automatically.

### VPS A — Controller

```sh
curl -fsSL https://prexor.cloud/install.sh | sh
```

In the wizard: **Controller** → **Native** → HTTP `8080`, gRPC `9090`, local
Mongo + Redis (or remote URIs if you run managed datastores). Install streams
the JRE/Mongo/Redis package output, registers `prexorcloud-controller`, and
waits for health. After it's up:

```sh
prexorctl login                      # CLI auto-linked on this host
prexorctl token create --node vps-b
```

### VPS B — Daemon

```sh
curl -fsSL https://prexor.cloud/install.sh | sh
```

In the wizard: **Daemon** → **Native** → node ID `vps-b`, controller host =
VPS A's reachable address, gRPC `9090`, paste the token from VPS A. Install
registers `prexorcloud-daemon`. Confirm:

```sh
systemctl status prexorcloud-daemon --no-pager
prexorctl node list
```

### VPS C — Dashboard

```sh
curl -fsSL https://prexor.cloud/install.sh | sh
```

In the wizard: **Dashboard** → **Native** → web server nginx (or Caddy for
auto-TLS), public URL `https://dash.example.com`, controller URL
`https://controller.example.com:8080`, admin credentials. The wizard logs into
the controller, registers the dashboard origin in CORS (live, no restart),
installs the web server, and configures it to proxy `/api/*` to the controller.
Open the public URL.

## Verify

Re-read the wizard's own flags:

```sh
prexorctl setup --help
```

After any install, the post-install "next steps" panel lists the exact commands
to bring the component up and confirm it. The check for "is this working" is
always: the component appears in `prexorctl node list` /
`prexorctl cluster members`, or its health endpoint answers.

## Notes and caveats

- The browser path exposes both compose and native modes per component. Native
  requires Linux + root; the `/api/info` gating disables the toggle otherwise.
- The wizard never sits behind a reverse proxy: it ignores `X-Forwarded-For`,
  and `--public` is for direct network reach during the setup window only.
- Release artifacts come from the latest GitHub release of
  `PrexorJustin/prexorcloud`. The native dashboard path needs a
  `dashboard-static-*` asset in that release; offline installs are documented
  separately.
- The `controller.yml` written by the CLI uses `runtime.profile=production`,
  which is why the signing trust-root auto-provision exists — it prevents a
  production controller from crash-looping on a missing
  `modules.signing.trustRoot`.
</content>
