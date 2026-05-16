---
title: Backups and Disaster Recovery
description: Backup scope, schedule, restore procedure, RPO/RTO targets, and the nightly DR drill that proves they hold.
---

A backup you have never restored is not a backup. This page defines
what PrexorCloud considers a backup, what it captures, the RPO/RTO
targets the project commits to, and the manual + automated drills
that verify those targets still hold.

## What you'll learn

- What is in a PrexorCloud backup and what isn't
- The recommended backup cadence and retention shape
- The restore procedure, including selective and dry-run modes
- The RPO/RTO contract per data tier and the drill that exercises it

## Recovery targets

| Tier | Source | RPO | RTO | What "recovered" means |
|---|---|---|---|---|
| **1 — Durable state** | MongoDB | ≤ 1h | ≤ 30 min | Controller boots, dashboard logs in, every pre-incident group / template / deployment / audit row is back, daemons reconnect with their existing certs. |
| **2 — Coordination** | Valkey | best-effort | ≤ 5 min | Empty Valkey is acceptable. Controller rebuilds leases on first reconciliation; JWT revocations are forgiven; SSE clients reconnect with replay-from-zero. |
| **3 — Filesystem** | `config/`, `data/certs/`, `cloud-modules/` | ≤ 24h | ≤ 30 min | `controller.yml`, the CA, and module data are recoverable. The CA private key is the only **irreplaceable** material — if lost, every daemon must rejoin from scratch. |
| **4 — Daemon hosts** | `daemon.yml` + per-daemon mTLS | n/a | ≤ 15 min/node | Daemon restored; reconnects and reconciles instances from the controller. |

RPO is measured as time-since-last-successful-backup. The targets
above are predicated on a backup cadence of at least hourly Mongo,
daily filesystem, optional Valkey. If your cadence is slower, your RPO
is whatever your cadence is — PrexorCloud does not run the cron for
you.

## What is in a backup

`prexorctl backup create` produces a single tarball that captures:

| Tier | Source | Loss impact |
|---|---|---|
| Durable platform | MongoDB (full dump) | Catastrophic — every group, deployment, audit entry, module record. |
| Coordination | Valkey RDB snapshot (optional) | Tolerable — login lockouts and SSE replay reset, JWT revocations forgiven. |
| Filesystem | `config/`, `data/certs/`, `cloud-modules/` | Catastrophic for config and CA; recoverable for module storage. |
| Per-module storage | Mongo (`mod_*` collections) and Valkey (`prexor:v1:platform:<id>:*`) | Module-defined. |

Each manifest records `createdAt`, `controllerVersion`, `host`, the
scopes captured, and the file references. The on-disk artefact is the
source of truth; the `backups` Mongo collection is a searchable
index.

### What is *not* in a backup

- Live state of running MC instances. Per-world / per-player game data
  is the operator's responsibility; most server jars include hot
  snapshots.
- DNS, load balancer, and reverse proxy config in front of the
  controller.
- Operator dashboard browser sessions (cookies; transient anyway).

## Recommended cadence

| Frequency | Scope | Retention |
|---|---|---|
| Hourly | Mongo only | 24 hours |
| Daily | Full (Mongo + Valkey + filesystem) | 14 days |
| Weekly | Full + off-host ship | 90 days |
| Pre-upgrade | Full | Until next stable upgrade window |

The audit log lives in Mongo, so the hourly cadence keeps
audit-trail loss bounded.

## Take a backup

```bash
# Full backup, default scope (Mongo + Valkey + filesystem).
prexorctl backup create

# Mongo only — fastest path; suitable for the hourly cadence.
prexorctl backup create --scope mongo

# Pre-upgrade snapshot with a label.
prexorctl backup create --label "pre-v1.4-upgrade"

# List manifests.
prexorctl backup list

# Verify integrity (checksums + structural restore-dry-run).
prexorctl backup verify <manifest-id>

# Prune older than retention.
prexorctl backup prune --keep 14
```

The CLI wraps `BackupCreator` and `BackupCatalog` — the same code
the controller uses internally. Output goes to `<install-root>/backups/<id>/`.

### Manual fallback

When the CLI is unavailable, the manual procedure lives in the
[backup runbook](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/backup.md).
TL;DR:

```bash
BK=/var/backups/prexorcloud/$(date -u +%Y-%m-%dT%H%M%SZ)
sudo mkdir -p "$BK"

mongodump --uri "$MONGO_URI" --gzip --out "$BK/mongo"
redis-cli -u "$REDIS_URI" BGSAVE
sudo cp /var/lib/valkey/dump.rdb "$BK/valkey-dump.rdb"
sudo tar -czf "$BK/etc-prexorcloud.tar.gz" -C /etc prexorcloud
```

Write a `manifest.json` matching the CLI's schema and ship the
directory off-host (encrypted with `age` or `gpg`).

## Off-host shipping

A backup that lives only on the controller is one disk failure from
useless.

```bash
# Encrypt then ship.
sudo tar -cf - -C /var/backups/prexorcloud "$BK_NAME" \
  | age -r age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx... \
        > "$BK_NAME.tar.age"

aws s3 cp "$BK_NAME.tar.age" s3://your-backups/prexorcloud/
```

