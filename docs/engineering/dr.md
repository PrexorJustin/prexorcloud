# Disaster Recovery

This is the contract PrexorCloud commits to when something catastrophic
happens — a host loss, a database corruption, a botched upgrade, an
operator deleting the wrong volume. It defines RPO and RTO targets,
which scenarios they apply to, and the drill that verifies the targets
still hold.

> **Scope.** PrexorCloud is a single-controller-by-default orchestrator
> (see [`architecture.md`](architecture.md)). Active-active controller HA
> via leases is supported by the codebase; multi-region is not. Targets
> below assume a single host or a single AZ.

## Targets

| Class             | RPO                | RTO                | What "recovered" means |
| ----------------- | ------------------ | ------------------ | ---------------------- |
| **Tier 1 — durable state (Mongo)** | ≤ 1 h | ≤ 30 min | Controller boots, dashboard logs in, every pre-incident group / template / deployment / audit row is back, daemons reconnect with their existing certs. |
| **Tier 2 — coordination (Valkey)** | best-effort       | ≤ 5 min            | Empty Valkey is acceptable — controller rebuilds leases on first reconciliation tick, JWT revocations are forgiven, SSE clients reconnect with replay-from-zero. |
| **Tier 3 — filesystem (config, CA, modules)** | ≤ 24 h | ≤ 30 min | `controller.yml`, `config/security/` (CA), and `modules/` are recoverable. The CA private key is the only **irreplaceable** material — if lost, every daemon must rejoin from scratch. |
| **Tier 4 — daemon hosts**          | n/a               | ≤ 15 min/node      | Daemon binary + `daemon.yml` + `config/security/` restored; daemon reconnects and reconciles instances from the controller's authoritative state. |

RPO is measured as time-since-last-successful-backup. The targets above
are predicated on a backup cadence of:

