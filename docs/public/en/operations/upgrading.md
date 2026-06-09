---
title: Upgrading
description: Upgrade the Controller and Daemons safely — version checks, protocol compatibility, dry-run, rolling HA upgrades, and the rollback path.
---

Upgrade in place, one process at a time. Two facts make it safe:

- Controllers are active-active over shared MongoDB + Redis-protocol
  (Valkey/Redis). Stopping one hands its work to the survivors.
- Daemons are stateless re-attachers. A Daemon that restarts re-runs
  the handshake, re-reports its running Instances, and the Controller
  reconciles.

Walk the steps in order and you upgrade an HA install with no
maintenance window. A single-Controller install takes ~10-60s of
downtime on the Controller restart.

> Upgrading from v1.0 to v1.1? That hop introduces the embedded Raft
> cluster control plane and is a one-time, one-way migration with its
> own procedure. Use the
> [v1.0 to v1.1 runbook](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/upgrade-v1.0-to-v1.1.md)
> instead of this page, then return here for routine v1.x upgrades.

## What you'll do here

- Run the pre-flight every upgrade owes you.
- Check version and protocol compatibility before you touch anything.
- Roll a single Controller (downtime) or an HA set (zero downtime).
- Upgrade Daemons and Modules.
- Roll back when an upgrade goes wrong, with a dry-run first.

## Version and protocol compatibility

Two version numbers matter. They are independent.

| Number | Where it lives | What it gates |
|---|---|---|
| Software version | `version.properties` → `GET /api/v1/system/version` (`version`, `gitCommit`, `javaVersion`) | Build identity. Surfaced by `prexorctl version`. |
| Protocol version | `ProtocolConstants.PROTOCOL_VERSION` (`"1.0"`) and the gRPC `Handshake.protocol_version` int (`1`) | Whether a Daemon may attach to a Controller. |

### Check what you're running

```bash
prexorctl version
```

`prexorctl version` prints a CLI card and, when you have an active
authenticated context, a CONTROLLER card. The Controller card comes
from `GET /api/v1/system/version` and contains `version`, `gitCommit`,
and `javaVersion`. Get JSON for scripting:

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
  "controller_gitCommit": "9275c7e",
  "controller_javaVersion": "25"
}
```

The `controller_*` keys appear only when the CLI can reach an
authenticated Controller. Without a token you get the CLI fields only.

### How protocol compatibility is enforced

The Controller-to-Daemon gRPC handshake is the compatibility gate, not
the software version.

- Each Daemon sends `Handshake.protocol_version = 1` on connect, plus
  its software `version` string.
- The Controller rejects any handshake with `protocol_version < 1`. The
  Daemon stream fails with gRPC `FAILED_PRECONDITION` and the message
  `Unsupported daemon protocol version: <n>`.
- On a valid handshake the Controller replies with `HandshakeAck`
  carrying `protocol_version = 1` and
  `protocol_compatible = (daemon protocol_version >= 1)`. When
  `protocol_compatible` is `false`, the Daemon disconnects and the
  operator must upgrade it.

Additive protocol changes do not bump the protocol version. New `oneof`
payload variants and additive scalar fields (for example the
`traceparent` field on `ControllerMessage`) are ignored by older peers,
so a Daemon and Controller one software version apart still handshake.
A protocol-version bump is a breaking change and is called out in the
release notes; treat it as a coordinated upgrade, not a rolling one.

### Software version skew during a rolling upgrade

While an HA set runs mixed software versions, the Mongo schema and the
Redis-protocol keyspace must stay backwards-compatible. The project
guarantees this within a single minor release and across one major hop,
and does not support skipping a major during a rolling upgrade — stop
all Controllers, upgrade the schema, then start them. Read the release
notes for the exact compatibility window of your target version; the
notes are authoritative.

## Pre-flight (always)

1. Read the release notes for every version between your current
   install and the target. Look for:
   - Config schema changes — new required keys, removed keys. A removed
     key still present in `controller.yml` blocks startup.
   - Mongo schema migrations and any manual data backfill they call out.
   - Protocol-version bumps (coordinated upgrade required).
   - Module SDK or capability changes that affect installed Modules.

2. Confirm the current install is healthy.

   ```bash
   prexorctl status
   curl -fs http://localhost:8080/api/v1/system/ready
   ```

   `GET /api/v1/system/ready` returns `200` with
   `{"status":"READY","checks":{...}}` when every check passes, or `503`
   with `{"status":"NOT_READY",...}` otherwise. The checks are:

   | Check | Green when |
   |---|---|
   | `mongo` | The state store is reachable. |
   | `redis` | Coordination (Valkey/Redis) is reachable. |
   | `scheduler` | The Controller holds an active scheduler. |
   | `platformModules` | The platform module manager is up. |

3. Take a backup. Always.

   ```bash
   prexorctl backup create
   ```

   `backup create` triggers the Controller to dump Mongo, Redis, and
   on-disk security/Template/Module state into a bundle in the
   Controller-side backup directory. The CLI does not transport the
   bundle — copy it off-host for off-site retention. See
   [Backups and DR](/operations/backups-and-dr/).

4. Check Module compatibility against the target release.

   ```bash
   prexorctl module list
   ```

   Confirm each installed Module is compatible with the target. The SDK
   compatibility matrix lives at
   `dashboard/packages/module-sdk/COMPAT.md`.

5. Verify the release artifacts are signed before you install them.

   ```bash
   cosign verify-blob \
     --certificate-identity-regexp "^https://github.com/prexorjustin/prexorcloud/.github/workflows/release.yml@refs/tags/" \
     --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
     --signature checksums.txt.sig \
     --certificate checksums.txt.pem \
     checksums.txt
   sha256sum -c checksums.txt
   ```

## Single-Controller upgrade

This path causes ~10-60s of downtime. Use it only when you do not run
HA.

```bash
# 1. Finish in-flight mutating work; don't start new deployments.
prexorctl deploy list <group>   # confirm no deployment is mid-rollout

