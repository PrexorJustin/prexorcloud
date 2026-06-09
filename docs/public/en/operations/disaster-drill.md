---
title: Disaster drill
description: The automated nightly DR drill that gates PrexorCloud — what it tests, how the recovery harness works, how to run it locally, and how to read a failure.
---

PrexorCloud ships an automated disaster-recovery drill that runs every night in CI. It does a full backup → wipe → restore cycle against a real Mongo and Valkey, then asserts the restored Controller is indistinguishable from the pre-incident snapshot. A red run is a DR regression — a change broke backup, restore, or the validator — and you stop and investigate before merging.

This page is for the developer reading a failed nightly run and the operator who wants to run the same drill locally before trusting a backup. It covers what the drill exercises, the recovery harness behind it, how to run it, and how to read the result.

## What it is

| Property | Value |
|---|---|
| Test class | `DrDrillTest.backupRestoreCycle_recoversFullDeclarativeState` |
| Location | `java/cloud-test-harness/src/test/.../tests/DrDrillTest.java` |
| JUnit tag | `@Tag("dr")` |
| Gradle task | `:cloud-test-harness:drDrill` |
| CI job | `dr-drill` in `.github/workflows/nightly.yml` |
| Schedule | Nightly, `cron: '0 2 * * *'` (02:00 UTC); also `workflow_dispatch` |
| Dependencies | A reachable Mongo and Valkey |
| Task timeout | 10 minutes |

The drill is tagged `dr` and excluded from the default test pass. The regular suite runs `excludeTags("perf", "dr")`; the `drDrill` task opts back in with `includeTags("dr")`. The drill never runs by accident in a normal `./gradlew test`.

## What it tests

The drill walks the real recovery code path end to end. Each numbered step maps to code in `DrDrillTest`:

1. **Boot.** Start an in-process Controller against a real Mongo + Valkey via `TestCluster.startWithRedis()`. The backup directory lives inside the Controller working directory.
2. **Seed a fixture.** Create one Template (`dr-drill-template`, platform `PAPER`) and two Groups with distinct shapes:
   - `dr-drill-lobby` — `minInstances=0`, `maxInstances=2`, `maxPlayers=50`, `priority=5`
   - `dr-drill-survival` — `minInstances=0`, `maxInstances=4`, `maxPlayers=100`, `priority=10`
3. **Snapshot.** Record the declarative state: the sorted Group set, each Group's `platform`/`platformVersion`/`minInstances`/`maxInstances`/`maxPlayers`/`priority`, and the sorted Template set.
4. **Back up.** `POST /api/v1/backups` → `201`, capture the manifest `id`.
5. **Verify before destroying.** `POST /api/v1/backups/{id}/verify` → `200`; assert `valid: true`. The drill refuses to wipe anything against an unverified backup.
6. **Simulate the catastrophe.** Stop the Controller, then `TestCluster.wipeDatastores()` drops the Mongo database and flushes the Valkey logical DB. The on-disk backup bundle survives because it sits in the Controller working directory, not in either datastore. Restart the Controller against the now-empty stores.
7. **Confirm the loss.** Re-snapshot. Assert every seeded Group is gone. (Default Templates may be re-seeded by bootstrap, so the drill only asserts the user-created fixtures vanished — not that the Template set is empty.)
8. **Dry-run the restore.** `POST /api/v1/restore` with `dryRun=true`, `filesystem=true`, `datastores=true` → `200`; assert the response echoes `dryRun: true`. A dry run validates without touching state.
9. **Apply the restore.** Same call with `dryRun=false` → `200`.
10. **Re-login.** The Mongo restore swaps out the user collection, so the pre-wipe admin JWT no longer verifies. `TestCluster.loginAs("admin", ...)` mints a fresh token signed against the restored admin record.
11. **Assert exact match.** Re-snapshot and compare against step 3 with `assertSnapshotsMatch`:
    - Group set identical (`assertEquals` on the sorted name list)
    - Template set identical
    - Every Group's full config map identical, name by name

If any assertion fails, the test fails and the CI job goes red.

### What it does not test

The drill exercises the code path, not the production environment. It does not measure or cover:

