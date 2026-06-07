---
title: Disaster Drill
description: Walk a real Mongo-loss scenario end to end, using the cloud-test-harness drDrill task and a manual quarterly drill in staging.
---

A backup you have never restored is not a backup. This page is the
walk-through — pick a scenario, run it cold, measure the wall-clock
time. PrexorCloud ships a nightly automated drill in CI; the manual
drill exists because the synthetic harness can't catch what your
specific environment hides.

## What you'll learn

- The two drills shipped today: nightly CI and manual quarterly
- How to run `cloud-test-harness:drDrill` locally
- A real worked scenario: Mongo dropped, restored from the previous
  hourly snapshot
- What to record after each drill so you have an honest RTO/RPO number

## The two drills

### Nightly automated drill

The `dr-drill` job in `.github/workflows/nightly.yml` runs every
night against ephemeral Mongo + Valkey service containers, via the
`cloud-test-harness:drDrill` Gradle task:

```bash
cd java
./gradlew :cloud-test-harness:drDrill
```

What it does:

1. Boots an in-process controller with a real Mongo + Valkey, just
   like the rest of the harness suite.
2. Seeds a deterministic fixture (one template, two groups with
   distinct platform / scaling / priority shapes).
3. Snapshots declarative state (group set + per-group config +
   template set).
4. Calls `POST /api/v1/backups`; verifies the manifest with
   `POST /api/v1/backups/{id}/verify` before going destructive.
5. Stops the controller, drops the Mongo database, and flushes the
   Valkey logical DB via `TestCluster.wipeDatastores()`. The on-disk
   backup directory is part of the controller working dir and
   survives.
6. Brings the controller back against the empty datastores; asserts
   the seeded fixtures vanished.
7. Calls `POST /api/v1/restore` first with `dryRun=true`, then
   `dryRun=false` (`filesystem=true`, `datastores=true`).
8. Re-logs in as admin (the user collection has been swapped) and
   re-snapshots state, asserting an exact match against step 3.

The test is `@Tag("dr")` and excluded from the default test pass; the
`drDrill` Gradle task opts in. The harness calls
`Assumptions.assumeTrue` on Mongo + Valkey reachability so runs
without those services skip rather than fail. **CI failure on this
job is a real DR regression** — investigate before merging.

What the CI job does **not** measure: wall-clock RTO. That target is
the operator's manual drill.

### Manual quarterly drill

Run a real-environment drill **at least quarterly** even with nightly
CI green. The synthetic harness can't catch:

- Off-host backup retrieval latency.
- Operator credentials / runbooks gone stale.
- Disk / network bandwidth surprises during a real restore.
- Permissions drift on `data/certs/`.

The walk-through below is the canonical manual drill.

## A worked scenario: Mongo dropped, restore from hourly snapshot

Your on-call doc says "restore from the most recent hourly Mongo
snapshot to a staging stack and validate." Here's the wall-clock
walk.

### Step 1 — Pick a manifest (target: 2 minutes)

```bash
prexorctl backup list
# Latest hourly: 2026-05-10T13:00:00Z, manifest 7e1c2af9
```

Note the manifest id and timestamp. Your **measured RPO** is
"manifest.timestamp → restore-complete." Record both moments.

### Step 2 — Provision a staging environment (target: 5 minutes)

A second Compose stack on a different host, or:

```bash
docker compose -p prexorcloud-staging \
    -f deploy/compose/compose.yml up -d
```

Use a *different* `jwtSecret` than production so any leaked tokens
won't apply.

### Step 3 — Pull the manifest off-host (target: 3 minutes)

If the backup was shipped to S3 / age:

```bash
aws s3 cp s3://your-backups/prexorcloud/2026-05-10T130000Z.tar.age .
age -d -i ~/.age/key.txt 2026-05-10T130000Z.tar.age | tar -xf -
```

Place the directory under the staging controller's `data/backups/`.

### Step 4 — Dry-run the restore (target: 2 minutes)

```bash
prexorctl --controller https://staging:8080 \
    restore /var/backups/prexorcloud/<id>/manifest.json --dry-run
```

The validator should report no scope conflicts. If it does, stop
here, debug the manifest, and update the runbook.

### Step 5 — Apply the restore (target: 5 minutes)

