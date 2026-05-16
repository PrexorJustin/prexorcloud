# Install

This runbook installs a single-controller PrexorCloud production environment
on a Linux host using `prexorctl setup`.

For high availability, repeat the controller install on a second host and
point both at the same MongoDB and Valkey/Redis. See
[`architecture.md`](../architecture.md) §"HA model".

## Prerequisites

| Component                    | Minimum   | Notes                                |
| ---------------------------- | --------- | ------------------------------------ |
| OS                           | Linux x86_64 (Debian, Ubuntu, RHEL, Fedora, openSUSE, Arch) | macOS / Windows controller hosts are not supported. |
| Java                         | OpenJDK 21+ | The setup flow installs it via your distro's package manager if missing. |
| MongoDB                      | 6.0+      | Self-hosted; PrexorCloud does not embed Mongo. |
| Valkey or Redis              | Valkey 7.2+ / Redis 7+ | **Required** in production. |
| Free TCP ports               | 8080, 9090, 9091 | Defaults; configurable. |
| Filesystem                   | 10 GB free under the install root | Per controller; growth scales with module storage and audit log retention. |
| User account                 | Non-root system user, e.g. `prexorcloud` | Created by the setup flow. |

The setup flow can install MongoDB and Valkey for you on the same host (dev
mode) or accept URIs to externally-managed instances (production mode).

## Step 1 — Bootstrap the Host

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
sudo prexorctl setup --role controller
```

The interactive setup will:

1. Detect your Linux distribution (`detect.go`) and the package manager.
2. Offer to install Java 21, MongoDB, and Valkey if missing. Decline any
   you manage externally; you will be prompted for the URI.
3. Generate `config/controller.yml` under the install root (default
   `/etc/prexorcloud/`).
4. Generate the controller CA under `data/certs/`.
5. Print the bootstrap admin password and write it once to
   `config/.initial-admin-password` (mode `0600`).
6. Install and enable a systemd unit `prexorcloud-controller.service`.

For a non-interactive run pass `--yes` and the required flags. See
`prexorctl setup --help`.

## Step 3 — Verify

```bash
sudo systemctl status prexorcloud-controller
curl -s http://localhost:8080/api/v1/system/health
curl -s http://localhost:8080/api/v1/system/ready
```

`/system/ready` must return HTTP 200 with `state.store=available` and
`coordination.store=available`. Anything else means the controller is up
but not serving mutations — see [`troubleshoot.md`](troubleshoot.md).

## Step 4 — First Login

```bash
prexorctl login --controller https://<host>:8080
# Username: admin
# Password: <contents of /etc/prexorcloud/config/.initial-admin-password>
prexorctl status
```

`status` should show your controller, zero nodes, and zero groups.

## Step 5 — Lock Down the Bootstrap

Within five minutes of the first login:

1. Change the admin password from the dashboard or via
   `prexorctl user set-password admin`.
2. Delete the bootstrap file:
   ```bash
   sudo shred -u /etc/prexorcloud/config/.initial-admin-password
   ```
3. Set `network.allowedSubnets` in `controller.yml` to the operator and
   daemon subnets only. Restart the controller:
   ```bash
   sudo systemctl restart prexorcloud-controller
   ```
4. If the controller REST is exposed beyond a private network, put it
   behind a TLS-terminating reverse proxy. Set
   `http.trustedProxyCidrs` so `network.allowedSubnets` evaluates the real
   client IP.

## Step 6 — Add a Daemon Node

On each daemon host:

```bash
# Issue a join token from the controller host.
prexorctl token create --description "node-1" --ttl 1h
# -> Token: prxn_...

# On the daemon host:
sudo prexorctl setup --role daemon \
    --controller-grpc <controller-host>:9090 \
    --join-token prxn_...
```

The setup flow on a daemon host:

1. Installs Java 21 if missing.
2. Generates `config/daemon.yml` and registers the node with the
   controller, exchanging the join token for a per-daemon mTLS certificate
   stored under `data/certs/`.
3. Installs `prexorcloud-daemon.service`.

Verify from any host:

```bash
prexorctl node list
```

The new node should appear in `READY` state within ~10 seconds.

## Step 7 — Apply Production Hardening

See [`SECURITY.md`](../../SECURITY.md) §"Hardening Checklist" for the full
list. Minimum:

- Set `runtime.profile=production` (the setup flow does this when you
  point at external Mongo and Valkey).
- Enable `security.lockout` (default on).
- Enable `modules.signing.required=true` and configure
  `modules.signing.trustRoot`.
- Configure backups per [`backup.md`](backup.md).

## What `prexorctl setup` Does Not Do

- Provision a load balancer for HA controllers.
- Configure firewall rules. The controller listens on all interfaces by
  default; restrict via `iptables`/`nftables` or your cloud's security
  groups.
- Set up Prometheus scraping. Metrics live at
  `/api/v1/system/metrics/prometheus` (when enabled).
- Install MongoDB or Valkey in production mode with replica sets — the
  embedded option is single-node only.

## Common Failures

| Symptom                                                | Likely cause                                   | Fix                                       |
| ------------------------------------------------------ | ---------------------------------------------- | ----------------------------------------- |
| `setup` fails detecting distro                         | Unsupported / minimal container image          | Install dependencies manually, then re-run with `--skip-install`. |
| Controller fails to bind 8080                          | Port already used                              | Change `http.port` and `http.healthPort`. |
| `system/ready` returns 503 with `coordination.store=unavailable` | Valkey/Redis not reachable          | Verify URI; see [`recover-redis.md`](recover-redis.md). |
| `system/ready` returns 503 with `state.store=unavailable` | Mongo not reachable                         | Verify URI; see [`recover-mongo.md`](recover-mongo.md). |
| Daemon `setup` fails on join                           | Token expired or wrong controller URL          | Issue a fresh token; check controller is reachable from daemon host. |

## Next

- [`backup.md`](backup.md) — set up backups before deploying real workloads.
- [`scale.md`](scale.md) — add additional daemon nodes.
- [`upgrade.md`](upgrade.md) — keep the install current.