- Wall-clock RTO at production data scale (the seed is three objects).
- Off-host backup retrieval, decryption, or transfer latency.
- Operator credentials, runbook accuracy, or decision-making under partial information.
- HA controller failover or Raft cluster recovery — see [Related runbooks](#related-runbooks).

Those are the job of the manual quarterly drill, covered under [Run a manual drill](#run-a-manual-drill-quarterly).

## The recovery harness

`TestCluster` is the harness that makes the drill hermetic. The methods the drill leans on:

| Method | What it does |
|---|---|
| `startWithRedis()` | Boots a Controller with a real Mongo + an isolated Valkey logical DB. |
| `stopController()` | Closes the Controller bootstrap without starting a replacement, so a wipe can happen in between. Sleeps 500 ms. |
| `wipeDatastores()` | `mongo.getDatabase(databaseName).drop()` plus a Valkey `FLUSHDB` on the isolated logical DB. |
| `startControllerAfterStop()` | Restarts against the **same** working directory, so the on-disk backup catalog survives the wipe. |
| `loginAs(user, pass)` | `POST /api/v1/auth/login`, returns a fresh JWT. Needed after restore swaps the user collection. |
| `mongoAvailable()` / `redisAvailable()` | TCP reachability probes used by the assumptions guard. |

### Datastore connection

The harness resolves Mongo and Valkey URIs in this order:

| Source | Mongo | Valkey |
|---|---|---|
| System property | `-Dprexor.test.mongoUri` | `-Dprexor.test.redisUri` |
| Environment variable | `PREXOR_TEST_MONGO_URI` | `PREXOR_TEST_REDIS_URI` |
| Default | `mongodb://127.0.0.1:27017` | `redis://127.0.0.1:6379` |

### Skip, not fail, when dependencies are missing

The drill opens with two assumptions:

```java
Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for the DR drill");
Assumptions.assumeTrue(TestCluster.redisAvailable(), "Redis/Valkey is required for the DR drill");
```

If either datastore is unreachable, the test is reported **skipped**, not failed. A skip is not a pass — in CI it means the service containers never came up. The `dr-drill` job waits up to 60 seconds for both ports before running Gradle and fails loudly if they never bind, so a real CI skip should be rare.

## The REST contract under test

The drill calls the same routes an operator uses. They live in `BackupRoutes`.

### Create a backup

```bash
POST /api/v1/backups
```

Returns `201` with the `BackupManifest` (`id`, `sizeBytes`, `mongoDocumentCount`, and more). Requires the `BACKUPS_MANAGE` permission. Creating a backup also silently prunes to `backup.retentionCount`.

### Verify a backup

```bash
POST /api/v1/backups/{id}/verify
```

Returns `200` with a validation report. The drill asserts on `valid`. The full shape:

| Field | Meaning |
|---|---|
| `id` | Manifest id. |
| `valid` | `true` only when every check below is empty. |
| `missingFiles` | Required files absent from the bundle. |
| `missingDirectories` | Required directories absent. |
| `missingMongoCollections` | Expected collections not captured. |
| `missingMongoCollectionPrefixes` | Expected prefixed collection groups not captured. |
| `missingRedisPrefixes` | Expected Valkey key prefixes not captured. |
| `emptyRequiredFiles` | Files present but empty. |

Requires `BACKUPS_VIEW`.

### Restore

```bash
POST /api/v1/restore
```

Body fields, with the defaults the route applies:

| Field | Type | Default | Effect |
|---|---|---|---|
| `id` | string | — (required; `400 VALIDATION_ERROR` if absent) | Manifest to restore. |
| `dryRun` | boolean | `false` | Validate only, touch nothing. |
| `filesystem` | boolean | `true` | Restore the filesystem scope (config, CA, Templates, Groups). |
| `datastores` | boolean | `true` | Restore Mongo + Valkey. |

Returns `200` with a per-scope report (`filesystem.applied`, `filesystem.entryCount`, `filesystem.rollbackRoot`; `datastores.mongoCollections`, `datastores.mongoPrefixGroups`, `datastores.redisPrefixes`). If the bundle fails validation, the route returns **`422 RESTORE_REJECTED`** and tells you to run the verify route. Requires `BACKUPS_RESTORE`.

## Run the drill locally

The drill is the cheapest way to confirm a backup actually restores before you trust it in production.

### Prerequisites

- JDK 25 (the harness builds against the Java 25 preview toolchain).
- A reachable Mongo and Valkey.

Start ephemeral datastores with Docker:

```bash
docker run -d --name dr-mongo -p 27017:27017 mongo:8
docker run -d --name dr-valkey -p 6379:6379 valkey/valkey:8-alpine
```

### Run

```bash
cd java
PREXOR_TEST_MONGO_URI=mongodb://127.0.0.1:27017 \
PREXOR_TEST_REDIS_URI=redis://127.0.0.1:6379 \
./gradlew :cloud-test-harness:drDrill
```

With the datastores on their default ports you can drop the env vars — the harness falls back to `mongodb://127.0.0.1:27017` and `redis://127.0.0.1:6379`.

Point at non-default datastores with either the env vars above or the system properties:

```bash
./gradlew :cloud-test-harness:drDrill \
  -Dprexor.test.mongoUri=mongodb://db.internal:27017 \
  -Dprexor.test.redisUri=redis://cache.internal:6379
```

### Expected output

A green run ends with the harness ASCII summary:

```text
================================================================================
  PREXORCLOUD TEST HARNESS REPORT
================================================================================

  Total: 1  |  Passed: 1  |  Failed: 0  |  Skipped: 0  |  Time: ...

  Pass Rate: 100.0%  [ALL GREEN]
```

`BUILD SUCCESSFUL` and one passing test is the only acceptable result. `Skipped: 1` means a datastore was unreachable — fix that and rerun; a skip is not a pass.

## Read the results

The harness writes both a console summary and a machine-readable JSON report.

### In CI

The `dr-drill` job uploads `java/cloud-test-harness/build/reports/` as the `nightly-dr-drill-reports` artifact (`if: always()`, retained 14 days). Download it from the workflow run, then read the JSON:

```bash
cat build/reports/test-harness/test-harness-report.json
```

### The JSON report

`test-harness-report.json` (written to the dir given by `-Dtest.report.dir`, which the build sets to `build/reports/test-harness/`):

| Field | Meaning |
|---|---|
| `timestamp` | Run end time, ISO-8601. |
| `durationMs` | Total wall-clock. |
| `totalTests` / `passed` / `failed` / `skipped` | Counts. For the drill, `totalTests` is 1. |
| `passRate` | `passed / (passed + failed) * 100`, one decimal. |
| `suites[]` | Per-suite breakdown with per-test `name`, `status`, `durationMs`, and `failure` message. |
| `categories{}` | Category rollups. |
| `failures[]` | One entry per failed test: `suite`, `test`, `message`. |

The fast triage path on a red run: read `failures[].message`. The drill's assertion messages name the exact divergence, for example `post-restore group dr-drill-lobby config diverged from pre-incident snapshot`.

### What a failure means

The drill fails closed, and the failing assertion tells you which stage broke:

| Symptom | Likely cause |
|---|---|
| `fresh backup must verify clean` | `BackupCreator` or the bundle layout regressed — verify is rejecting a just-made backup. |
| `group ... survived the wipe — DR drill premise broken` | `wipeDatastores()` did not actually clear state; the test premise is invalid. Check the datastore wiring, not the restore. |
| `422` on restore in the test logs | `RestoreValidator` rejected the bundle. A collection, prefix, or required file the scope expects is missing from the backup format. |
| `post-restore group set diverged` / `template set diverged` | Restore ran but the declarative state came back wrong — restore dropped or mangled objects. |
| `post-restore group <name> config diverged` | A specific field round-tripped incorrectly (a new Group field added without backup coverage is the classic cause). |
| Test **skipped** in CI | Mongo or Valkey container never bound. Not a DR regression — a CI infra problem. |

A genuine red `dr-drill` (not a skip) is a release blocker. Backup and restore are the last line of defense; treat a regression in them as P1.

## Run a manual drill (quarterly)

Keep running a real-environment drill at least quarterly even with the nightly job green. The synthetic seed is three objects on localhost; it cannot catch what your environment hides — off-host retrieval latency, stale operator credentials, permission drift on `data/certs/`, or anything that only appears at production data scale.

The manual drill uses the same REST surface the automated drill does, against a staging stack restored from a real production manifest:

```bash
# 1. Pick the manifest to restore.
prexorctl backup list

# 2. Verify it before trusting it.
prexorctl backup verify <id>

# 3. Dry-run against staging.
prexorctl --controller https://staging:8080 restore <id> --dry-run

# 4. Apply.
prexorctl --controller https://staging:8080 restore <id> --filesystem --datastores

# 5. Re-login (restore overwrote the user collection) and validate.
prexorctl --controller https://staging:8080 login
prexorctl group list
prexorctl module list
```

Measure two numbers and record them in your on-call log:

- **RPO** — manifest timestamp → restore complete.
- **RTO** — decision to restore → smoke test green.

If a measured number blows past the target in [Backups and DR](/operations/backups-and-dr/), the drill is the fact and the target is wrong. Escalate the gap.

## Related runbooks

- [Backups and DR](/operations/backups-and-dr/) — RPO/RTO targets and the full backup/restore command surface.
- [HA setup](/operations/ha-setup/) — the failover model that avoids most restore scenarios.
- Cluster failure recovery (`docs/runbooks/recover-cluster.md`) — HA controller quorum loss and the single-survivor reset. The DR drill does not cover this path.
- Mongo, Valkey, and single-Controller recovery (`docs/runbooks/recover-mongo.md`, `recover-redis.md`, `recover-controller.md`).
