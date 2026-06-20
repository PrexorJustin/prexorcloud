# Upgrade

This runbook covers in-place upgrades of a PrexorCloud install. The same flow
applies to single-Controller and HA deployments; HA gets zero downtime
because lease handoff is automatic.


## Pre-flight

1. Read the release notes for every version between your current install
   and the target. Look for:
   - Config schema changes (new required keys, removed keys). A removed
     key still present in `controller.yml` blocks startup.
   - Mongo schema migrations (the Controller logs `migration applied:` on
     startup; some require a manual data backfill — release notes call
     these out).
   - Module SDK or capability changes that affect installed Modules.
2. Confirm the current install is healthy:
   ```bash
   prexorctl status
   curl -fs http://localhost:8080/api/v1/system/ready
   ```
3. Take a backup. Always. See [`backup.md`](backup.md).
4. If you have any installed Modules, run
   `prexorctl module list` and confirm each is compatible with the target
   release (consult the SDK compat matrix at
   `dashboard/packages/module-sdk/COMPAT.md`).

## Single-Controller upgrade

This path causes downtime — the Controller is unavailable for ~10–60s.

```bash
# 1. Finish in-flight mutating work; don't start new deployments.
prexorctl deploy list <group>   # confirm no deployment is mid-rollout

# 2. Take backup.
prexorctl backup create

# 3. Stop the Controller.
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

## HA Controller upgrade

Run Controllers one at a time; the surviving Controller picks up leases
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

While Controllers run mixed versions, the schema must stay backwards-
compatible. PrexorCloud guarantees this within a single minor release
(e.g. 0.7.x ↔ 0.7.y) and during one major hop (e.g. 0.7 ↔ 0.8). Skipping
majors (0.7 → 0.9) is **not** supported during a rolling upgrade — stop
all Controllers, upgrade the Mongo schema, then start them.

## Daemon upgrade

Upgrade Daemons one at a time. The Controller keeps scheduling work onto
the Daemons you haven't touched yet.

```bash
# Drain the node first so running Instances finish gracefully.
prexorctl node drain <node-id>

# Wait until the node reports zero running Instances.
prexorctl node info <node-id>

# Stop, upgrade, start.
sudo systemctl stop prexorcloud-daemon
sudo apt-get install --only-upgrade prexorcloud-daemon
sudo systemctl start prexorcloud-daemon

# Confirm.
prexorctl node list
prexorctl node undrain <node-id>
```

## Module upgrade

State-preserving hot reload is intentionally not supported (see
[`../engineering/decisions.md`](../engineering/decisions.md) ADR 20).
Upgrading a Module installs a new module-package, then a rolling
deployment propagates it to running Instances:

```bash
# Install the new version (registry pin or local signed bundle).
prexorctl module install my-module@2.0.0
# Or from a file:
prexorctl module install ./my-module-2.0.0.jar
# Existing Instances keep the previous version until the Group is redeployed.

# Propagate the new composition with a rolling deployment.
prexorctl deploy <group>
```

`prexorctl deploy <group>` rolls the Group's current Template chain and
Module composition to its running Instances. Watch progress with
`prexorctl group info <group>`.

## Rollback

If the upgrade fails:

1. Stop the Controller.
2. Reinstall the previous package version:
   ```bash
   sudo apt-get install prexorcloud-controller=<previous-version>
   ```
3. Restore the backup taken in pre-flight (see [`restore.md`](restore.md)).
   A restore is only required if a Mongo schema migration ran during the
   failed upgrade — the release notes say so.
4. Start the Controller and verify.

For HA, roll back the upgraded Controllers in reverse order before
restoring data.

## Validation checklist

After a successful upgrade, confirm:

- [ ] `/system/ready` returns 200 on every Controller.
- [ ] `prexorctl status` lists all expected nodes in `READY`.
- [ ] `prexorctl group list` shows the expected Groups, no
  `desiredVersion != currentVersion` drift.
- [ ] `prexorctl module list` shows each installed Module in `ACTIVE`.
- [ ] `prexorctl crash list --since "10 min ago"` is empty (or only shows
  pre-existing entries).
- [ ] No new errors in `journalctl -u prexorcloud-controller --since "10 min ago"`.

## Common failures

| Symptom                                                | Likely cause                                      | Fix                                       |
| ------------------------------------------------------ | ------------------------------------------------- | ----------------------------------------- |
| Controller fails to start, log says `unknown config key` | Removed key still present in `controller.yml` | Edit out the key, restart.                 |
| Controller starts but `coordination.store=unavailable` | New release requires a Valkey/Redis feature      | Upgrade Valkey/Redis to the documented minimum. |
| Daemons disconnect after upgrade                       | mTLS client trust changed                         | Re-issue Daemon certificates; see [`rotate-secrets.md`](rotate-secrets.md). |
| Module install rejected after upgrade                  | Manifest schema bumped                            | Re-publish the Module against the new SDK; existing installs continue running. |
| Audit log entries spike "migration applied"            | Normal — schema migrations run once on startup    | None. Confirm no `migration failed` entries follow. |

## Related

- [`backup.md`](backup.md) — required pre-flight.
- [`restore.md`](restore.md) — rollback path.
- [`recover-controller.md`](recover-controller.md) — if an upgrade leaves
  the Controller in a non-startable state.
