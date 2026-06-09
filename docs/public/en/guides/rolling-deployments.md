---
title: Rolling deployments
description: Roll a template or runtime change across a group's instances with no player downtime — canary wave, batch size, health gate, pause, resume, and rollback.
---

A deployment rolls a group's current Template chain and Module composition onto its running Instances, one wave at a time. The Controller stops an outdated Instance, lets placement start a replacement on the new revision, waits for that replacement to come up (optionally to pass a health gate), then moves to the next wave. Players move off each Instance before it stops, so the group stays available throughout.

This guide covers the full rollout surface: triggering a deploy, the canary wave, batch size, the health gate, and pause / resume / rollback. Every command and flag here is the real CLI and REST contract.

## Before you start

- A PrexorCloud Controller (v1.0+) and at least one Daemon.
- A Group with two or more RUNNING Instances. `prexorctl group list` shows it.
- A change staged on the group's Template chain or its runtime/module composition (see [Push the change](#push-the-change-first)).
- A token with the `GROUPS_UPDATE` permission. Triggering, pausing, resuming, and rolling back a deployment all require it; reading deployment history needs `GROUPS_VIEW`.

## How a rolling deployment works

The Controller tracks each rollout as a `DeploymentRecord` with a monotonic `revision` (the previous revision + 1, starting at 1). A background reconciler drives the rollout independently of the main scheduler loop:

1. Count `total` = the group's RUNNING Instances at trigger time, and `updated` = Instances already at or beyond the target revision.
2. Take the next wave. The first wave is the canary; later waves use the batch size.
3. For each Instance in the wave, send a graceful stop. Placement starts a replacement on the new revision.
4. Wait for the replacements to reach the new revision (the replacement wait).
5. If the health gate is on, wait for the wave's updated Instances to be healthy (and stable, if `minHealthySeconds` is set). If they crash or time out, halt.
6. Repeat until `updated == total`.

An Instance counts as "updated" when its `deploymentRevision` is at or beyond the target revision and it is not `STOPPED` or `CRASHED`. Players are moved off each Instance by the Network proxy Plugin as the Instance enters `STOPPING`; for a lobby fronted by a proxy, the proxy walks the Network Composition fallback chain. See [Network Composition](/concepts/groups-instances-templates/).

The whole rollout holds a per-group distributed lease, so in a multi-Controller cluster exactly one Controller drives a given group's deployment. If the lease is lost mid-roll, the reconciler stops cleanly and the owning Controller resumes.

## Push the change first

`prexorctl deploy` rolls out the group's **current** Template chain and composition. It does not stage a change for you — stage it first, then deploy.

Template files are uploaded over REST (multipart), not by a dedicated CLI verb:

```
POST /api/v1/templates/{name}/files/upload
```

Each upload stores a new content-addressed version of the Template. Existing Instances keep serving the version they booted with until you roll. Confirm the new version landed:

```bash
prexorctl template versions lobby
```

```
Versions of template lobby

  #   HASH      SIZE     CREATED
  2   a1b2c3d4  4.1 KB   2026-06-07T12:00:00Z
  1   9f8e7d6c  4.0 KB   2026-06-06T08:00:00Z

  2 versions
```

For a runtime or module-composition change, edit the group with `prexorctl group update <name>` (or the dashboard) before deploying. A plain `group update` does not start a rollout on its own — you trigger it explicitly.

## Trigger the rollout

```bash
prexorctl deploy lobby
```

`prexorctl deploy <group>` prints a PLAN block, asks for confirmation, then triggers the deployment and streams a live view that polls the deployment record:

```
  Reading deploy plan for group lobby

  PLAN  group lobby  • strategy rolling  • canary 0  • healthcheck on

    • image        paper-1.21
    • templates    lobby
    • batch size   group default
    • scaling      LOAD

  Confirm rollout? [y/N]
```

Pass `-y` / `--yes` to skip the prompt (for scripts and CI). Pass `--json` to trigger and emit the raw deployment record with no TUI.

### Rollout flags

Every flag is optional. An omitted flag falls back to the group's update strategy default; the Controller resolves and stores the effective values in the deployment's config snapshot.

| Flag | Body field | Type | Meaning |
|---|---|---|---|
| `--strategy` | `strategy` | string | Rollout strategy. Defaults to the group's `updateStrategy` (default `ROLLING`). |
| `--batch-size` | `batchSize` | int `>= 1` | Instances replaced per wave after the canary. Default `1`. |
| `--canary-instances` | `canaryInstances` | int `>= 0` | Size of the first (canary) wave. Default `0` (no canary). |
| `--canary-percent` | `canaryPercent` | int `0`–`100` | Canary wave as a percentage of total Instances. Resolved with `ceil`, clamped to `[1, total]`. Mutually exclusive with `--canary-instances`. |
| `--health-gate` | `healthGateEnabled` | bool | Block the next wave until the current wave's updated Instances are healthy. Default `false`. |
| `--auto-rollback` | `autoRollbackOnFailure` | bool | On a failed health gate, mark the deployment `ROLLED_BACK` instead of `FAILED`. Default `false`. |
| `--promotion-timeout` | `promotionTimeoutSeconds` | int `>= 1` | Health-gate wait budget per wave. Default `0`, which falls back to `2 ×` the scheduler evaluation interval. |
| `--min-healthy` | `minHealthySeconds` | int `>= 0` | A replacement must have at least this uptime to count as healthy for the gate. Default `0`. |
| `-y`, `--yes` | — | — | Skip the confirmation prompt. |
| `--json` | — | — | Trigger and print the deployment record as JSON; no live view. |