```bash
prexorctl --controller https://staging:8080 \
    restore /var/backups/prexorcloud/<id>/manifest.json \
    --filesystem --datastores
```

Watch the controller log for `migration applied:` lines (normal),
`migration failed:` (stop), and `coordination.store=available` /
`state.store=available` (good).

### Step 6 — Validate (target: 5 minutes)

```bash
# Login should work with the production admin password (NOT staging's
# bootstrap password — restore overwrote the user collection).
prexorctl --controller https://staging:8080 login

prexorctl status
prexorctl group list
prexorctl module list
prexorctl crash list --since "1 hour ago"
```

Spot checks:

- Group counts and template counts match production at the manifest
  timestamp (record both numbers in the drill log).
- Every audit-log row from the last hour pre-snapshot is present.
- Smoke a daemon: `prexorctl token create` on staging, enrol a
  fresh daemon, deploy a one-instance group, verify the instance
  reaches `RUNNING`.

### Step 7 — Record times (target: 2 minutes)

Wall-clock from "decision to restore" → "smoke test green" is
**measured RTO**.
Wall-clock from `manifest.timestamp` → "restore complete" is
**measured RPO**.

If a measured value blows past the target, the target is not the
fact — the drill is. Update [Backups and DR](/operations/backups-and-dr/),
escalate the gap, and treat closing it as the next on-call carry-over
item.

### Step 8 — Tear down (target: 2 minutes)

```bash
docker compose -p prexorcloud-staging down -v
```

Drop the volumes. Log the drill in your on-call doc using the format
below.

## Total target wall-clock: ~26 minutes

Real numbers will differ. If your drill runs to >60 minutes, the gap
is usually one of:

- Off-host backup retrieval (network / decryption).
- A schema migration on first start (one-time).
- Operator credentials missing; the drill becomes a credential hunt.

## Other scenarios worth drilling

| Scenario | Tier | Recommended cadence |
|---|---|---|
| Mongo full-restore from hourly | 1 | Quarterly |
| Filesystem-only restore (config + CA) | 3 | Quarterly |
| Lost daemon — re-issue cert and rejoin | 4 | Annually |
| HA controller failover (kill one, watch peer take leases) | n/a | Quarterly |
| CA rotation drill | 3 (worst) | Annually |
| Total Mongo loss, no backup (tabletop only) | 1 | Annually, tabletop |

PrexorCloud doesn't ship synthetic drills for the last three; they
are operator-led tabletops. The runbooks under `docs/runbooks/` are
the source material.

## Recording the drill

A two-line entry pasted into the on-call channel and the on-call
doc:

```text
DR drill 2026-05-10
- Scenario: Mongo full-restore from 13:00 UTC manifest
- Measured RPO: 47 min
- Measured RTO: 22 min
- Notes: validator caught a `composition_plans` schema drift from
  the backup → re-ran with --skip-mongo on that collection.
```

Three numbers and one note. Anything more is over-engineering. Anything
less and you don't actually have a number to defend.

## When the drill catches a real problem

Backup tooling regressions caught by drills are the cheap-time
discoveries. Take them seriously:

- **Manifest schema drift.** A new collection / index was added in a
  release without backup-format coverage. Open an issue against the
  release; pin the workaround in the runbook until the next release.
- **Restore validator regression.** The validator passed dry-run but
  the apply failed. File a P1 — `RestoreExecutor` is meant to be the
  authoritative pre-flight.
- **Post-restore state divergence between releases.** State after
  restore differs from state before backup beyond expected drift.
  This is what the nightly DR drill is supposed to catch — if a
  manual drill catches it instead, the synthetic seed isn't covering
  enough of the state surface. Add a fixture.

## Why the synthetic harness alone isn't enough

The CI drill exercises the *code path* — backup creation, manifest
verification, drop, restore, dry-run + apply, post-restore state
match. It does not exercise:

- Off-host retrieval.
- Operator decision-making under partial information.
- Documentation accuracy ("which manifest do I pick?").
- Any failure mode that only happens at production data scale.

The manual drill complements the CI drill. Run both.

## Next up

- [Backups and DR](/operations/backups-and-dr/) — RPO/RTO targets and the restore command surface
- [HA Setup](/operations/ha-setup/) — failover model that avoids most restore scenarios
- [Production Checklist](/operations/production-checklist/) — alert wiring that makes the page fire on time