# 2. Take a backup.
prexorctl backup create

# 3. Stop the Controller.
sudo systemctl stop prexorcloud-controller

# 4. Replace the binary / package / image.
#    A — package manager:
sudo apt-get install --only-upgrade prexorcloud-controller
#    B — manual jar swap:
sudo cp prexorcloud-controller-<new-version>.jar /opt/prexorcloud/lib/
#    C — Docker Compose:
docker compose pull controller
docker compose up -d controller

# 5. Start (skip for the Compose path; up -d already started it).
sudo systemctl start prexorcloud-controller

# 6. Watch it come back.
sudo journalctl -u prexorcloud-controller -f
```

Verify:

```bash
curl -fs http://localhost:8080/api/v1/system/ready
prexorctl status
prexorctl version
```

If `/system/ready` does not return `200` within two minutes, read the
checks in the body to see which subsystem is down, then scan the log:

```bash
curl -s http://localhost:8080/api/v1/system/ready | jq .checks
sudo journalctl -u prexorcloud-controller --since "5 min ago" | grep -i ERROR
```

Most upgrade failures are config drift (a new required key, or a removed
key still present) or a datastore that the new release can't reach. Roll
back if needed — see [Rollback](#rollback).

The systemd unit names are `prexorcloud-controller.service` and
`prexorcloud-daemon.service`. The Compose services are `controller`,
`daemon`, `dashboard`, `mongo`, and `redis`.

## HA Controller upgrade (zero downtime)

Run Controllers one at a time. The surviving Controller picks up leases
automatically after the stopped one loses its coordination session.

```bash
# On controller-1:
sudo systemctl stop prexorcloud-controller

# controller-2 acquires leases within the lease timeout.
# Verify on controller-2:
curl -fs http://controller-2:8080/api/v1/system/ready
prexorctl status

# Upgrade and restart controller-1.
sudo apt-get install --only-upgrade prexorcloud-controller
sudo systemctl start prexorcloud-controller

# Wait until controller-1 reports ready.
curl -fs http://controller-1:8080/api/v1/system/ready

# Then repeat on controller-2.
```

```mermaid
sequenceDiagram
  participant C1 as controller-1
  participant V as Valkey (leases)
  participant C2 as controller-2
  Note over C1,C2: Both serving traffic, leases distributed
  C1->>V: stop heartbeat
  Note over C1: stopped for upgrade
  V-->>C2: lease expired (after ~timeout)
  C2->>V: acquire lease, bump fencing token
  Note over C2: serves all traffic with fresh tokens
  C1->>V: restart, request leases
  V-->>C1: distribute new lease set
  Note over C1,C2: both serving again
```

While Controllers run mixed software versions, the schema must stay
backwards-compatible — see
[Software version skew](#software-version-skew-during-a-rolling-upgrade).
Skipping a major during a rolling upgrade is not supported; stop all
Controllers, upgrade the schema, then start them.

### v1.1 clusters

On a v1.1 embedded-Raft cluster, the cluster-shared config and identity
live in the Raft state machine rather than in each `controller.yml`. The
rolling order above still holds: upgrade one Controller at a time and
keep quorum. After each step, confirm membership and leadership are
stable.

```bash
prexorctl cluster status     # member count, active config version, leader
prexorctl cluster members    # every controller READY, matching clusterId
```

Keep an odd member count (3 tolerates one failure, 5 tolerates two) so
quorum survives the one node you have down at a time.

## Daemon upgrade

Upgrade Daemons one at a time. The Controller keeps scheduling onto the
Daemons you haven't touched yet.

```bash
# Drain the node so the scheduler stops placing new Instances on it.
prexorctl node drain <node-id>

