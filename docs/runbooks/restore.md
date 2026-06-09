# Restore

Restoring a PrexorCloud install replays a backup taken with the procedure
in [`backup.md`](backup.md). The flow is the same whether you're
recovering from a corrupted Mongo, a lost host, or a botched upgrade.

> **Recommended path:** `prexorctl restore <id>` — `<id>` is the bundle id
> from `prexorctl backup list`. Both tiers restore by default; scope the
> restore with `--filesystem=false` or `--datastores=false`, and pass
> `--dry-run` to validate the bundle and report the planned changes without
> writing. The CLI calls `RestoreExecutor`, which runs `RestoreValidator`
> before applying — a bundle that fails verification is rejected, so run
> `prexorctl backup verify <id>` first. The manual procedure below is for
> emergencies when the CLI is unavailable.

## Decision tree

| Scenario                                       | Restore?                          |
| ---------------------------------------------- | --------------------------------- |
| Single Controller host died, HA peer healthy   | **No.** See [`recover-controller.md`](recover-controller.md). |
| Mongo database corrupted / dropped             | **Yes**, Mongo + filesystem.      |
| Valkey emptied / lost                          | Usually no; Controllers rebuild soft state. See [`recover-redis.md`](recover-redis.md). |
| Bad upgrade rolled back the binary             | Maybe — only if the failed upgrade ran a Mongo migration. Release notes call this out. |
| Bad config push                                | Filesystem only (`config/controller.yml`). |
| Module accidentally uninstalled                | Selective Mongo restore of `modules` and `module_storage_*`. |

When in doubt, restore the full backup to a *staging* Controller first
and validate before pointing production at it.

## Pre-flight

1. Identify the backup to restore. Locate the manifest:
   ```bash
   ls -1t /var/backups/prexorcloud/*/manifest.json
   ```
2. Read the manifest. Confirm `controllerVersion` matches the version
   you're going to run. **Do not restore a backup into a different
   Controller version unless the release notes explicitly allow it.**
3. Stop every Controller that talks to the target Mongo and Valkey:
   ```bash
   # On each controller host.
   sudo systemctl stop prexorcloud-controller
   ```
4. Optionally stop Daemons. Daemons keep their existing certificates and
   reconnect once the Controller comes back, so leaving them running is
   safe — they'll be unhappy for the duration of the restore.

## Step 1 — Restore Mongo

If Mongo is corrupt, drop and recreate the database before restoring.

```bash
BK=/var/backups/prexorcloud/2026-05-03T120000Z   # adjust
MONGO_URI=$(sudo grep -E '^\s*uri:' /etc/prexorcloud/config/controller.yml \
    | head -1 | awk '{print $2}' | tr -d '"')

# Optional: drop the existing database first.
mongosh "$MONGO_URI" --eval 'db.dropDatabase()'

sudo mongorestore --uri "$MONGO_URI" \
    --gzip \
    --drop \
    "$BK/mongo"
```

The `--drop` flag drops each collection before restoring it, so you
don't end up with a mix of old and new data.

## Step 2 — Restore Valkey (optional)

```bash
REDIS_URI=$(sudo grep -A1 '^redis:' /etc/prexorcloud/config/controller.yml \
    | grep uri | awk '{print $2}' | tr -d '"')

# Stop Valkey, replace dump.rdb, start.
sudo systemctl stop valkey
sudo cp "$BK/valkey-dump.rdb" /var/lib/valkey/dump.rdb
sudo chown valkey:valkey /var/lib/valkey/dump.rdb
sudo systemctl start valkey

# Verify.
redis-cli -u "$REDIS_URI" --scan --pattern 'prexor:v1:*' | head
```