Validation is enforced server-side and returns `400 BAD_REQUEST`:

- `batchSize` must be `>= 1`.
- `canaryInstances` must be `>= 0`.
- `canaryPercent` must be between `0` and `100`.
- You cannot set both `canaryInstances` and `canaryPercent`.
- `promotionTimeoutSeconds` must be `>= 1` when given.
- `minHealthySeconds` must be `>= 0`.

Worked example — canary one Instance, then one at a time, gated on 60 s of healthy uptime, auto-rollback on failure:

```bash
prexorctl deploy lobby \
  --canary-instances 1 \
  --batch-size 1 \
  --health-gate \
  --min-healthy 60 \
  --auto-rollback \
  --yes
```

### What the canary actually is

The canary is the **first wave** of the rollout, not an extra spawned Instance. With `--canary-instances 1`, the reconciler replaces one existing outdated Instance first, runs the health gate against it, and only then proceeds to the batch-sized waves. A failed canary halts the rollout before any further Instance is touched.

Wave sizing, exactly:

- First wave (`updated == 0`) with a canary set: `min(remaining, canaryInstances)`.
- Every other wave: `min(remaining, batchSize)`.

With three Instances, `--canary-instances 1 --batch-size 1`, the waves are `1, 1, 1`. With `--batch-size 2` and no canary, the waves are `2, 1`.

## Watch progress

The `deploy` command's built-in view polls `GET /api/v1/groups/{name}/deployments/{rev}` until the deployment leaves `IN_PROGRESS`. To inspect a deployment from another terminal or after the fact:

```bash
prexorctl deploy show lobby 2
```

```
Deployment r2 for lobby

  State       IN_PROGRESS
  Strategy    rolling
  Trigger     manual
  Progress    1/3 instances
  Created     2026-06-07T12:00:00Z

Rollout

  Batch Size          1
  Canary Instances    1
  Health Gate         true
  Auto-Rollback       true
  Promotion Timeout   0s
  Min Healthy         60s
```

List the group's deployment history:

```bash
prexorctl deploy list lobby
```

```
  REV   STATE         STRATEGY   TRIGGER   PROGRESS              CREATED
  r2    IN_PROGRESS   rolling    manual    1/3 ████░░░░░░░░       2026-06-07T12:00:00Z
  r1    COMPLETED     rolling    manual    3/3 ████████████       2026-06-06T08:00:00Z
```

`--page` and `--page-size` (max 100) page the history. `--json` on any of these emits the raw record.

### Deployment states

A `DeploymentRecord.state` is one of:

| State | Meaning |
|---|---|
| `IN_PROGRESS` | Reconciler is rolling waves. |
| `PAUSED` | Operator paused it; no waves run until resumed. |
| `COMPLETED` | `updated == total`. Every Instance is on the new revision. |
| `FAILED` | A health gate failed and `--auto-rollback` was off. |
| `ROLLED_BACK` | Rolled back by the operator, or by a failed gate with `--auto-rollback` on. |

`trigger` is `manual` for `prexorctl deploy` and `rolling_restart` for a plain group restart.

## The health gate

The health gate runs only when `--health-gate` is set. After each wave's replacements come up, the reconciler waits, per wave, for the wave's updated Instances to be healthy:

- **Without** `--min-healthy`: an updated Instance counts as healthy as soon as it is `RUNNING`.
- **With** `--min-healthy N`: it must be `RUNNING` and have at least `N` seconds of uptime.

The wait budget per wave is `promotionTimeoutSeconds`, or `2 ×` the scheduler evaluation interval when that is `0`. The gate fails — and halts the rollout — if either:

- Any updated Instance enters `CRASHED`, or
- The wait budget elapses before enough updated Instances are healthy.

