---
title: Installation
description: Install the PrexorCloud Controller plus your first Daemon — Docker Compose or native systemd, with cosign-verified artifacts and the pre-production checks.
---

This page takes you from nothing to a running Controller with one Daemon enrolled. Pick one of two install paths — Docker Compose or native (`prexorctl setup` / systemd) — and follow it top to bottom. Both produce a `production`-profile cluster you can put real instances on.

## What you'll do here

- Verify the release artifacts are signed by the right GitHub Actions workflow (cosign keyless).
- Install the Controller via Docker Compose or natively.
- Enroll your first Daemon with a one-time join token that bootstraps mTLS.
- Run the pre-production checks before you expose the Controller.

## Before you start

| Component | Minimum | Notes |
|---|---|---|
| OS (server-side) | Linux x86_64 / arm64 | The Controller and Daemon run on Linux only. The `prexorctl` CLI also ships for macOS and Windows for remote operation. |
| Java | OpenJDK 25+ | Required for native installs. Both processes launch with `--enable-preview --enable-native-access=ALL-UNNAMED`. Not needed for Docker. |
| MongoDB | 6.0+ (8.0 in the reference stack) | Durable state store. Not embedded. |
| Valkey or Redis | Valkey 7.2+ / Redis 7+ | Coordination store. Required when `runtime.profile=production` — the Controller refuses to start in production without it. |
| Free TCP ports | 8080 (REST), 9090 (gRPC), 3000 (dashboard) | Defaults; all configurable. |
| `cosign` | latest | For artifact verification. `prexorctl setup` installs it automatically if missing. |

The Daemon host must reach the Controller's gRPC port (`9090`). Operators must reach the Controller's REST port (`8080`).

## Step 1 — Verify the release is signed

Releases are signed with **cosign keyless** using the GitHub Actions OIDC identity. There is no long-lived signing key — verification proves an artifact was signed by a specific workflow running in this repo.

Two workflows sign two artifact sets, so the certificate identity differs by what you're verifying:

| Artifact | Signed by workflow | Identity regex |
|---|---|---|
| `prexorctl` CLI (`checksums.txt`) | `release.yml` | `…/.github/workflows/release.yml@…` |
| Controller + Daemon jars (`SHA256SUMS`) | `release-jars.yml` | `…/.github/workflows/release-jars.yml@…` |

Both use the GitHub Actions OIDC issuer `https://token.actions.githubusercontent.com`.

The jar release publishes a `SHA256SUMS` manifest plus two sidecars, `SHA256SUMS.sig` (signature) and `SHA256SUMS.crt` (signing certificate). Verify the signature on the manifest, then check each jar's hash against the now-trusted manifest:

```bash
# Download SHA256SUMS, SHA256SUMS.sig, SHA256SUMS.crt and the jars from the release.

cosign verify-blob \
  --certificate-identity-regexp "(?i)^https://github\.com/prexorjustin/prexorcloud/\.github/workflows/release-jars\.ya?ml@.*" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  --certificate SHA256SUMS.crt \
  --signature SHA256SUMS.sig \
  SHA256SUMS

sha256sum -c SHA256SUMS
```

The first command must print `Verified OK`. The second must print `OK` for each jar. Don't run an artifact that fails either step.

For the `prexorctl` binary, the GoReleaser-produced `checksums.txt` carries the same `.sig` + `.pem` sidecars; swap the identity regex to `release.yml` and the filenames accordingly.

`prexorctl setup` performs this whole chain for you: it downloads `SHA256SUMS` + sidecars, runs `cosign verify-blob` against the `release-jars.yml` identity, parses the trusted manifest, and compares the downloaded jar's SHA-256 before writing it to disk. If `cosign` is missing in a production install it fails closed.

:::tip[Container images]
The GHCR images (`ghcr.io/prexorjustin/prexorcloud-controller`, `-daemon`, `-dashboard`) follow the same keyless model. Verify with `cosign verify <image>` and the equivalent identity regex.
:::

## Step 2 — Choose an install path

