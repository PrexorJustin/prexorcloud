---
title: Utilities + Scripting
description: prexorctl logs, diagnostics, deploy, backup, restore, completion, plus JSON output, exit codes, and CI scripting patterns.
---

The smaller commands and the scripting surface that ties everything
together: log tailing, diagnostics bundles, deployment promotion,
backup/restore, shell completion, and the `--json` contract every
command honours.

## What you'll learn

- The remaining utility commands that don't fit elsewhere.
- The `--json` output contract.
- Exit codes, env vars, and CI patterns proven in our own pipelines.

## `prexorctl logs`

Tail controller or daemon logs through the controller's REST/SSE
surface. Supports follow mode and source filtering.

```bash
prexorctl logs --source controller --follow
prexorctl logs --source daemon --node node-1 --since 5m
prexorctl logs --source instance --instance lobby-1 --tail 200
```

Flags:

- `--source controller|daemon|instance` — log producer.
- `--node <id>` — daemon source only.
- `--instance <id>` — instance source only.
- `--follow` — stream new lines as they arrive (SSE).
- `--since <duration|iso8601>` — start point.
- `--tail <n>` — number of lines to replay before following.

## `prexorctl diagnostics`

Collects controller config, daemon registrations, recent crash reports,
and a snapshot of `system/health` + `system/ready` into a single
tarball, redacting tokens and Mongo/Redis URIs along the way. Attach
the resulting `prexorcloud-diagnostics-<timestamp>.tar.gz` to bug
reports.

```bash
prexorctl diagnostics
prexorctl diagnostics --output ./diag.tar.gz --include logs,crashes
```

Flags:

- `--output <path>` — destination archive (default: cwd).
- `--include <list>` — comma-separated bundle sections to include
  (`config`, `logs`, `crashes`, `health`, `nodes`).

## `prexorctl deploy`

Promotes a deployment revision for a group. Used by CI that uploads a
new template version, then atomically points the group at it.

```bash
prexorctl deploy <group> --revision 17
prexorctl deploy <group> --strategy rolling --max-unavailable 1
```

Flags:

- `--revision <n>` — explicit deployment revision to promote.
- `--strategy rolling|recreate` — how the controller should drain old
  instances.
- `--max-unavailable <n>` — concurrent stop budget.

## `prexorctl backup` and `prexorctl restore`

Snapshot the controller's Mongo state (groups, templates, users,
modules) and replay it into a fresh controller. See
[Disaster Recovery](/operations/disaster-drill/) for the full
recipe; the commands themselves are thin wrappers over the REST
backup endpoints.

```bash
prexorctl backup --output controller-backup.tar.gz
prexorctl restore --input controller-backup.tar.gz --confirm
```

## `prexorctl completion`

Generate shell completion scripts.

```bash
prexorctl completion bash > /etc/bash_completion.d/prexorctl
prexorctl completion zsh  > "${fpath[1]}/_prexorctl"
prexorctl completion fish | source
```

## JSON output contract

Every command that returns data accepts `--json` and emits one of:

- A JSON object — for single-resource commands (`group info`,
  `instance info`, `version`).
- A JSON array — for `* list` commands.
- A JSON `{"status":"<verb>",...}` envelope — for action commands
  (`template rollback`, `instance start`).

JSON mode implies `--no-color` and never prompts. `PREXOR_OUTPUT=json`
flips the default for all commands at once — useful in CI shells.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | Generic error |
| `2` | `401` — not authenticated |
| `3` | `403` — forbidden |
| `4` | `404` — resource missing |
| `5` | `409` / `422` — conflict or invalid state |
| `6` | Network / unreachable controller |

## CI patterns

Drive the CLI from a CI job without hitting the on-disk config:

```bash
export PREXOR_CONTROLLER=https://controller.internal:8080
export PREXOR_TOKEN=$CI_PREXOR_TOKEN
export PREXOR_OUTPUT=json

prexorctl status \
  | jq -e '.degraded == false' \
  || { echo "cluster degraded"; exit 1; }
```

Wait for an instance to reach `RUNNING`:

```bash
ID=$(prexorctl instance start lobby --json | jq -r '.id')
until [ "$(prexorctl instance info "$ID" --json | jq -r '.state')" = "RUNNING" ]; do
  sleep 2
done
```

Promote a new template version, only on green diff:

```bash
HASH=$(prexorctl template versions my-config --json | jq -r '.[0].hash')
[ "$HASH" = "$EXPECTED_HASH" ] || prexorctl template rollback my-config
```

## Next up

- [Setup + Auth](/reference/cli/setup-and-auth/) — `PREXOR_*` env vars.
- [Operations runbook](/operations/production-checklist/) — playbooks that lean on
  these commands.
- [Disaster recovery](/operations/disaster-drill/) — `backup` and
  `restore` in context.
