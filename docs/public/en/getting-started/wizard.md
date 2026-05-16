---
title: Install wizard — three components across three VPS
description: Walk through installing the PrexorCloud Controller, Daemon, and Dashboard across separate hosts using the browser-default `prexorctl setup` wizard.
---

This guide installs a complete PrexorCloud cluster across three VPS using the
browser-based `prexorctl setup` wizard. Each component runs on its own host;
the CLI auto-links itself to the cluster as a side effect of installing
something, so you never copy admin tokens by hand.

If you only want a single-host lab install, the older
[Installation guide](./installation.md) is shorter.

## The three components

| Component | What it does | One per | Auth-bootstrap during install |
|---|---|---|---|
| **Controller** | Orchestrator. Holds MongoDB + Redis state, serves REST API, accepts daemons. | Cluster | Generates a random admin password, writes it to `config/.initial-admin-password`, wizard reveals it once and auto-logs the local CLI in. |
| **Daemon** | Worker node. Runs Minecraft server instances; one per worker VPS. | Host | Operator pastes a one-shot join token. CLI redeems it, receives mTLS cert + a `DAEMON_HOST`-scoped CLI JWT, both stored locally. |
| **Dashboard** | Nuxt SPA + nginx proxy. Operators visit it in a browser. | Public hostname | Wizard logs into the controller with admin credentials, registers the dashboard's public URL in the controller's CORS allow-list, saves a local CLI context. |

## Before linking, the CLI is intentionally locked

Until `~/.prexorcloud/config.yml` has at least one context with a controller URL,
`prexorctl` only allows the commands that exist to fix that state: `setup`,
`login`, `logout`, `version`, `help`, `completion`, `context`. Everything else
prints **no cluster connected** and points at the two fix paths.

This means a fresh `curl ... | sh` install only has two doors: install a
component on this VPS (`setup`) or link this CLI to an existing controller
(`login`).

## VPS A — install the Controller

Linux / macOS:

```sh
curl -fsSL https://prexor.cloud/install.sh | sh   # downloads the prexorctl binary
prexorctl setup
```

Windows (PowerShell):

```powershell
irm https://prexor.cloud/install.ps1 | iex
prexorctl setup
```

`prexorctl setup` opens `http://127.0.0.1:9100` in your default browser and
walks you through:

1. **Component pick** — choose **Controller**.
2. **Configuration** — install dir, HTTP/gRPC ports, MongoDB and Redis source
   (in-compose containers by default).
3. **Install** — wizard downloads `PrexorCloudController.jar`, writes
   `controller.yml` + `docker-compose.yml`, optionally registers a systemd unit.
4. **Startup validation** (native mode) — wizard starts the controller, polls
   the health endpoint, reads the freshly-generated bootstrap password from
   `config/.initial-admin-password`, and auto-logs the local CLI in.

On hosts with no `$DISPLAY`/`$BROWSER` or inside Docker containers the wizard
falls back to a TTY form automatically. Force TTY anywhere with
`prexorctl setup --no-browser`.

After the wizard finishes you'll see:

```
✓ Controller running at http://localhost:8080
✓ CLI logged in as admin (context: default)
```

The bootstrap password file stays on disk until you change the admin password
from the dashboard (the controller deletes it itself at that point — one-time
lifecycle). Generate a daemon join token before moving on:

```sh
prexorctl token create --node my-first-daemon
```

Copy the printed token string. It's single-use and short-lived.

## VPS B — install a Daemon

```sh
curl -fsSL https://prexor.cloud/install.sh | sh
prexorctl setup
```

Browser wizard again:

1. **Component pick** — choose **Daemon**.
2. **Configuration** — node ID, controller host + gRPC port, paste the join
   token from VPS A. The wizard also asks for the controller's HTTP port
   (default 8080) — it uses this to redeem the join token over REST so the
   CLI on VPS B gets a context too.
3. **Cluster enrolment** — wizard POSTs to
   `/api/v1/bootstrap/exchange` on the controller, receives the node certificate
   + a `DAEMON_HOST` CLI JWT, writes the cert files into the daemon's
   `config/security/`, and saves the JWT as a CLI context named after the
   node.
4. **Install** — writes `daemon.yml` + `docker-compose.yml`, optionally
   registers a systemd unit.

End state:

```
✓ Join token redeemed; certificate installed
✓ CLI linked to cluster (context: <nodeId>)
✓ PrexorCloud Daemon prepared
```

The daemon detects the cert files exist on first start and skips its own
gRPC bootstrap. Run `prexorctl node list` on VPS B to confirm it's connected.

The `DAEMON_HOST` role is intentionally narrow — read cluster state, manage
instances on this node, view logs. It does **not** grant cluster-wide write
access. A leaked daemon host doesn't compromise admin credentials.

## VPS C — install the Dashboard

```sh
curl -fsSL https://prexor.cloud/install.sh | sh
prexorctl setup
```

Browser wizard:

1. **Component pick** — choose **Dashboard**.
2. **Configuration** — install dir, **public URL** (e.g.
   `https://dash.example.com`), local nginx port, controller base URL, admin
   username (default `admin`), and the admin password you saved from VPS A.