Use whatever encrypted off-host store fits your workflow — S3,
restic, borg, Backblaze B2.

## Restore

Restore replays a backup taken with the procedure above. The flow is
the same whether you are recovering from a corrupt Mongo, a lost host,
or a botched upgrade.

### Decision tree

| Scenario | Restore? |
|---|---|
| Single controller died, HA peer healthy | **No** — fail over. See the [recover-controller runbook](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/recover-controller.md). |
| Mongo corrupted / dropped | **Yes**, Mongo + filesystem. |
| Valkey emptied / lost | Usually no; controllers rebuild. |
| Bad upgrade rolled back the binary | Maybe — only if the failed upgrade ran a Mongo migration. Release notes call this out. |
| Bad config push | Filesystem only (`config/controller.yml`). |
| Module accidentally uninstalled | Selective Mongo restore of `module_packages` and `mod_<moduleId>_*`. |

When in doubt, restore the full backup to a *staging* controller first
and validate before pointing production at it.

### Run a restore

```bash
# Always dry-run first.
prexorctl restore /var/backups/prexorcloud/<id>/manifest.json --dry-run

# Apply.
prexorctl restore /var/backups/prexorcloud/<id>/manifest.json \
    --filesystem --datastores
```

`--dry-run` runs the restore validator and reports scope conflicts
without mutating anything. The validator runs again before any
APPLY, regardless of `--dry-run` — the dry-run mode just stops there.

### Validation checklist

After restore, before declaring it good:

- [ ] `/api/v1/system/ready` returns 200.
- [ ] `prexorctl status` shows expected controllers, nodes, groups.
- [ ] `prexorctl group list` shows expected groups.
- [ ] `prexorctl module list` shows installed modules in `ACTIVE`.
- [ ] Spot-check the audit log: `db.audit_log.find().sort({createdAt:-1}).limit(20)`.
- [ ] All daemons reconnect — `prexorctl node list` shows `READY`.
- [ ] Smoke-test a deploy on a non-prod group.

If daemon mTLS material was *not* in the restored backup, daemons
fail at the TLS layer. Re-issue:

```bash
prexorctl token create --description "rejoin-after-restore" --ttl 1h
# On each daemon:
sudo prexorctl setup --role daemon --rejoin --join-token <token>
```

## The DR drill

The drill exists because backup tooling that is never restored isn't
backup tooling.

### Nightly automated drill

The `dr-drill` job in `.github/workflows/nightly.yml` runs the
end-to-end cycle every night against ephemeral Mongo + Valkey service
containers:

```bash
cd java
./gradlew :cloud-test-harness:drDrill
```

The harness:

1. Boots an in-process controller with a real Mongo + Valkey.
2. Seeds a deterministic fixture (one template, two groups with
   distinct platform / scaling / priority shapes).
3. Snapshots declarative state.
4. Calls `POST /api/v1/backups`; verifies the manifest with
   `POST /api/v1/backups/{id}/verify`.
5. Stops the controller, drops the Mongo database, flushes the Valkey
   logical DB.
6. Brings the controller back; asserts the seeded fixtures vanished.
7. Calls `POST /api/v1/restore` with `dryRun=true`, then
   `dryRun=false` (`filesystem=true`, `datastores=true`).
8. Re-logs in as admin and re-snapshots state, asserting an exact
   match against step 3.

The job is `@Tag("dr")` and excluded from the default test pass; the
`drDrill` task opts in. CI failure on this job is a real DR
regression — investigate before merging.

The CI job does **not** measure wall-clock RTO; that target is still
the operator's manual drill. The job's value is catching backup
schema drift, restore-validator regressions, and post-restore state
divergence between releases.

### Manual quarterly drill

Even with nightly CI green, run a real-environment drill **at least
quarterly**. See [Disaster Drill](/operations/disaster-drill/) for
the step-by-step.

Production credibility is the median time-to-restore from your last
drill, not the last green backup-create job.

## Common failures

| Symptom | Likely cause | Fix |
|---|---|---|
| `mongorestore` fails with `unsupported BSON version` | Restoring with much-older `mongorestore` | Use the binary matching the source Mongo version. |
| Controller starts then exits with `migration failed` | Schema mismatch | Restore into the same controller version that took the backup. |
| Daemons can't connect: `peer not found in trust store` | CA was not restored | Restore `data/certs/`, restart controller. |
| `coordination.store=unavailable` after restore | Valkey URI changed | Update `controller.yml`, restart. |
| Modules show `LOAD_FAILED` | Bundle file removed but record kept | Reinstall via `prexorctl module install`. |
| First login rejected with `Locked` | Restored login-attempt counters | Wait the lockout window or `prexorctl user unlock <username>`. |

## Next up

- [Disaster Drill](/operations/disaster-drill/) — walk a real scenario step by step
- [HA Setup](/operations/ha-setup/) — failure modes that don't need a restore
- [Configuration Reference](/operations/configuration/) — `backup.directory`, `backup.retentionCount`, `scheduler.auditRetentionDays`