| Path | Best for | What it provisions |
|---|---|---|
| **Docker Compose** | Single-host installs; you already run Docker | Controller, Daemon, dashboard, MongoDB, Valkey on a private network with healthchecks |
| **Native** (`prexorctl setup` / systemd) | Multi-host fleets, air-gapped hosts, hosts that already run Mongo/Valkey under systemd | Jars under `/opt/prexorcloud/`, systemd units, distro-native dependencies |

`prexorctl setup --install-mode=compose` generates the same kind of Compose project the hand-rolled path below uses. Running Compose by hand or through the wizard is preference.

## Path A — Docker Compose

The reference stack lives at `deploy/compose/` in the source tree (or under `compose/` in the release tarball). It keeps Mongo and Valkey on an internal-only network and reads secrets from `.env`.

### A1. Lay down the stack

```bash
cd deploy/compose
cp .env.example .env
$EDITOR .env
```

`.env` controls image pins, heap sizes, and host ports:

| Variable | Default | Purpose |
|---|---|---|
| `PREXORCLOUD_CONTROLLER_IMAGE` | `ghcr.io/prexorjustin/prexorcloud-controller:latest` | Pin a specific tag in production. |
| `PREXORCLOUD_DAEMON_IMAGE` | `…/prexorcloud-daemon:latest` | |
| `PREXORCLOUD_DASHBOARD_IMAGE` | `…/prexorcloud-dashboard:latest` | |
| `PREXORCLOUD_CONTROLLER_HEAP` | `1g` | Controller `-Xmx`. 1g handles a few hundred instances; 2g–4g for thousands. |
| `PREXORCLOUD_DAEMON_HEAP` | `512m` | Daemon `-Xmx`. It supervises processes, not hosts them. |
| `PREXORCLOUD_HTTP_PORT` | `8080` | Host port for REST. Front this with a TLS reverse proxy in production. |
| `PREXORCLOUD_GRPC_PORT` | `9090` | Host port for Daemon gRPC. mTLS-protected; safe to expose. |
| `PREXORCLOUD_DASHBOARD_PORT` | `3000` | Host port for the dashboard. |

### A2. Edit `controller.yml`

The Controller reads `deploy/compose/controller.yml`, bind-mounted read-only into the container. Set these before first boot:

- `security.jwtSecret` — required for stable sessions. Generate with `openssl rand -base64 48`. If left blank the Controller generates a random secret each boot, invalidating all sessions on restart.
- `security.initialAdminPassword` — set once for first login, then clear it. Leave blank to have a random password generated and written to a file (see A4).
- `network.allowedSubnets` — tighten to your operator and Daemon ranges. The reference file ships with the RFC 1918 ranges; the record default is `0.0.0.0/0`, `::/0` (permissive).
- `http.cors.allowedOrigins` — the dashboard origin you actually serve.
- `redis.uri` — defaults to `redis://valkey:6379`. Required because `runtime.profile` is `production`.

### A3. Start it

```bash
docker compose -f compose.yml up -d
docker compose -f compose.yml ps
```

You get `controller`, `daemon`, `dashboard`, `mongo`, and `valkey`. Mongo and Valkey sit on the internal-only `prexor-internal` network and are never published to the host. The `daemon` service waits on `controller` being healthy via `depends_on: { condition: service_healthy }`.

### A4. Verify readiness and grab the admin password

```bash
curl -s http://localhost:8080/health
curl -s http://localhost:8080/ready
```

`/health` returns `{"status":"UP","readiness":{...}}`. `/ready` returns HTTP 200 when ready, 503 otherwise. The readiness body lists four checks:

```json
{"status":"READY","checks":{"mongo":true,"redis":true,"scheduler":true,"platformModules":true}}
```

`mongo:false` means MongoDB is unreachable; `redis:false` means Valkey/Redis is unreachable. Both probes are unauthenticated and exempt from the subnet guard so load balancers can reach them. The same content is served at `/api/v1/system/health` and `/api/v1/system/ready` (those two require auth) and at `/metrics` for Prometheus (plaintext, unauthenticated, when `metrics.enabled=true`).