Skip this step if you're recovering from a Valkey-only loss — the
Controller rebuilds the keys it owns. Skip it if your Valkey is shared
with other services (you'll trample them).

## Step 3 — Restore filesystem

The filesystem tarball includes `config/controller.yml`, `data/certs/`,
and module data.

```bash
# Move the existing directory aside so you can roll back if the restore
# fails halfway through.
sudo mv /etc/prexorcloud /etc/prexorcloud.pre-restore.$(date +%s)
sudo mkdir -p /etc/prexorcloud
sudo tar -xzf "$BK/etc-prexorcloud.tar.gz" -C /etc
sudo chown -R prexorcloud:prexorcloud /etc/prexorcloud
sudo chmod 600 /etc/prexorcloud/config/controller.yml
```

If you only need to restore config:

```bash
sudo tar -xzf "$BK/etc-prexorcloud.tar.gz" \
    -C / etc/prexorcloud/config/controller.yml
```

If you only need to restore certs:

```bash
sudo tar -xzf "$BK/etc-prexorcloud.tar.gz" \
    -C / etc/prexorcloud/data/certs
```

## Step 4 — Start the Controller

```bash
sudo systemctl start prexorcloud-controller
sudo journalctl -u prexorcloud-controller -f
```

Watch for:

- `migration applied:` lines — these are normal if you're restoring an
  older backup into the same Controller version.
- `migration failed:` — stop, restore the pre-restore directory, and
  open a support issue. Do not improvise schema fixes by hand.
- `coordination.store=available` — confirms Valkey is reachable.
- `state.store=available` — confirms Mongo is reachable.

Verify:

```bash
curl -fs http://localhost:8080/api/v1/system/ready
prexorctl login --controller https://localhost:8080
prexorctl status
prexorctl group list
prexorctl module list
prexorctl crash list
```

If Groups, Modules, and audit entries match expectations, the restore
is good.

## Step 5 — Reconnect Daemons

If Daemon certificates are part of the restored backup, each Daemon
reconnects automatically as soon as the Controller is back.

If you restored a backup older than a Daemon's current certificate, the
Controller may not recognize that Daemon. Re-enrol it with a fresh join
token:

```bash
# On the Controller — mint a short-lived join token.
prexorctl token create --ttl 1h
```

```bash
# On each Daemon — re-run setup, choose the Daemon component, and paste
# the new join token.
sudo prexorctl setup
```

The join-token exchange overwrites the stale certificate files in the
Daemon's `config/security/` directory (`node.p12`, `.node-password`,
`ca.pem`), so the Daemon connects on its next start.

## Selective restores

### Restore a single Module

```bash
mongorestore --uri "$MONGO_URI" \
    --nsInclude='prexorcloud.modules' \
    --nsInclude='prexorcloud.module_storage_*' \
    --gzip \
    "$BK/mongo"
prexorctl module list
```

### Restore the audit log only

```bash
mongorestore --uri "$MONGO_URI" \
    --nsInclude='prexorcloud.audit_log' \
    --gzip "$BK/mongo"
```

## Restore validation checklist

After the Controller is back and reporting ready:

- [ ] `prexorctl status` shows the expected Controllers, nodes, and Groups.
- [ ] `prexorctl group list` shows the expected Groups in the expected states.
- [ ] `prexorctl module list` shows installed Modules in `ACTIVE`.
- [ ] `prexorctl crash list` matches the backup era.
- [ ] Audit-log spot-check in the dashboard audit view: the entries from
  around the backup time are present.
- [ ] Daemons reconnect — `prexorctl node list` shows them all `ONLINE`.
- [ ] Smoke-test a deploy on a non-prod Group.

## Common failures

| Symptom                                                    | Likely cause                                | Fix                                       |
| ---------------------------------------------------------- | ------------------------------------------- | ----------------------------------------- |
| `mongorestore` fails with `unsupported BSON version`       | Restoring with a much older `mongorestore`  | Use the `mongorestore` matching the source Mongo version. |
| Controller starts then stops with `migration failed`       | Schema mismatch                             | Restore into the same Controller version that took the backup. |
| Daemons can't connect: `peer not found in trust store`     | CA was not restored                         | Restore `data/certs/` from the backup, restart the Controller. |
| `coordination.store=unavailable` after restore             | Valkey URI changed                          | Update `controller.yml` to the new Valkey URI; restart. |
| Modules show `FAILED`                                      | Module jar removed but record kept          | Reinstall the jar via `prexorctl module upload`. |
| First login rejected with `Locked`                         | Restored login attempt counters             | Wait out the lockout window — the failed-login lock clears after the inactivity window. |

## Drill cadence

Run a full restore drill in a throwaway environment **at least quarterly**.
A nightly DR drill in CI exercises the restore path against ephemeral
Mongo + Valkey containers (`dr-drill` job in `.github/workflows/nightly.yml`),
but a real-environment drill catches drift the synthetic harness misses.

## Related

- [`backup.md`](backup.md) — the procedure that produced the manifest.
- [`recover-controller.md`](recover-controller.md) — when to fail over
  instead of restore.
- [`recover-mongo.md`](recover-mongo.md), [`recover-redis.md`](recover-redis.md)
  — store-specific failure modes.
