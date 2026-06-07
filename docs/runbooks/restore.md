# Restore

Restoring a PrexorCloud install replays a backup taken with the procedure
in [`backup.md`](backup.md). The flow is the same whether you're
recovering from a corrupted Mongo, a lost host, or a botched upgrade.

> **Recommended path:** `prexorctl restore <manifest>` (with `--dry-run`
> + scope flags `--filesystem` / `--datastores`). The CLI wraps
> `RestoreExecutor` and runs the restore validator before any APPLY. The
> manual procedure below is for emergencies when the CLI is unavailable.

## Decision Tree

| Scenario                                       | Restore?                          |
| ---------------------------------------------- | --------------------------------- |
| Single controller host died, HA peer healthy   | **No.** See [`recover-controller.md`](recover-controller.md). |
| Mongo database corrupted / dropped             | **Yes**, Mongo + filesystem.      |
| Valkey emptied / lost                          | Usually no; controllers rebuild soft state. See [`recover-redis.md`](recover-redis.md). |
| Bad upgrade rolled back the binary             | Maybe — only if the failed upgrade ran a Mongo migration. Release notes call this out. |
| Bad config push                                | Filesystem only (`config/controller.yml`). |
| Module accidentally uninstalled                | Selective Mongo restore of `modules` and `module_storage_*`. |

When in doubt, restore the full backup to a *staging* controller first
and validate before pointing production at it.

## Pre-flight

1. Identify the backup to restore. Locate the manifest:
   ```bash
   ls -1t /var/backups/prexorcloud/*/manifest.json
   ```
2. Read the manifest. Confirm `controllerVersion` matches the version
   you're going to run. **Do not restore a backup into a different
   controller version unless the release notes explicitly allow it.**
3. Stop every controller that talks to the target Mongo and Valkey:
   ```bash
   # On each controller host.
   sudo systemctl stop prexorcloud-controller
   ```
4. Optionally stop daemons. Daemons keep their existing certificates and
   reconnect once the controller comes back, so leaving them running is
   safe — they'll just be unhappy for the duration of the restore.

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

The `--drop` flag drops each collection before restoring it, ensuring
you don't end up with a mix of old and new data.

## Step 2 — Restore Valkey (Optional)

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
controller rebuilds the keys it owns. Skip it if your Valkey is shared
with other services (you'll trample them).

## Step 3 — Restore Filesystem

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
  older backup into the same controller version.
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
prexorctl crash list --since "1 hour ago"
```

If groups, modules, and audit entries match expectations, the restore
is good.

## Step 5 — Reconnect Daemons

If daemon certificates are part of the restored backup, daemons will
reconnect automatically as soon as the controller is back.

If you restored a backup older than the daemon's current certificate,
the controller may not recognize the daemon. Re-issue:

```bash
# On the controller:
prexorctl token create --description "rejoin-after-restore" --ttl 1h

# On each daemon:
sudo prexorctl setup --role daemon --rejoin --join-token <token>
```

`--rejoin` clears the daemon's local cert directory and requests a
fresh certificate.

## Selective Restores

### Restore a Single Module

```bash
mongorestore --uri "$MONGO_URI" \
    --nsInclude='prexorcloud.modules' \
    --nsInclude='prexorcloud.module_storage_*' \
    --gzip \
    "$BK/mongo"
prexorctl module list
```

### Restore Audit Log Only

```bash
mongorestore --uri "$MONGO_URI" \
    --nsInclude='prexorcloud.audit_log' \
    --gzip "$BK/mongo"
```

## Restore Validation Checklist

After the controller is back and reporting ready:

- [ ] `prexorctl status` shows expected controllers, nodes, groups.
- [ ] `prexorctl group list` shows expected groups in expected states.
- [ ] `prexorctl module list` shows installed modules in `ACTIVE`.
- [ ] `prexorctl crash list --since "1 day ago"` matches the backup era.
- [ ] Audit-log spot-check: `prexorctl audit query --since "<backup time>"`
  returns the expected entries (or via dashboard until the CLI lands).
- [ ] Daemons reconnect — `prexorctl node list` shows all `READY`.
- [ ] Smoke-test a deploy on a non-prod group.

## Common Failures

| Symptom                                                    | Likely cause                                | Fix                                       |
| ---------------------------------------------------------- | ------------------------------------------- | ----------------------------------------- |
| `mongorestore` fails with `unsupported BSON version`       | Restoring with a much older `mongorestore`  | Use the `mongorestore` matching the source Mongo version. |
| Controller starts then stops with `migration failed`       | Schema mismatch                             | Restore into the same controller version that took the backup. |
| Daemons can't connect: `peer not found in trust store`     | CA was not restored                         | Restore `data/certs/` from the backup, restart controller. |
| `coordination.store=unavailable` after restore             | Valkey URI changed                          | Update `controller.yml` to the new Valkey URI; restart. |
| Modules show `LOAD_FAILED`                                 | Module jar removed but record kept          | Reinstall the jar via `prexorctl module upload`. |
| First login rejected with `Locked`                         | Restored login attempt counters             | Wait out the lockout window or call `prexorctl user unlock <username>`. |

## Drill Cadence

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