On first boot the Controller creates the `admin` user and writes its password to `config/.initial-admin-password` (owner-only) inside the `controller-data` volume. Read it, log in, then change the password — the Controller deletes the file automatically once `admin` changes it.

## Path B — Native

Native installs put jars under `/opt/prexorcloud/`, register systemd units, and run as the unprivileged `prexorcloud` user. Use `prexorctl setup` for the guided flow or wire up the units by hand.

### B1. Install the CLI

```bash
curl -fsSL https://prexor.cloud/install.sh | sh
prexorctl version
```

The one-liner installs the `prexorctl` binary (with a `pc` alias) to `/usr/local/bin` and, by default, launches the setup wizard. Pass `--no-setup` to install the binary only, or `--version <ver>` to pin a release. On Windows, install via Scoop (`scoop install prexorctl`).

### B2. Run the Controller wizard

```bash
sudo prexorctl setup
```

`prexorctl setup` opens a loopback browser wizard (`127.0.0.1:9100`) by default. On headless hosts (no `DISPLAY`/`BROWSER`, inside a container, or in CI) it falls back to the TTY form automatically; pass `--no-browser` to force TTY. When you run it inside an SSH session it switches to a tunnelled wizard and prints the `ssh -L` command to run from your laptop.

The wizard installs one component at a time. For the Controller it:

1. Checks dependencies — installs Java 25 (managed Temurin JRE) and offers to install MongoDB and Valkey through your distro's package manager. Decline anything you manage externally and supply its URI instead. Native installs must run as root.
2. Downloads `PrexorCloudController.jar`, cosign-verifies it against `SHA256SUMS` (the `release-jars.yml` identity), and writes it to `/opt/prexorcloud/controller/`.
3. Prompts for HTTP port (`8080`), gRPC port (`9090`), and the dashboard CORS origin, then writes `controller.yml`.
4. Auto-configures the local CLI to point at the new Controller (`~/.prexorcloud/config.yml`).
5. Optionally registers and starts `prexorcloud-controller.service`, runs a live startup validation, and auto-logs the local CLI in using the bootstrap password.

For unattended installs, drive it with flags instead:

```bash
sudo prexorctl setup \
  --non-interactive \
  --component controller \
  --install-mode native \
  --service-mode enable \
  --controller-mongo-mode local \
  --controller-redis-mode local \
  --controller-http-port 8080 \
  --controller-grpc-port 9090 \
  --controller-cors-origin https://dashboard.example.com
```

Use `--controller-mongo-mode remote --controller-mongo-uri mongodb://…` (and the Redis equivalents) to point at external datastores.

### B3. Verify

```bash
sudo systemctl status prexorcloud-controller
curl -s http://localhost:8080/ready
```

Same readiness contract as Compose — `mongo`, `redis`, `scheduler`, and `platformModules` must all be `true`.

The bootstrap admin password is at `/opt/prexorcloud/controller/config/.initial-admin-password`.

### B4. systemd units by hand (optional)

If you'd rather not use the wizard, the reference units are at `deploy/systemd/`. They assume the layout in `deploy/systemd/README.md` and a `prexorcloud` system user. The Controller `ExecStart` is:

```ini
ExecStart=/usr/bin/java \
    --enable-preview \
    --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow \
    -Dio.netty.noUnsafe=true \
    -jar /opt/prexorcloud/controller/PrexorCloudController.jar
```

The Controller unit is hardened (`NoNewPrivileges`, `ProtectSystem=strict`, a tight `ReadWritePaths`). The Daemon unit is deliberately more permissive because it spawns Minecraft processes via `ProcessBuilder` that need normal filesystem and network access — don't tighten it or running instances break.

```bash
sudo useradd --system --home /opt/prexorcloud/controller --shell /usr/sbin/nologin prexorcloud
sudo chown -R prexorcloud:prexorcloud /opt/prexorcloud
sudo cp deploy/systemd/prexorcloud-controller.service /etc/systemd/system/
sudo cp deploy/systemd/prexorcloud-daemon.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now prexorcloud-controller
```

