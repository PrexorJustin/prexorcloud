# Upgrade

This runbook covers in-place upgrades of a PrexorCloud install. Same flow
applies to single-controller and HA deployments; HA gets zero-downtime
because lease handoff is automatic.

> **Upgrading from v1.0?** The v1.0 → v1.1 hop introduces the embedded
> Raft cluster control plane and is a one-time, one-way migration. Use
> [`upgrade-v1.0-to-v1.1.md`](upgrade-v1.0-to-v1.1.md) instead.

## Pre-flight

1. Read the release notes for every version between your current install
   and the target. Pay attention to:
   - Config schema changes (new required keys, deprecated keys).
   - Mongo schema migrations (the controller logs `migration applied:` on
     startup; some require a manual data backfill — release notes call
     these out).
   - Module SDK or capability changes that might break installed modules.
2. Verify the current install is healthy:
   ```bash
   prexorctl status
   curl -fs http://localhost:8080/api/v1/system/ready
   ```
3. Take a backup. Always. See [`backup.md`](backup.md).
4. If you have any installed modules, check
   `prexorctl module list` and confirm each is compatible with the target
   release (consult the SDK compat matrix at
   `dashboard/packages/module-sdk/COMPAT.md`).

## Single-Controller Upgrade

This path causes downtime — the controller is unavailable for ~10–60s.

```bash
# 1. Drain mutating work — finish in-flight deployments and don't start new ones.
prexorctl group list --maintenance
# Pause anything actively rolling.

# 2. Take backup.
prexorctl backup create

# 3. Stop the controller.
sudo systemctl stop prexorcloud-controller

# 4. Replace the binary / package.
#   Option A — package manager:
sudo apt-get install --only-upgrade prexorcloud-controller
#   Option B — manual jar swap:
sudo cp prexorcloud-controller-<new-version>.jar /opt/prexorcloud/lib/

# 5. Start.
sudo systemctl start prexorcloud-controller

# 6. Verify.
sudo journalctl -u prexorcloud-controller -n 100
curl -fs http://localhost:8080/api/v1/system/ready
prexorctl status
```

If `/system/ready` doesn't go green within two minutes:

```bash
sudo journalctl -u prexorcloud-controller --since "5 min ago" | grep -i ERROR
```

Most upgrade failures are config drift (a new required key) or a Mongo
migration that needs manual intervention. Roll back if needed
(see below).

## HA Controller Upgrade

Run controllers one at a time; the surviving controller picks up leases
automatically.

```bash
# On controller-1:
sudo systemctl stop prexorcloud-controller
# controller-2 acquires leases within ~lease-timeout seconds (default 15s).
# Verify on controller-2:
curl -fs http://controller-2:8080/api/v1/system/ready
prexorctl status

# Upgrade and start controller-1:
sudo apt-get install --only-upgrade prexorcloud-controller
sudo systemctl start prexorcloud-controller

# Wait until controller-1 reports ready.
curl -fs http://controller-1:8080/api/v1/system/ready

# Then repeat on controller-2.
```

While controllers run mixed versions, the schema must be backwards-
compatible. PrexorCloud guarantees this within a single minor release
(e.g. 0.7.x ↔ 0.7.y) and during one major hop (e.g. 0.7 ↔ 0.8). Skipping
majors (0.7 → 0.9) is **not** supported during a rolling upgrade — stop
all controllers, upgrade Mongo schema, then start them.

## Daemon Upgrade

Daemons are upgraded one at a time. The controller continues to schedule
work onto un-upgraded daemons.

```bash
# Drain the node first so running instances finish gracefully.
prexorctl node drain <node-id> --shutdown=false --timeout 5m

# Wait until the node reports zero running instances.
prexorctl node info <node-id>

# Stop, upgrade, start.
sudo systemctl stop prexorcloud-daemon
sudo apt-get install --only-upgrade prexorcloud-daemon
sudo systemctl start prexorcloud-daemon

# Confirm.
prexorctl node list
prexorctl node undrain <node-id>
```

## Module Upgrade

State-preserving hot reload is intentionally not in scope (see
[`../decisions.md`](../decisions.md) §"No hot-reload UX"). Upgrading a
module triggers a planned controller restart for that group:

```bash
prexorctl module upload ./my-module-2.0.0.jar
# This creates a new module-package record. Existing instances keep the
# previous version until the group is redeployed.
prexorctl group deploy <group> --module my-module=2.0.0
```

The deploy command performs a rolling restart of the affected instances.
Watch `prexorctl group info <group>`.

## Rollback

If the upgrade fails:

1. Stop the controller.
2. Reinstall the previous package version:
   ```bash
   sudo apt-get install prexorcloud-controller=<previous-version>
   ```
3. Restore the backup taken in pre-flight (see [`restore.md`](restore.md)).
   Restore is only required if a Mongo schema migration ran during the
   failed upgrade — release notes will say.
4. Start the controller and verify.

For HA, roll back the upgraded controllers in reverse order before
restoring data.

## Validation Checklist

After a successful upgrade, confirm:

- [ ] `/system/ready` returns 200 on every controller.
- [ ] `prexorctl status` lists all expected nodes in `READY`.
- [ ] `prexorctl group list` shows expected groups, no
  `desiredVersion != currentVersion` drift.
- [ ] `prexorctl module list` shows each installed module in `ACTIVE`.
- [ ] `prexorctl crash list --since "10 min ago"` is empty (or only shows
  pre-existing entries).
- [ ] No new errors in `journalctl -u prexorcloud-controller --since "10 min ago"`.

## Common Failures

| Symptom                                                | Likely cause                                      | Fix                                       |
| ------------------------------------------------------ | ------------------------------------------------- | ----------------------------------------- |
| Controller fails to start, log says `unknown config key` | Removed key still present in `controller.yml` | Edit out the key, restart.                 |
| Controller starts but `coordination.store=unavailable` | New release requires a Valkey/Redis feature      | Upgrade Valkey/Redis to the documented minimum. |
| Daemons disconnect after upgrade                       | mTLS client trust changed                         | Re-issue daemon certificates; see [`rotate-secrets.md`](rotate-secrets.md). |
| Module install rejected after upgrade                  | Manifest schema bumped                            | Re-publish the module against the new SDK; existing installs continue running. |
| Audit log entries spike "migration applied"            | Normal — schema migrations run once on startup    | None. Confirm no `migration failed` entries follow. |

## Related

- [`backup.md`](backup.md) — required pre-flight.
- [`restore.md`](restore.md) — rollback path.
- [`recover-controller.md`](recover-controller.md) — if upgrade leaves the
  controller in a non-startable state.
- [`upgrade-v1.0-to-v1.1.md`](upgrade-v1.0-to-v1.1.md) — one-time
  migration to the embedded-Raft cluster control plane.