# Wait until the node reports zero running Instances.
prexorctl node info <node-id>

# Stop, upgrade, start.
sudo systemctl stop prexorcloud-daemon
sudo apt-get install --only-upgrade prexorcloud-daemon
sudo systemctl start prexorcloud-daemon

# Confirm the node reconnected, then clear the drain mark.
prexorctl node list
prexorctl node undrain <node-id>
```

`prexorctl node drain <id>` and `prexorctl node undrain <id>` take only a
node id — no rollout flags. Watch the running-Instance count drain to
zero with `prexorctl node info <id>` before you stop the Daemon, so live
Instances move or finish cleanly first.

The Daemon's existing mTLS certificate carries across software upgrades —
nothing to re-issue. The handshake's `protocol_version` (`1`) is what
the Controller checks; the certificate authenticates the transport and
is unaffected by a software-version bump. If a release bumps the
protocol version, the Daemon's `HandshakeAck.protocol_compatible` comes
back `false` and the Daemon disconnects until you upgrade it — that is a
coordinated upgrade, called out in the release notes.

## Module upgrade

State-preserving hot reload is intentionally not supported. Upgrading a
Module installs a new module-package, then a rolling deployment
propagates it to running Instances.

Install the new version from a configured registry or a local bundle:

```bash
# From a registry (newest, or pinned):
prexorctl module upgrade <module-id>          # one Module to its newest
prexorctl module upgrade --all                # every Module with a newer version
prexorctl module install <module-id>@2.0.0    # pin an exact version

# From a local signed bundle:
prexorctl module install ./my-module-2.0.0.jar
```

`module upgrade` and `module install` re-verify the artifact's sha256
and signature against the Controller's trust root before installing — a
signature failure returns `422 SIGNATURE_VERIFICATION_FAILED`. Existing
Instances keep the previous Module version until the Group is
redeployed.

Propagate the new composition with a rolling deployment:

```bash
prexorctl deploy <group>
```

`prexorctl deploy <group>` rolls the Group's current Template chain and
Module composition to running Instances. Tune the rollout:

| Flag | Effect |
|---|---|
| `--strategy <name>` | Rollout strategy; overrides the Group default. |
| `--batch-size <n>` | Instances rolled per batch (≥ 1). |
| `--canary-instances <n>` | Number of canary Instances (≥ 0). |
| `--canary-percent <n>` | Canary percentage (0-100); mutually exclusive with `--canary-instances`. |
| `--health-gate` | Require a canary health gate before promoting the rollout. |
| `--min-healthy <s>` | Minimum healthy seconds before advancing a batch (≥ 0). |
| `--auto-rollback` | Roll back automatically on rollout failure. |
| `--promotion-timeout <s>` | Promotion timeout in seconds (≥ 1). |
| `-y`, `--yes` | Skip the rollout confirmation prompt. |

Omitted flags fall back to the Group's update-strategy defaults. Use a
canary plus `--health-gate` and `--auto-rollback` for a careful Module
rollout:

```bash
prexorctl deploy <group> \
  --canary-instances 1 \
  --health-gate \
  --min-healthy 60 \
  --auto-rollback
```

Watch progress:

```bash
prexorctl group info <group>
prexorctl deploy list <group>
prexorctl deploy show <group> <rev>
```

Pause, resume, or roll back a deployment mid-flight:

```bash
prexorctl deploy pause <group>
prexorctl deploy resume <group>
prexorctl deploy rollback <group> <rev>
```

`deploy rollback` marks the deployment `ROLLED_BACK`. It stops the
rollout; restoring the previous Template/Module state is operator-driven
— redeploy the prior composition or restore from backup if the change
already landed everywhere.

## Dashboard upgrade

The dashboard is a separate static bundle served independently. Replace
it on its own.

```bash
# Compose:
docker compose pull dashboard
docker compose up -d dashboard
```

For a native install, ship the new static bundle and restart whatever
serves it (systemd unit, nginx). The dashboard talks to the Controller
over the same REST API; keep it within one minor version of the
Controller.

## Rollback

Roll back when an upgrade fails to come ready or the new version
misbehaves.

### Dry-run a restore first

Before applying any datastore restore, validate the bundle and see the
planned changes without writing anything:

```bash
prexorctl backup verify <backup-id>
prexorctl restore <backup-id> --dry-run
```

`--dry-run` reports the filesystem entry count, Mongo collections, Mongo
prefix groups, and Redis prefixes that a real restore would touch, then
exits without mutating state. A restore is rejected outright if the
bundle fails verification — run `backup verify` first to see the gap.

### Roll back the binary

```bash
# 1. Stop the Controller(s).
sudo systemctl stop prexorcloud-controller