- **Mongo:** at least hourly.
- **Filesystem:** at least daily (the CA + config don't change often).
- **Valkey:** optional — back up only if a module treats it as primary
  storage.

If your operational cadence is slower than that, your RPO is whatever
your cadence is. PrexorCloud doesn't run the cron for you.

## What is *not* in scope

- **Cross-region failover.** v1 is single-region. A second region
  recovering from a backup of the first is a manual data-restore
  procedure, not a target.
- **Zero-RPO synchronous replication.** Mongo replica sets and Valkey
  replication are upstream concerns; PrexorCloud doesn't enforce or
  configure them. Operators who want lower RPO bring their own.
- **Hot standby controller.** The codebase supports a second controller
  on the same Mongo + Valkey via the `controller_lease`, but bootstrap
  + cutover scripting is operator-owned.
- **DDoS / capacity events.** This document is about data-loss recovery,
  not availability under load.

## Recovery scenarios

Each scenario points at the runbook that drives it. The DR document
exists to set the **target** — the runbooks are the **procedure**.

| Scenario                                       | Tier          | Runbook                                |
| ---------------------------------------------- | ------------- | -------------------------------------- |
| Controller host lost (Mongo + Valkey survived) | n/a           | [`recover-controller.md`](runbooks/recover-controller.md) |
| Mongo dropped or corrupted                     | 1             | [`recover-mongo.md`](runbooks/recover-mongo.md) + [`restore.md`](runbooks/restore.md) |
| Valkey lost                                    | 2             | [`recover-redis.md`](runbooks/recover-redis.md) |
| Bad upgrade — rollback                         | 1 (sometimes) | [`upgrade.md`](runbooks/upgrade.md) §rollback |
| Bad config push                                | 3             | [`restore.md`](runbooks/restore.md) §filesystem |
| Daemon host lost                               | 4             | [`drain-node.md`](runbooks/drain-node.md) §replace |
| CA private key compromised                     | 3 (worst)     | [`rotate-secrets.md`](runbooks/rotate-secrets.md) §ca-rotate |

## The restore drill

The drill exists because backup tooling that is never restored isn't
backup tooling. **Run it at least quarterly.** Production credibility
is the median time-to-restore from your last drill, not the last green
backup-create job.

### Manual drill (works today)

1. **Pick a manifest.** From a recent
   `prexorctl backup list` (or the on-disk catalog under the controller's
   `data/backups/` directory), pick a manifest at least 24 h old.
2. **Provision a staging environment.** A second compose stack on a
   different host, or `docker compose -p prexorcloud-staging -f
   deploy/compose/compose.yml up -d`. Use a *different* `jwtSecret`
   than production so any leaked tokens won't apply.
3. **Restore with `--dry-run` first:**
   ```bash
   prexorctl --controller https://staging:8080 \
     restore /var/backups/prexorcloud/<id>/manifest.json --dry-run
   ```
   The validator should report no scope conflicts.
4. **Apply for real:**
   ```bash
   prexorctl --controller https://staging:8080 \
     restore /var/backups/prexorcloud/<id>/manifest.json
   ```
5. **Validate:**
   - Dashboard loads, admin can log in.
   - Group counts and template counts match production at the time the
     manifest was taken (record both numbers in the drill log).
   - Every audit-log row from the last hour pre-snapshot is present.
   - Smoke a daemon: `prexorctl node generate-token` on staging,
     enrol a fresh daemon, deploy a one-instance group, verify the
     instance reaches `RUNNING`.
6. **Record times.** Wall-clock from "decision to restore" to "smoke
   test green" is the **measured RTO**. Wall-clock from
   `manifest.timestamp` to "restore complete" is the **measured RPO**.
7. **Tear down staging.** Drop the volumes, log the drill in your
   on-call doc.

### Nightly automated drill (shipped)

The `dr-drill` job in `.github/workflows/nightly.yml` runs the cycle
every night against ephemeral Mongo + Valkey service containers, via
the cloud-test-harness `drDrill` gradle task:

1. The `DrDrillTest` harness boots an in-process controller with a
   real Mongo + Valkey, just like the rest of the harness suite.
2. Seeds a deterministic fixture (one template, two groups with
   distinct platform / scaling / priority shapes).
3. Snapshots the declarative state (group set + per-group config +
   template set).
4. Calls `POST /api/v1/backups` and verifies the manifest with
   `POST /api/v1/backups/{id}/verify` before going destructive.
5. Stops the controller, drops the Mongo database, and flushes the
   Valkey logical DB via `TestCluster.wipeDatastores()`. The
   on-disk backup directory is part of the controller working dir
   and survives the wipe.
6. Brings the controller back up against the empty datastores and
   asserts the seeded fixtures really vanished.
7. Calls `POST /api/v1/restore` first with `dryRun=true`, then with
   `dryRun=false` (`filesystem=true`, `datastores=true`).
8. Re-logs in as admin (the user collection has been swapped) and
   re-snapshots the state, asserting an exact match against
   step 3.

Skipping rules: the test is `@Tag("dr")` and excluded from the
default test pass; the `drDrill` gradle task opts in. The harness
calls `Assumptions.assumeTrue` on Mongo + Valkey reachability so
runs without those services skip rather than fail. CI failure on
this job is a real DR regression — investigate before merging.

The CI job does **not** measure wall-clock RTO; that target is still
the operator's manual drill. The job's value is catching backup
schema drift, restore-validator regressions, and post-restore state
divergence between releases.

## What to record after every drill

A two-line entry, pasted into the on-call channel and the on-call doc:

```
DR drill 2026-05-03
- Scenario: Mongo full-restore from 23:59 UTC manifest
- Measured RPO: 47 min
- Measured RTO: 22 min
- Notes: validator caught a `composition_plans` schema drift from
  the backup → re-ran with --skip-mongo on that collection.
```

If a measured value blows past the target, **the target is not the
fact** — the drill is. Update this document, escalate the gap, and
treat closing it as the next on-call carry-over item.