## Step 3 — First login

If the wizard already auto-logged you in (native path with startup validation), skip this. Otherwise:

```bash
prexorctl login
# Controller URL: http://<host>:8080
# Username: admin
# Password: <contents of config/.initial-admin-password>
prexorctl status
```

`status` shows your Controller with zero nodes and zero groups.

## Step 4 — Enroll your first Daemon

Daemons authenticate with mTLS. A fresh Daemon has no certificate, so it presents a **join token** — a single-use credential the Controller exchanges for a per-Daemon certificate signed by its internal CA. The token is a signed, HMAC-protected string prefixed `prexor-jt:v1:`.

### 4a. Create a token

```bash
prexorctl token create --node node-1 --ttl 1h
```

```
Join Token Created
  Token ID    …
  Join Token  prexor-jt:v1:…
  Node ID     node-1
  Expires At  …
```

Flags:

| Flag | Default | Meaning |
|---|---|---|
| `--node` | (any node) | Bind the token to a specific node ID. Optional. |
| `--ttl` | `1h` | Token lifetime, e.g. `1h`, `24h`. |

`prexorctl token list` shows outstanding tokens; `prexorctl token revoke <id>` invalidates one. The backing REST endpoint is `POST /api/v1/admin/tokens`.

### 4b. Install the Daemon

**Compose:** the in-stack `daemon` already runs. Edit `deploy/compose/daemon.yml`, set `security.joinToken` to the token, set a unique `nodeId`, then restart it:

```bash
docker compose -f compose.yml up -d daemon
```

**Native, wizard:**

```bash
sudo prexorctl setup
# choose: daemon
```

Or non-interactively:

```bash
sudo prexorctl setup \
  --non-interactive \
  --component daemon \
  --install-mode native \
  --service-mode enable \
  --daemon-node-id node-1 \
  --daemon-controller-host <controller-host> \
  --daemon-controller-grpc-port 9090 \
  --daemon-controller-http-port 8080 \
  --daemon-join-token prexor-jt:v1:...
```

The Daemon flow downloads and cosign-verifies `PrexorCloudDaemon.jar`, writes `daemon.yml`, then redeems the join token over REST (`--daemon-controller-http-port`, default `8080`). On success the Controller mints an mTLS certificate into the Daemon's `config/security/` directory. If REST redemption fails the Daemon retries over gRPC on first start. After enrollment the token is consumed — replays are rejected server-side.

### 4c. Confirm

```bash
prexorctl node list
```

```
ID       STATUS    CPU   MEMORY        INSTANCES   CONNECTED SINCE
node-1   ● ONLINE  2%    512/8192 MB   0           …
```

Node status is one of `ONLINE`, `DRAINING`, `UNREACHABLE`, `OFFLINE`. The new node reaches `ONLINE` within a heartbeat interval (default 30s). Filter with `prexorctl node list --state ONLINE`; inspect one with `prexorctl node info node-1`.

## Step 5 — Pre-production checks

Run these before exposing the Controller beyond a trusted network.

1. **Change the admin password.** From the dashboard, or `POST /api/v1/auth/change-password`. Changing the `admin` password deletes the bootstrap password file automatically.
2. **Confirm the bootstrap file is gone.** If you set `security.initialAdminPassword` in config rather than letting it generate, clear it now and restart so it isn't redacted into diagnostics on every read.
   ```bash
   # Compose:
   docker compose -f compose.yml exec controller ls config/.initial-admin-password
   # Native:
   ls /opt/prexorcloud/controller/config/.initial-admin-password
   ```
3. **Tighten `network.allowedSubnets`** to your operator and Daemon ranges, then restart the Controller. The subnet guard exempts only the health/ready/metrics probes.
4. **Terminate TLS at a reverse proxy.** The REST port is plaintext by design. Front it with Caddy / nginx / Traefik. The gRPC port is mTLS and safe to expose directly.
5. **Set a persistent `security.jwtSecret`** (`openssl rand -base64 48`) so restarts don't log every operator out.
6. **Enable module-signing enforcement** if you'll install third-party modules: `modules.signing.required=true` plus a configured `modules.signing.trustRoot`. In `production` the Controller already refuses to start with signing required but no trust root.