# 2. Reinstall the previous package version.
sudo apt-get install prexorcloud-controller=<previous-version>

# 3. Restore data ONLY if a Mongo schema migration ran during the
#    failed upgrade (the release notes say so). Otherwise skip this.
prexorctl restore <backup-id>            # add --no-files or --no-data to scope it

# 4. Start and verify.
sudo systemctl start prexorcloud-controller
curl -fs http://localhost:8080/api/v1/system/ready
prexorctl status
```

`prexorctl restore <id>` restores both the on-disk filesystem and the
live Mongo + Redis stores by default. Scope it with `--no-files` (skip
the filesystem) or `--no-data` (skip the datastores). See
[Backups and DR](/operations/backups-and-dr/).

For HA, roll the upgraded Controllers back in reverse order before
restoring any data. Roll back a Daemon the same way — stop it, reinstall
the previous package, start it; the mTLS certificate is unchanged so it
re-attaches.

## Validation checklist

After a successful upgrade, confirm:

- [ ] `GET /api/v1/system/ready` returns `200` (`"status":"READY"`) on
      every Controller.
- [ ] `prexorctl version` shows the expected Controller `version`.
- [ ] `prexorctl status` lists every expected node in `READY`.
- [ ] `prexorctl cluster members` (v1.1) shows each Controller `READY`
      with a matching `clusterId`; `cluster status` shows a stable
      leader.
- [ ] `prexorctl group list` shows the expected Groups with no
      version drift.
- [ ] `prexorctl module list` shows each installed Module active.
- [ ] `prexorctl crash list --since "$(date -u -d '10 min ago' +%Y-%m-%dT%H:%M:%SZ)"`
      is empty, or shows only pre-existing entries.
- [ ] No new errors:
      `journalctl -u prexorcloud-controller --since "10 min ago" | grep -i ERROR`.

`crash list` filters on `--since` (ISO 8601), `--group`, and `--node`.

## Common failures

| Symptom | Likely cause | Fix |
|---|---|---|
| Controller won't start, log says unknown config key | A removed key is still in `controller.yml` | Remove the key, restart. |
| `/system/ready` returns 503 with `mongo:false` | State store unreachable from the new build | Confirm Mongo is up and the connection string is unchanged. |
| `/system/ready` returns 503 with `redis:false` | New release needs a Valkey/Redis feature or version | Upgrade Valkey/Redis to the documented minimum. |
| Daemon handshake fails with `Unsupported daemon protocol version` | Daemon older than the Controller across a protocol bump | Upgrade the Daemon; protocol bumps are coordinated, not rolling. |
| Daemon connects then disconnects after upgrade | `HandshakeAck.protocol_compatible=false` (protocol skew) | Upgrade the Daemon to match the Controller's protocol version. |
| Module install rejected with `422 SIGNATURE_VERIFICATION_FAILED` | Artifact signature doesn't match the trust root | Re-fetch a correctly signed build, or fix the configured trust root. |
| HA peer can't take leases after one upgrades | A major was skipped during a rolling upgrade | Stop all Controllers, upgrade the schema in lockstep, start them. |

## Why HA rolling works

Two mechanisms make it safe:

1. Lease + fencing tokens. When a surviving Controller takes a lease the
   stopped one just lost, the fencing token bumps. The stopped
   Controller cannot mutate under the old token even if it comes back
   unaware.
2. Persisted intent. In-flight rolling restarts, drains, placements, and
   Module mutations are persisted, so the new lease holder reads the
   intent and resumes deterministically rather than restarting it.

See [Architecture](/concepts/architecture/).

## Related

- [Backups and DR](/operations/backups-and-dr/) — the pre-flight backup
  and the restore path used by rollback.
- [HA setup](/operations/ha-setup/) — multi-Controller install and lease
  semantics.
- [Production checklist](/operations/production-checklist/) — pre-launch
  hardening.
- [v1.0 to v1.1 runbook](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/upgrade-v1.0-to-v1.1.md)
  — the one-time embedded-Raft migration.
