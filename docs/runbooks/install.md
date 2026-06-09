# Install

This runbook installs a single-Controller PrexorCloud production environment
on a Linux host using `prexorctl setup`.

For high availability, repeat the Controller install on a second host and
point both at the same MongoDB and Valkey/Redis. See
[architecture](../public/en/concepts/architecture.md) for the HA model.

## Prerequisites

| Component                    | Minimum   | Notes                                |
| ---------------------------- | --------- | ------------------------------------ |
| OS                           | Linux x86_64 (Debian, Ubuntu, RHEL, Fedora, openSUSE, Arch) | macOS / Windows Controller hosts are not supported. |
| Java                         | OpenJDK 21+ | The setup flow installs it via your distro's package manager if missing. |
| MongoDB                      | 6.0+      | Self-hosted; PrexorCloud does not embed Mongo. |
| Valkey or Redis              | Valkey 7.2+ / Redis 7+ | **Required** in production. |
| Free TCP ports               | 8080, 9090, 9190 | Controller HTTP, gRPC, and Raft defaults; configurable. |
| Filesystem                   | 10 GB free under the install root | Per Controller; growth scales with Module storage and audit log retention. |
| User account                 | Non-root system user, e.g. `prexorcloud` | Created by the setup flow. |

The setup flow can install MongoDB and Valkey for you on the same host (dev
mode) or accept URIs to externally-managed instances (production mode).

## Step 1 — Bootstrap the host

```bash
# Run once, as root or via sudo.
curl -fsSL https://prexor.cloud/install.sh | sudo sh
```

This places the `prexorctl` binary on `PATH`. Verify:

```bash
prexorctl version
```

## Step 2 — Run `prexorctl setup`

```bash
sudo prexorctl setup --component controller
```

The interactive setup will:

1. Detect your Linux distribution (`detect.go`) and the package manager.
2. Offer to install Java 21, MongoDB, and Valkey if missing. Decline any
   you manage externally; you will be prompted for the URI.
3. Generate `config/controller.yml` under the install root (default
   `/opt/prexorcloud/controller`).
4. Generate the Controller CA at `config/security/ca.p12`.
5. Print the bootstrap admin password and write it once to
   `config/.initial-admin-password` (mode `0600`).
6. Install and enable a systemd unit `prexorcloud-controller.service`.

For a non-interactive run pass `--non-interactive` and the required flags. See
`prexorctl setup --help`.

## Step 3 — Verify

```bash
sudo systemctl status prexorcloud-controller
curl -s http://localhost:8080/api/v1/system/health
curl -s http://localhost:8080/api/v1/system/ready
```

`/system/ready` must return HTTP 200 with `"status":"READY"` and every entry
in `checks` true (`mongo`, `redis`, `scheduler`, `platformModules`). Anything
else means the Controller is up but not serving mutations — see
[`troubleshoot.md`](troubleshoot.md).

## Step 4 — First login

```bash
prexorctl login --controller https://<host>:8080
# Username: admin
# Password: <contents of /opt/prexorcloud/controller/config/.initial-admin-password>
prexorctl status
```

`status` should show your Controller, zero nodes, and zero Groups.

## Step 5 — Lock down the bootstrap

Within five minutes of the first login:

1. Change the admin password from the dashboard, or via
   `POST /api/v1/auth/change-password` with `currentPassword` and
   `newPassword` (minimum 8 characters).
2. Delete the bootstrap file:
   ```bash
   sudo shred -u /opt/prexorcloud/controller/config/.initial-admin-password
   ```
3. Set `network.allowedSubnets` in `controller.yml` to the operator and
   Daemon subnets only. Restart the Controller:
   ```bash
   sudo systemctl restart prexorcloud-controller
   ```
4. If the Controller REST is exposed beyond a private network, put it behind
   a TLS-terminating reverse proxy. `network.allowedSubnets` evaluates the
   connecting peer IP, so behind a proxy it sees the proxy's address — add the
   proxy's IP to `allowedSubnets` and enforce client filtering at the proxy.

## Step 6 — Add a Daemon node

On each Daemon host:

```bash
# Issue a join token from the Controller host.
prexorctl token create --node node-1 --ttl 1h
# -> Join Token: prxn_...

# On the Daemon host:
sudo prexorctl setup --component daemon \
    --daemon-node-id node-1 \
    --daemon-controller-host <controller-host> \
    --daemon-controller-grpc-port 9090 \
    --daemon-join-token prxn_...
```

The setup flow on a Daemon host:

1. Installs Java 21 if missing.
2. Generates `config/daemon.yml` and registers the node with the Controller,
   exchanging the join token for a per-Daemon mTLS certificate stored under
   `config/security/`.
3. Installs `prexorcloud-daemon.service`.

Verify from any host:

```bash
prexorctl node list
```

The new node should appear in `READY` state within ~10 seconds.

## Step 7 — Apply production hardening

See [`SECURITY.md`](../../SECURITY.md) §"Hardening Checklist" for the full
list. Minimum:

- Set `runtime.profile=production` (the setup flow does this when you
  point at external Mongo and Valkey).
- Enable `security.lockout` (default on).
- Enable `modules.signing.required=true` and configure
  `modules.signing.trustRoot`.
- Configure backups per [`backup.md`](backup.md).

## What `prexorctl setup` does not do

- Provision a load balancer for HA Controllers.
- Configure firewall rules. The Controller listens on all interfaces by
  default; restrict via `iptables`/`nftables` or your cloud's security
  groups.
- Set up Prometheus scraping. Metrics live at `/metrics` (when
  `metrics.enabled` is true).
- Install MongoDB or Valkey in production mode with replica sets — the
  embedded option is single-node only.

## Common failures

| Symptom                                                | Likely cause                                   | Fix                                       |
| ------------------------------------------------------ | ---------------------------------------------- | ----------------------------------------- |
| `setup` fails detecting distro                         | Unsupported / minimal container image          | Install the dependencies manually, then re-run `setup` and decline its install offers. |
| Controller fails to bind 8080                          | Port already in use                            | Free the port, or change `http.port`.     |
| `system/ready` returns 503 with `checks.redis: false`  | Valkey/Redis not reachable                     | Verify the URI; see [`recover-redis.md`](recover-redis.md). |
| `system/ready` returns 503 with `checks.mongo: false`  | Mongo not reachable                            | Verify the URI; see [`recover-mongo.md`](recover-mongo.md). |
| Daemon `setup` fails on join                           | Token expired or wrong Controller URL          | Issue a fresh token; check the Controller is reachable from the Daemon host. |

## Next

- [`backup.md`](backup.md) — set up backups before deploying real workloads.
- [`scale.md`](scale.md) — add more Daemon nodes.
- [`upgrade.md`](upgrade.md) — keep the install current.
</content>
</invoke>