3. **Controller link** — wizard logs into the controller via
   `/api/v1/auth/login`. Hard-fails if unreachable or credentials are wrong —
   a dashboard without a working controller link is useless.
4. **CORS registration** — wizard sends `PATCH /api/v1/admin/cors/origins`
   to add the dashboard's public URL to the controller's CORS allow-list.
   Applied live to the running controller; the YAML write makes it durable
   across restarts. No restart needed.
5. **Compose project** — wizard writes a `docker-compose.yml` that runs
   `ghcr.io/prexorjustin/prexorcloud-dashboard:latest` and a templated
   `nginx.conf` that proxies `/api/*` to the controller URL you provided.

End state:

```
✓ Authenticated against https://controller.example.com:8080
✓ Registered https://dash.example.com in controller CORS allow-list (live, no restart needed)
✓ CLI linked to cluster (context: dashboard)
✓ PrexorCloud Dashboard prepared
```

Then `cd /opt/prexorcloud/dashboard && docker compose up -d` and open the
public URL in your browser.

## Network defense-in-depth: `allowedSubnets`

In addition to mTLS (the primary control for daemon → controller) and JWT/admin
password (REST API), the controller enforces an IP-level allow-list against
every inbound REST + gRPC connection except bootstrap and health probes.

The list lives at `network.allowedSubnets` in `controller.yml`. Default for
existing installs is `[0.0.0.0/0, ::/0]` — wide open, behaves like no filter.
Three things tighten it:

1. **Daemon install auto-registers itself.** When a daemon redeems its join
   token (via either gRPC or `POST /api/v1/bootstrap/exchange`), the controller
   reads the source IP, adds `<source>/32` (or `/128` for IPv6) to the live
   allow-list, and persists to `controller.yml`. Next request from that daemon
   passes the guard immediately — no restart, no manual edit.

2. **Admin-driven PATCH.** Any admin can mutate the list at runtime:

   ```sh
   curl -X PATCH https://controller:8080/api/v1/admin/network/allowed-subnets \
     -H "Authorization: Bearer $JWT" \
     -H "Content-Type: application/json" \
     -d '{"action":"add","cidr":"203.0.113.0/24"}'
   ```

   Same shape as the CORS endpoint. Returns `{changed, restartRequired: false,
   allowedSubnets, wideOpen}`.

3. **Manual tightening.** To lock down a cluster: remove `0.0.0.0/0` and
   `::/0` from `controller.yml`, leaving only the per-daemon CIDRs and the
   subnet your operator workstations connect from. The `wideOpen` flag in the
   PATCH response tells the dashboard whether the current state is permissive.

**Always allowed**, regardless of config: `127.0.0.0/8` and `::1/128`. So the
controller's own health probes, the local CLI, and SSH-tunneled access never
break.

**Bootstrap endpoint is exempt** (`POST /api/v1/bootstrap/exchange` and the
gRPC `BootstrapService`). The join token is the auth there, and the exchange
itself is what adds the daemon's IP to the list — chicken-and-egg avoided.

**Behind a reverse proxy** (nginx terminating TLS, cloud LB), the source IP
seen by the controller is the proxy's IP, not the original client. In that
case put the proxy's IP in `allowedSubnets`. CIDR filtering on
original-client IPs requires X-Forwarded-For trust configuration that the
controller doesn't ship yet.

## What the wizard does not do (and what to do instead)

- **Generate TLS certificates.** Provision them yourself (Let's Encrypt via
  certbot, your CDN's TLS, terminated upstream by your load balancer). Set
  `serveMode` to `terminated-upstream` if TLS terminates before nginx.
- **Open firewall ports.** The wizard binds locally; expose them via your
  cloud-provider firewall or distro tooling.
- **Rotate the admin password.** Do it from the dashboard (`/settings/profile`)
  the first time you log in. The controller deletes
  `config/.initial-admin-password` from disk the moment you do.
- **TLS termination.** The wizard does not configure nginx for HTTPS — set
  `tls.mode=terminated-upstream` if your load balancer / CDN handles TLS,
  or wire certbot in by hand otherwise.

## Single-VPS variant

Run all three steps on the same VPS. Everything works — the wizard just writes
three sibling compose projects under `/opt/prexorcloud/{controller,daemon,dashboard}`.
The auto-login on the controller install means you can immediately generate
the daemon token without `prexorctl login`, and the live-CORS update from the
dashboard install means you don't have to restart anything in the middle.

## Operator laptop (no component install)

A laptop that just controls the cluster remotely doesn't install any component.
Use `prexorctl login` directly:

```sh
prexorctl login
# enter controller URL, admin username, admin password → JWT saved as a context
```

Or set up multiple contexts:

```sh
prexorctl context add prod    --controller https://prexor.example.com
prexorctl context add staging --controller https://prexor.staging.example.com
prexorctl context use prod
prexorctl login
```

See [`prexorctl context`](../reference/cli/context.md) for the full multi-cluster
story (ADR 27).
