---
title: Installation
description: Install the PrexorCloud controller, daemon, and CLI on your infrastructure — Docker Compose or bare-metal walkthroughs with cosign verification and mTLS bootstrap.
---

This page is the controller-and-first-daemon install walkthrough. Pick one
of the two paths — Docker Compose or bare-metal `prexorctl setup` — and
follow it linearly. Both produce a working production-profile cluster.

## What you'll learn

- How to verify the `prexorctl` release is signed by the right GitHub
  Actions workflow (cosign keyless).
- Two install paths: Docker Compose (recommended) and bare-metal systemd.
- How a daemon joins the controller via the one-time **join token** that
  bootstraps mTLS.
- The five things to do before exposing the controller to the network.

## Prerequisites

| Component | Minimum | Notes |
|---|---|---|
| OS | Linux x86_64 (Debian, Ubuntu, RHEL, Fedora, openSUSE, Arch) | macOS / Windows controller hosts are not supported. |
| Java | OpenJDK 21+ | `prexorctl setup` installs it via your distro's package manager when missing. |
| MongoDB | 6.0+ | Self-hosted; PrexorCloud does not embed Mongo. |
| Valkey or Redis | Valkey 7.2+ / Redis 7+ | Required in `production` profile. |
| Free TCP ports | 8080, 9090, 9091 | Defaults; configurable in `controller.yml`. |
| Free disk | 10 GB under the install root | Per controller; grows with module storage and audit-log retention. |

You also need a host you control with a stable network address — the daemon
process needs to reach the controller's gRPC port, and operators need to
reach the controller's REST port.

## Step 1 — Verify the release is signed

Releases are signed with **cosign keyless** using the GitHub Actions OIDC
identity. Verification proves "this binary was signed by *that* workflow on
the prexorcloud repo" — exactly what you want before running it.

Pick a release from <https://github.com/prexorjustin/prexorcloud/releases>,
download the archive plus `checksums.txt`, `checksums.txt.sig`, and
`checksums.txt.pem`, then:

```bash
cosign verify-blob \
  --certificate-identity-regexp "^https://github.com/prexorjustin/prexorcloud/.github/workflows/release.yml@refs/tags/" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  --signature checksums.txt.sig \
  --certificate checksums.txt.pem \
  checksums.txt

sha256sum -c checksums.txt
```

The first command verifies the signature chain. The second confirms the
archive you downloaded matches the signed checksum. Both must succeed
before you extract the archive.

:::tip[Container images are signed too]
If you use the GHCR images (`ghcr.io/prexorjustin/prexorcloud-controller:<semver>`
etc.) the same cosign keyless model applies. Use `cosign verify` against
the image with the equivalent identity regex — see the
Distribution doc
for the full recipe.
:::

## Step 2 — Choose your install path

| Path | Best for | Stack |
|---|---|---|
| **Docker Compose** (recommended) | Linux, macOS, or Windows hosts with Docker | Compose v2 reference stack: controller, daemon, dashboard, MongoDB, Valkey on a private network |
| **Bare-metal `prexorctl setup`** | Linux hosts where Docker isn't appropriate (Ansible-managed fleets, kernel-level requirements) | Distro-native packages + systemd units |

The two paths produce equivalent clusters. `prexorctl setup --install-mode=compose`
generates the same Compose stack documented below; running them by hand or
via the CLI is a matter of preference.

## Path A — Docker Compose (recommended)

The reference Compose stack lives at `deploy/compose/` in the source tree
(or in the release tarball under `compose/`).

```bash
cd /opt
sudo tar -xzf ~/Downloads/prexorcloud-<version>-linux-amd64.tar.gz
sudo mv prexorcloud-<version> prexorcloud
cd prexorcloud/compose

cp .env.example .env
$EDITOR .env                # set HOST_HTTP_PORT, JVM_HEAP_*, MONGO_PASSWORD, etc.
docker compose up -d
```

What you get:

- `controller`, `daemon`, `dashboard`, `mongo`, `valkey` services on a
  private `prexor-internal` Docker network.
- Healthchecks on every service; pinned image tags.
- Bind-mounted `controller.yml` and `daemon.yml` you can edit without
  rebuilding the images.

Verify the stack:

```bash
docker compose ps
curl -s http://localhost:${HOST_HTTP_PORT:-8080}/api/v1/system/health
curl -s http://localhost:${HOST_HTTP_PORT:-8080}/api/v1/system/ready
```

`/system/ready` must return HTTP 200 with both `state.store=available` and
`coordination.store=available`. Anything else means the controller booted
but isn't serving mutations — typically Mongo or Valkey isn't reachable.

The bootstrap admin password is written once to
`./data/controller/config/.initial-admin-password` (mode `0600`). Read it,
log in, then **shred the file** (Step 5 below).

## Path B — Bare-metal `prexorctl setup`

On the controller host:

Linux / macOS:

