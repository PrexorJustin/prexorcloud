# Recover from MongoDB loss

MongoDB is the durable source of truth for PrexorCloud. Losing it
without a backup is **catastrophic**: Groups, deployments, audit log,
crash records, Module records, composition plans, and workflow intent
are all gone.

This runbook covers four scenarios:

1. Mongo is reachable but degraded.
2. Mongo is unreachable.
3. Mongo is corrupt and must be restored.
4. Mongo data is gone with no backup (last resort).

## What loss of Mongo means

| State                       | Recovery path                                       |
| --------------------------- | --------------------------------------------------- |
| Connection lost             | Wait / fix network / reset auth.                    |
| Replica primary failover    | Mongo handles internally; the Controller pauses writes. |
| Single collection corrupt   | Selective `mongorestore` from latest backup.        |
| Whole DB gone, backup ready | Full `mongorestore`; see [`restore.md`](restore.md). |
| Whole DB gone, no backup    | Reinstall from scratch; rejoin all Daemons; rebuild Groups manually. |

If the Controller cannot reach Mongo, **all** mutations stop. Reads
that hit only the Valkey-backed runtime store continue to serve
(scheduler ticks, Daemon heartbeats, SSE deltas) until the next
attempted persistence; then they queue.

## Symptoms

| Signal                                                    | Likely cause                                |
| --------------------------------------------------------- | ------------------------------------------- |
| `/ready` returns 503 with `mongo:false`                   | Connectivity / auth / disk full             |
| Controller log: `MongoTimeoutException` or `MongoSocketException` | Network or process down               |
| `prexorctl status` hangs                                  | Mongo connection pool exhausted             |
| Audit entries missing for recent operations               | Writes failing silently — should not happen; if it does, file a bug |
| `migration failed: ...`                                   | Schema mismatch on startup                  |

## Scenario 1 — Mongo slow / degraded

```bash
# From the controller host:
mongosh "$MONGO_URI" --eval 'db.runCommand({ping:1})'
mongosh "$MONGO_URI" --eval 'db.serverStatus().connections'
mongosh "$MONGO_URI" --eval 'db.currentOp({"secs_running": {"$gt": 1}})'
mongosh "$MONGO_URI" --eval 'rs.status()'   # if replica set
```

Common patterns:

- **Connection pool saturated** — the Controller log shows
  `Too many open connections` or hangs at `acquireConnection`. Bump
  `maxPoolSize` in the Mongo URI: `?maxPoolSize=200`.
- **Slow query** — `db.currentOp` shows queries running >1s on
  PrexorCloud collections. Index audit_log by `createdAt`; check
  `composition_plans` for missing index after a restore (the
  Controller creates indices on startup; if you killed it mid-startup,
  re-run).
- **Replica primary stepping down** — `rs.status()` shows churn. Fix
  Mongo first; the Controller pauses until a primary is elected.

## Scenario 2 — Mongo unreachable

```bash
mongosh "$MONGO_URI" --eval 'db.runCommand({ping:1})'
# Connection refused / timed out / Authentication failed
```

Restore connectivity. Common causes:

- Mongo systemd unit stopped (`systemctl status mongod`).
- Disk full on Mongo host (`df -h`).
- Auth credentials rotated without updating `controller.yml`.
- Network ACL change.

Once Mongo is back, the Controller picks up on the next probe (~5s).
No Controller restart needed.

## Scenario 3 — Mongo corrupt or schema-broken

You see `migration failed`, persistent
`E11000 duplicate key error`, or read errors that don't go away.

1. Stop the Controller(s):
   ```bash
   sudo systemctl stop prexorcloud-controller
   ```
2. Take a fresh `mongodump` of the corrupt state (you may need it for
   forensics):
   ```bash
   mongodump --uri "$MONGO_URI" --gzip --out /var/backups/prexorcloud/forensic-$(date -u +%Y%m%dT%H%M%SZ)
   ```
3. Restore from your most recent good backup. See
   [`restore.md`](restore.md) §Step 1 — Restore Mongo.
4. Start the Controller and validate.

If only one collection is corrupt, restore selectively:

```bash
mongosh "$MONGO_URI" --eval 'db.composition_plans.drop()'
mongorestore --uri "$MONGO_URI" \
    --nsInclude='prexorcloud.composition_plans' \
    --gzip "/var/backups/prexorcloud/<latest>/mongo"
```

The Controller rebuilds composition plans on the next Group tick from
the source data (Groups + Templates + Modules), so this collection is
relatively safe to drop.

**Do not drop**: `audit_log`, `crash_records`, `users`, `roles`,
`workflow_intent`, or `modules`. Those have no rebuild source.

## Scenario 4 — Total loss, no backup

You're rebuilding from zero. Treat this as a fresh install with one
shortcut: Daemon hosts likely still have their certificates, but a
fresh Controller won't recognize them.

1. Provision a new MongoDB instance.
2. On the Controller, reset state:
   ```bash
   sudo systemctl stop prexorcloud-controller
   sudo rm -rf /etc/prexorcloud/data/certs/   # forces CA regeneration
   ```
3. Run [`install.md`](install.md) §Step 2–4. The setup flow creates a
   fresh CA and a new admin password.
4. **Re-issue every Daemon certificate.** Old Daemons cannot talk to
   the new Controller because the CA is different.
   ```bash
   prexorctl token create --ttl 24h
   # On each Daemon, re-run setup with the new join token:
   sudo prexorctl setup --component daemon --daemon-join-token <token>
   ```
5. Recreate Groups, Templates, and Module installs. There is no
   shortcut — this is the cost of running without backups.
6. File a post-incident note: schedule backups before reopening the
   service. See [`backup.md`](backup.md).

## Replica set considerations

If your Mongo is a replica set, the Controller relies on the driver
to handle primary failover transparently. Verify on startup that the
URI uses replica-set syntax:

```
mongodb://user:pw@host1:27017,host2:27017,host3:27017/prexorcloud?replicaSet=rs0&w=majority
```

`w=majority` is the recommended write concern; the Controller does
not override it. PrexorCloud does not require a specific Mongo
deployment topology — single-node, replica set, and sharded clusters
all work.

## Common failures

| Symptom                                                   | Likely cause                                | Fix                                                 |
| --------------------------------------------------------- | ------------------------------------------- | --------------------------------------------------- |
| Controller log: `MongoSecurityException`                  | Auth failed                                 | Update credentials in `controller.yml`              |
| Controller log: `not master and slaveOk=false`            | URI omits replica set name                  | Add `?replicaSet=...`                               |
| Controller log: `migration failed: E11000`                | Restored two backups merged                 | [`restore.md`](restore.md) cleanly with `--drop`    |
| Audit entries delayed by minutes                          | Mongo write contention                      | Check disk; ensure `audit_log` has a TTL index per `scheduler.auditRetentionDays` |
| `prexorctl group list` empty after restore                | Restored older backup, Controller version newer | Run a forward-migration: restart the Controller; migrations run on startup. |
| Total loss, Daemons orphaned                              | CA regenerated                              | Re-issue certs (see Scenario 4 §step 4)             |

## Related

- [`backup.md`](backup.md) — make this never happen.
- [`restore.md`](restore.md) — full restore procedure.
- [`recover-controller.md`](recover-controller.md) — when the Controller is down rather than the store.
- [storage schema](../public/en/internals/storage-schema.md) — what MongoDB owns.