On a failed gate, the deployment becomes `ROLLED_BACK` when `--auto-rollback` is on, otherwise `FAILED`. In both cases the rollout stops where it is; Instances already moved to the new revision stay there. Marking the record `ROLLED_BACK` does not re-deploy the old revision — restoring Template or Module state is operator-driven (see [Roll back](#roll-back)).

Without `--health-gate`, the reconciler still waits for each wave's replacements to reach the new revision (up to `2 ×` the evaluation interval) before continuing, but it never inspects health and never auto-halts. It logs a warning and continues if no replacement appears in time.

## Pause, resume, rollback

Each control action is its own subcommand and REST endpoint, addressed by group and revision.

### Pause

```bash
prexorctl deploy pause lobby 2
```

Sets the deployment to `PAUSED`. The reconciler finishes any Instance stop already in flight, then stops taking new waves. Use this to investigate a misbehaving rollout without losing rollout progress.

```
POST /api/v1/groups/{name}/deployments/{rev}/pause
```

### Resume

```bash
prexorctl deploy resume lobby 2
```

Sets the deployment back to `IN_PROGRESS` and restarts the reconciler from where it left off. It recounts updated Instances, so already-rolled Instances are not touched again.

```
POST /api/v1/groups/{name}/deployments/{rev}/resume
```

### Roll back

```bash
prexorctl deploy rollback lobby 2
```

Marks the deployment `ROLLED_BACK` and stops the rollout. This is a state change only — it does not redeploy the previous revision. To actually restore the old Template content, roll the Template back and deploy again:

```bash
prexorctl template rollback lobby
prexorctl deploy lobby
```

```
POST /api/v1/groups/{name}/deployments/{rev}/rollback
```

`prexorctl template rollback <name>` reverts the Template to its previous content-addressed version; the follow-up `deploy` rolls that older content onto the Instances.

## Verify the rollout

A deployment is done when its state is `COMPLETED` and progress reads `total/total`:

```bash
prexorctl deploy show lobby 2
```

```
  State       COMPLETED
  Progress    3/3 instances
  Completed   2026-06-07T12:03:10Z
```

Cross-check at the Instance level. Every Instance should report the new `deploymentRevision`:

```bash
prexorctl group info lobby
```

The Instance list in `group info` shows each Instance's state. An Instance still on the old revision was not replaced — it was `STOPPED`, `CRASHED`, or not `RUNNING` at trigger time, so the rollout's `total` never counted it.

## Edge cases and pitfalls

| Symptom | Cause | Fix |
|---|---|---|
| `400 BAD_REQUEST` on deploy | A flag failed validation (e.g. `batchSize < 1`, both canary flags set). | Read the error body; it names the offending field. |
| Deploy completes instantly, `0/0` | The group had no RUNNING Instances at trigger time. `total` is counted then, once. | Scale the group up, then deploy. |
| Rollout never advances past a wave | Replacements aren't reaching the new revision (placement blocked: no free Daemon capacity or port range). | Check Daemon capacity and the group's port range; the reconciler logs `cannot stop ... right now` and leaves the deploy `IN_PROGRESS`. |
| Health gate fails immediately | A replacement crash-looped to `CRASHED`. | Inspect the new revision's logs; fix the Template/jar, then deploy a new revision. |
| Gate times out though Instances look up | `--min-healthy` longer than the wait budget, or boot slower than the budget. | Raise `--promotion-timeout`; for cold Paper starts allow 30–90 s. |
| Rollback "did nothing" to running Instances | `rollback` is a state change, not a redeploy. | Roll the Template back and deploy again — see [Roll back](#roll-back). |
| A second deploy of the same revision is ignored | The scheduler dedupes concurrent rolling restarts for the same group + revision. | Wait for the active one, or trigger a fresh deploy (it gets the next revision). |
| Players get kicked instead of moved | The group isn't behind a proxy with Network Composition fallbacks. | Put it behind a Velocity/Bungee Network. See [Network Composition](/concepts/groups-instances-templates/). |

## Reference

CLI:

```text
prexorctl deploy <group> [--strategy …] [--batch-size N] [--canary-instances N]
                         [--canary-percent N] [--health-gate] [--auto-rollback]
                         [--promotion-timeout S] [--min-healthy S] [-y] [--json]
prexorctl deploy list <group> [--page N] [--page-size N] [--json]
prexorctl deploy show <group> <rev> [--json]
prexorctl deploy pause <group> <rev>
prexorctl deploy resume <group> <rev>
prexorctl deploy rollback <group> <rev>
```

REST:

| Method | Path | Permission |
|---|---|---|
| `POST` | `/api/v1/groups/{name}/deploy` | `GROUPS_UPDATE` |
| `GET` | `/api/v1/groups/{name}/deployments` | `GROUPS_VIEW` |
| `GET` | `/api/v1/groups/{name}/deployments/{rev}` | `GROUPS_VIEW` |
| `POST` | `/api/v1/groups/{name}/deployments/{rev}/pause` | `GROUPS_UPDATE` |
| `POST` | `/api/v1/groups/{name}/deployments/{rev}/resume` | `GROUPS_UPDATE` |
| `POST` | `/api/v1/groups/{name}/deployments/{rev}/rollback` | `GROUPS_UPDATE` |
| `POST` | `/api/v1/groups/{name}/restart` | `GROUPS_UPDATE` |

`POST /deploy` returns `202` with the created `DeploymentRecord`.

## Where to go next

- [Concepts → Deployments](/concepts/deployments/) — the deployment record model and revision semantics.
- [Recipes → CI/CD deployments](/recipes/cicd-deployments/) — drive `prexorctl deploy --yes --json` from a pipeline.
- [Guides → Crash recovery](/guides/crash-recovery/) — what happens when a new revision crash-loops after the gate passes.