```bash
# One-shot bootstrap. Reads /etc/os-release to detect the package manager.
curl -fsSL https://prexor.cloud/install.sh | sudo sh
prexorctl version

sudo prexorctl setup --role controller
```

Windows (PowerShell, admin shell if installing under Program Files):

```powershell
irm https://prexor.cloud/install.ps1 | iex
prexorctl version

prexorctl setup --role controller
```

The interactive setup will:

1. Detect your distro and offer to install Java 21, MongoDB, and Valkey if
   missing. Decline any you manage externally; you'll be prompted for the
   URI.
2. Generate `/etc/prexorcloud/config/controller.yml` with sensible defaults.
3. Generate the controller's internal CA under
   `/var/lib/prexorcloud/data/certs/`.
4. Print the bootstrap admin password and write it once to
   `config/.initial-admin-password` (`0600`).
5. Install and enable `prexorcloud-controller.service`.

Verify:

```bash
sudo systemctl status prexorcloud-controller
curl -s http://localhost:8080/api/v1/system/health
curl -s http://localhost:8080/api/v1/system/ready
```

Same readiness contract as the Compose path: both stores must report
`available`.

## Step 3 — First login

```bash
prexorctl login --controller https://<host>:8080
# Username: admin
# Password: <contents of .initial-admin-password>
prexorctl status
```

`status` should show your controller, **zero nodes**, and zero groups.

## Step 4 — Add your first daemon node

Daemons authenticate to the controller via mTLS. They don't have a cert yet,
so they need a **join token** — a one-time credential that the
`BootstrapService` exchanges for a per-daemon mTLS certificate.

On the controller:

```bash
prexorctl token create --description "node-1" --ttl 1h
# -> Token: prxn_...
```

On the daemon host:

```bash
sudo prexorctl setup --role daemon \
    --controller-grpc <controller-host>:9090 \
    --join-token prxn_...
```

The daemon setup flow:

1. Installs Java 21 if missing.
2. Generates `daemon.yml` with the join token.
3. Calls the unauthenticated `BootstrapService.Register` RPC, which mints a
   private key + cert via the controller's CA and returns them.
4. Writes the cert + key to `data/certs/`, deletes the join token from
   config, switches to mTLS for all future gRPC calls.
5. Installs `prexorcloud-daemon.service`.

Verify from any host:

```bash
prexorctl node list
```

The new node appears in `READY` state within ~10 seconds. Join tokens are
single-use — replay rejection happens server-side, so you can't accidentally
register the same daemon twice with the same token.

## Step 5 — Lock down the bootstrap

Within five minutes of the first login:

1. **Change the admin password** from the dashboard or via
   `prexorctl user set-password admin`.
2. **Shred the bootstrap file**:
   ```bash
   sudo shred -u /etc/prexorcloud/config/.initial-admin-password
   ```
3. **Restrict network exposure.** Set `network.allowedSubnets` in
   `controller.yml` to the operator and daemon subnets only. Restart the
   controller (`sudo systemctl restart prexorcloud-controller` or
   `docker compose restart controller`).
4. **Terminate TLS at a reverse proxy** if the controller REST is exposed
   beyond a private network. Set `http.trustedProxyCidrs` so
   `network.allowedSubnets` evaluates the real client IP.
5. **Enable module-signing enforcement** if you plan to install third-party
   modules: `modules.signing.required=true` plus a configured trust root.

## What `prexorctl setup` does *not* do

- Provision a load balancer for HA controllers.
- Configure host firewall rules. The controller listens on all interfaces
  by default — restrict via `iptables` / `nftables` or your cloud's
  security groups.
- Set up Prometheus scraping. Metrics live at
  `/api/v1/system/metrics/prometheus` (when `metrics.enabled=true`).
- Install MongoDB or Valkey with replica sets — the embedded option is
  single-node only. For HA-grade Mongo / Valkey, set them up externally and
  point the setup flow at their URIs.

## Common failures

| Symptom | Likely cause | Fix |
|---|---|---|
| `setup` fails detecting distro | Unsupported / minimal container image | Install dependencies manually, then re-run with `--skip-install`. |
| Controller fails to bind 8080 | Port already used | Change `http.port` and `http.healthPort` in `controller.yml`. |
| `system/ready` 503 with `coordination.store=unavailable` | Valkey/Redis not reachable | Verify `coordination.url`; restart Valkey. |
| `system/ready` 503 with `state.store=unavailable` | Mongo not reachable | Verify `state.url`; check Mongo logs. |
| Daemon `setup` fails on join | Token expired or wrong controller URL | Issue a fresh token; check controller is reachable from the daemon host. |

## Next up

- **[Quickstart (10 min)](/getting-started/quickstart/)** — first group,
  first instance, first players.
- **[Core Concepts](/getting-started/core-concepts/)** — the 10-minute
  orientation across groups, instances, templates, modules.
- **[Production Checklist](/operations/production-checklist/)** — the full
  hardening list before you put this in front of real players.