## Configuration keys

`controller.yml` deserializes into `ControllerConfig`. The keys you touch at install time:

| Key | Default | Notes |
|---|---|---|
| `http.host` / `http.port` | `0.0.0.0` / `8080` | REST + SSE. |
| `grpc.host` / `grpc.port` | `0.0.0.0` / `9090` | Daemon mTLS gRPC. Must differ from `http.port`. |
| `runtime.profile` | `development` | Set `production` for real clusters. Production requires `redis`. |
| `database.uri` / `database.database` | `mongodb://localhost:27017` / `prexorcloud` | MongoDB. |
| `redis.uri` | (nullable) | `redis://…`. Required under `production`. |
| `network.allowedSubnets` | `0.0.0.0/0`, `::/0` | CIDRs allowed to reach non-probe routes. |
| `http.cors.allowedOrigins` | `http://localhost:3000` | Dashboard origin(s). |
| `security.jwtSecret` | (auto) | 32+ random bytes (base64). Set it for stable sessions. |
| `security.jwtExpirationMinutes` | `1440` | 1–43200. |
| `security.initialAdminPassword` | (random) | First-boot only; clear after login. |
| `metrics.enabled` | `true` | Exposes `/metrics`. |

`daemon.yml` deserializes into `DaemonConfig`:

| Key | Default | Notes |
|---|---|---|
| `nodeId` | `node-1` | Unique across the cluster. |
| `controller.host` / `controller.grpcPort` | `127.0.0.1` / `9090` | Where to reach the Controller. |
| `security.certificateDir` | `config/security` | Where the minted mTLS cert lands. |
| `security.joinToken` | `""` | Set once for enrollment; cleared after. |
| `health.bindAddress` / `health.port` | `127.0.0.1` / `9091` | Local health endpoint. |
| `resources.maxMemoryMb` | `0` | `0` = auto-detect from cgroup limits / `/proc/meminfo`. |
| `instances.directory` | `instances` | Where instance working dirs live. |
| `labels` | `{}` | Free-form placement labels. |

`ConfigValidator` runs at startup and fails closed with a list of errors. Common ones: `http.port` equals `grpc.port`; `redis.uri must be configured when runtime.profile=production`; `runtime.profile` not `development`/`production`.

## What native setup does not do

- Provision a load balancer or set up multi-Controller HA (supported by pointing a second Controller at the same Mongo + Valkey, but out of scope for these single-host paths).
- Configure host firewall rules — the Controller binds all interfaces; restrict with `nftables`/`iptables` or cloud security groups.
- Set up Mongo/Valkey replica sets — the bundled option is single-node.
- Configure Prometheus scraping — point your scraper at `/metrics`.

## Common failures

| Symptom | Cause | Fix |
|---|---|---|
| `cosign verify-blob failed` | Wrong identity regex, or tampered artifact | Use `release-jars.yml` for jars, `release.yml` for the CLI. Re-download from the release. |
| Controller exits at startup with a config error list | `ConfigValidator` rejected the config | Read the logged errors; common one is missing `redis.uri` under `production`. |
| `/ready` 503 with `mongo:false` | MongoDB unreachable | Check `database.uri`; check Mongo logs. |
| `/ready` 503 with `redis:false` | Valkey/Redis unreachable | Check `redis.uri`; restart Valkey. |
| Controller won't bind `8080` | Port in use | Change `http.port` (keep it different from `grpc.port`). |
| Daemon enrollment fails | Token expired, already consumed, or wrong Controller URL | Issue a fresh token; confirm the Daemon can reach the Controller's REST (8080) and gRPC (9090) ports. |
| Sessions drop on every restart | `security.jwtSecret` blank | Set a fixed secret and restart. |

## Next

- **Quickstart** — your first Group, Instance, and players.
- **Core concepts** — Groups, Instances, Templates, Networks, Modules.
- **Operations** — upgrade, backup, DR, and scaling.
