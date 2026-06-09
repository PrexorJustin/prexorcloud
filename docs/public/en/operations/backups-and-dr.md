---
title: Backups and disaster recovery
description: The backup-orchestrator module — config-snapshot scope, the 64 KiB daemon read cap, REST and scheduled snapshots, restore, and the DR posture each data tier needs.
---

PrexorCloud ships one first-party backup component: the `backup-orchestrator` Module. It snapshots the **config and small text files** of running Instances by reading them through the Daemon and packing them into a controller-local `tar.gz`. It does not back up MongoDB, Valkey, the Controller filesystem, or Minecraft world data — those tiers are the operator's responsibility, and this page tells you exactly which tool covers which tier.

Read the [scope](#what-the-module-captures) section first. It is the part most likely to surprise you: a snapshot is config files, nothing more.

## What you'll learn

- What `backup-orchestrator` captures and the hard limits that define that scope
- How to trigger a snapshot over REST and how to run periodic snapshots
- Where archives land and how to ship them off-host
- How to restore a config snapshot (there is no restore command — you untar it)
- The DR posture for every tier the module does *not* cover

## The module at a glance

| Property | Value | Source |
|---|---|---|
| Module id | `backup-orchestrator` | `src/main/module/module.yaml` |
| Version | `1.0.0` | `module.yaml` |
| Host | Controller only | `hosts: [controller]` |
| Storage | Mongo, `snapshots` collection (≤ 50 000 docs) | `module.yaml`, `SnapshotRepository` |
| Required capability | `prexor.instance.files` (`>=1.0.0 <2.0.0`) | `module.yaml` |
| Archive root | `/var/lib/prexorcloud/snapshots` (override: `PREXORCLOUD_BACKUP_DIR`) | `BackupOrchestratorModule` |
| REST mount | `/api/v1/modules/backup-orchestrator/snapshots` | module-route dispatcher + `BackupRoutes` |

The module is a Capability API consumer: it never opens its own Daemon gRPC channel. It reads Instance files through the built-in `InstanceFileAccess` capability the Controller registers as `prexor.instance.files`.

Install it like any platform Module:

```bash
prexorctl module install backup-orchestrator
prexorctl module list
```

```text
ID                    VERSION  HOST        STATE
backup-orchestrator   1.0.0    controller  ACTIVE
```

## What the module captures

A snapshot is a `tar.gz` of the config files in one Instance's working directory. That is the whole feature.

By default the snapshot picks up these filename patterns (basename only):

| Pattern | Catches |
|---|---|
| `*.properties` | `server.properties`, `paper-global.properties` |
| `*.json` | `ops.json`, `whitelist.json`, `banned-players.json` |
| `*.yml`, `*.yaml` | plugin and proxy config |
| `*.txt` | `eula.txt` and similar |
| `*.cfg`, `*.toml` | mod config (Forge/Fabric/NeoForge) |

The pattern matcher supports `*` (any run of characters) and literal characters only — no `?`, no character classes, no path separators in the pattern. Matching is against the **basename**, so `*.json` matches `config/ops.json`. Directories are always skipped.

Override the patterns per request through the REST `patterns` field. An empty or omitted list uses the defaults above.

### What is not captured

| Not captured | Why | What covers it |
|---|---|---|
| World data (region `.mca`, NBT, chunks) | Binary; the Daemon RPC encodes content as UTF-8 and round-trips binary lossily | A `prexor.instance.snapshot` capability with a Daemon-side tar handler — not yet shipped |
| MongoDB (Groups, Templates, deployments, audit, Module data) | Out of the module's scope | `mongodump` / your Mongo backup |
| Valkey coordination state | Out of scope; rebuildable | Optional `BGSAVE`; usually skipped |
| Controller filesystem (`controller.yml`, the CA in `data/certs/`) | Out of scope | Filesystem backup of the install root |
| Daemon host config and mTLS material | Out of scope | Per-host backup |

The most important line: **the CA private key under the Controller's `data/certs/` is the only irreplaceable material in the system, and `backup-orchestrator` does not touch it.** Back it up separately. If it is lost, every Daemon must rejoin from scratch.

## The 64 KiB read cap

The scope limit above is a direct consequence of how the Daemon serves file reads. Understanding the cap explains every "truncated" you will see.

The read path has three caps stacked on top of each other:

| Layer | Cap | Constant |
|---|---|---|
| Daemon `ReadInstanceFile` default | 64 KiB when the request omits `max_bytes` | `InstanceFileReader.DEFAULT_MAX_BYTES = 64 * 1024` |
| Daemon absolute ceiling | 1 MiB — the Daemon never returns more, whatever the caller asks | `InstanceFileReader.MAX_BYTES_CEILING = 1 * 1024 * 1024` |
| `backup-orchestrator` per-file request | 256 KiB | `SnapshotService.READ_MAX_BYTES = 256 * 1024` |

The module asks for **256 KiB per file**, above the 64 KiB Daemon default but well under the 1 MiB ceiling. So a config file up to 256 KiB is captured whole. A file larger than 256 KiB is captured **up to the cap** — the first 256 KiB — and its path is recorded in the snapshot's `truncatedFiles` list so you can spot the partial. The archive still lands; truncation never fails a snapshot.

Reads are head-first (first N bytes). The Daemon also supports tail reads (`tail=true`, last N bytes), but `backup-orchestrator` always reads heads.

The directory walk that feeds the read loop has its own Daemon-side caps: **5 000 entries** and **24 directory levels**, with directories over 500 children summarized rather than enumerated. The module discards summary markers and only reads concrete file paths. A walk that hits the entry or depth cap comes back with `truncated=true`.

Every walk and read blocks up to **20 seconds** and never throws. Unreachable Daemons, timeouts, and Daemon-reported errors surface as an `error` tag, not an exception:

| Error tag | Meaning |
|---|---|
| `DAEMON_UNREACHABLE` | No live Daemon channel for the node |
| `TIMEOUT` | Daemon did not reply within 20 s |
| `INSTANCE_NOT_FOUND` | No such Instance working directory on that Daemon |
| `FILE_NOT_FOUND` | Path does not exist under the Instance dir |
| `NOT_REGULAR_FILE` | Symlink or non-regular file (symlinks are never followed) |
| `PATH_OUTSIDE_INSTANCE` | Path-traversal attempt; rejected |
| `FILE_UNREADABLE` | I/O error reading the file |

A walk error short-circuits the snapshot: no archive, and an error record is still persisted so the failure is visible. A per-file read error is logged and skipped — one bad file does not abort the snapshot.

## Trigger a snapshot over REST

The REST surface is the primary interface. All four routes mount under `/api/v1/modules/backup-orchestrator/`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/snapshots` | List recent snapshots (`?instance=`, `?limit=`) |
| `POST` | `/snapshots` | Trigger a snapshot |
| `GET` | `/snapshots/{id}` | Fetch one snapshot record |
| `DELETE` | `/snapshots/{id}` | Delete the archive and its record |

### Take a snapshot

`nodeId` and `instanceId` are required. `group` may be blank for an ungrouped Instance. `patterns` is optional.

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  https://controller:8443/api/v1/modules/backup-orchestrator/snapshots \
  -d '{
        "nodeId": "node-1",
        "group": "lobby",
        "instanceId": "lobby-1"
      }'
```

A successful snapshot returns `201` with the metadata record:

```json
{
  "id": "0b1f9c2e-...",
  "instanceId": "lobby-1",
  "group": "lobby",
  "nodeId": "node-1",
  "createdAt": "2026-06-07T09:14:03Z",
  "archiveSizeBytes": 4821,
  "archivePath": "/var/lib/prexorcloud/snapshots/lobby-1/1717751643000-0b1f9c2e.tar.gz",
  "fileCount": 5,
  "truncatedFiles": [],
  "patterns": ["*.properties", "*.json", "*.yml", "*.yaml", "*.txt", "*.cfg", "*.toml"],
  "error": ""
}
```

Status codes:

| Code | Condition |
|---|---|
| `201` | Snapshot written (`error` is empty) |
| `502` | Snapshot ran but the underlying walk failed — record is returned with a populated `error` |
| `400` | `instanceId` or `nodeId` missing/blank |

Override the patterns to capture only what you need:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  https://controller:8443/api/v1/modules/backup-orchestrator/snapshots \
  -d '{"nodeId":"node-1","group":"lobby","instanceId":"lobby-1","patterns":["*.yml"]}'
```

### List and inspect

```bash
# Recent snapshots across all instances (default limit 50, max 500).
curl -sS -H "Authorization: Bearer $TOKEN" \
  'https://controller:8443/api/v1/modules/backup-orchestrator/snapshots?limit=20'

# Just one instance.
curl -sS -H "Authorization: Bearer $TOKEN" \
  'https://controller:8443/api/v1/modules/backup-orchestrator/snapshots?instance=lobby-1'

# One record by id.
curl -sS -H "Authorization: Bearer $TOKEN" \
  https://controller:8443/api/v1/modules/backup-orchestrator/snapshots/<id>
```

The list is sorted newest-first by `createdAt`. A `limit` of 0 or non-numeric falls back to 50; anything above 500 is clamped to 500.

### Delete

```bash
curl -sS -X DELETE -H "Authorization: Bearer $TOKEN" \
  https://controller:8443/api/v1/modules/backup-orchestrator/snapshots/<id>
```

Returns `204` on success, `404` if the id is unknown. The archive file is removed best-effort; the Mongo record is removed when it exists.

## Periodic snapshots

The module does not run a schedule by default. REST triggers are always available; the periodic path is opt-in through environment variables read once at module start.

| Variable | Effect | Default |
|---|---|---|
| `PREXORCLOUD_BACKUP_INTERVAL_MINUTES` | Snapshot period in minutes. `0` or absent disables the schedule. | disabled |
| `PREXORCLOUD_BACKUP_INITIAL_DELAY_MINUTES` | Delay before the first run. | `1` |
| `PREXORCLOUD_BACKUP_TARGETS` | Comma-separated `nodeId/group/instanceId` triples. | none |
| `PREXORCLOUD_BACKUP_DIR` | Archive root override. | `/var/lib/prexorcloud/snapshots` |

The schedule is active only when **both** a positive interval **and** at least one well-formed target are present. Otherwise the Module stays REST-only and logs that periodic snapshots are disabled.

Targets are configured explicitly because the Module cannot enumerate live Instances from its context — list the long-lived ones (a persistent lobby, a hub) by hand:

```bash
# In the Controller's environment.
export PREXORCLOUD_BACKUP_INTERVAL_MINUTES=60
export PREXORCLOUD_BACKUP_INITIAL_DELAY_MINUTES=5
export PREXORCLOUD_BACKUP_TARGETS="node-1/lobby/lobby-1,node-1/hub/hub-1"
```

Target parsing is total — a malformed token (not a three-part `node/group/instance` triple, or a blank node or instance) is skipped, never fatal. The `group` segment may be blank for an ungrouped Instance, written as `node-1//inst-1`. A single unreachable target in a scheduled run is logged and skipped; it does not abort the run or kill the task.

Restart the Controller after changing these — they are read once in `onStart`.

## Where archives land

```text
$PREXORCLOUD_BACKUP_DIR/
└── <instanceId>/
    └── <epochMillis>-<first-8-of-snapshotId>.tar.gz
```

The `instanceId` is sanitized (anything outside `[A-Za-z0-9_.-]` becomes `_`) before it is used as a directory name. The archive is a standard gzip-compressed POSIX tar; each entry's path mirrors its relative path inside the Instance directory.

The archive is controller-local. The module does not ship anything off-host — that is your job.

### Inspect an archive

```bash
tar tzf /var/lib/prexorcloud/snapshots/lobby-1/1717751643000-0b1f9c2e.tar.gz
```

```text
server.properties
ops.json
config/paper-global.yml
```

## Ship off-host

A snapshot on the Controller disk is one failure away from useless. Ship it.

```bash
# Encrypt and push to object storage.
SRC=/var/lib/prexorcloud/snapshots
age -r age1examplexxxxxxxxxxxxxxxxxxxxxxxxxxxxxx \
    -o /tmp/snapshots.tar.age \
    <(tar cf - -C "$SRC" .)

aws s3 cp /tmp/snapshots.tar.age s3://your-backups/prexorcloud/snapshots-$(date -u +%Y%m%dT%H%M%SZ).age
```

Use whatever encrypted off-host store fits your workflow — S3, restic, borg, rclone, Backblaze B2. The point is off-host and encrypted.

## Restore a config snapshot

There is no restore command and no restore API. A snapshot is a `tar.gz` of config files; restoring it means putting those files back into the Instance's Template or working directory and redeploying.

Typical flow after a bad config push:

```bash
# 1. Pull the archive (locally or from off-host store).
mkdir -p /tmp/restore && tar xzf <archive>.tar.gz -C /tmp/restore

# 2. Diff against current config to see what changed.
diff -ru /tmp/restore /path/to/template/files

# 3. Copy the good files back into the Template, then push and redeploy.
prexorctl template apply <template>
prexorctl group redeploy <group>
```

Restore is a manual, deliberate act. Because a snapshot is config only, restoring it never touches world data, player data, or platform state — it cannot make those worse, and it cannot recover them either.

> Truncated files matter at restore time. If a file appears in `truncatedFiles`, the archived copy is the first 256 KiB only — do not restore it blindly over a complete file. Re-fetch the full file from the Instance or another source.

## DR posture for the tiers this module does not cover

`backup-orchestrator` is one slice of a recovery plan. The rest is conventional infrastructure work. Treat this table as the checklist for the tiers the Module leaves to you.

| Tier | Source | How to back up | What "recovered" means |
|---|---|---|---|
| Durable platform state | MongoDB | `mongodump` on your cadence; ship off-host | Controller boots; every Group, Template, deployment, audit row, and Module record returns; Daemons reconnect with existing certs |
| Coordination | Valkey | Optional `BGSAVE`; usually skipped | Empty Valkey is acceptable — the Controller rebuilds leases on first reconciliation |
| Controller filesystem | `controller.yml`, `data/certs/` (the CA) | Filesystem backup of the install root | Config and CA recoverable; the CA private key is the only irreplaceable material |
| Daemon hosts | `daemon.yml` + per-Daemon mTLS | Per-host backup | Daemon restored; reconnects and reconciles Instances from the Controller |
| Instance config | Instance working-dir config files | `backup-orchestrator` (this Module) | Config files recoverable per the scope and cap above |
| Instance world data | Region/NBT files | Out of scope today — use server-side world saving / your own snapshot job | Not covered by PrexorCloud tooling yet |

Sizing your RPO is direct: RPO equals your slowest relevant cadence. If you `mongodump` hourly and snapshot Instance config daily, your platform-state RPO is one hour and your config RPO is one day. PrexorCloud does not run those crons for you.

## Common failures

| Symptom | Cause | Fix |
|---|---|---|
| `POST /snapshots` returns `502` with `error: DAEMON_UNREACHABLE` | No live Daemon channel for `nodeId` | Confirm the Daemon is connected (`prexorctl node list`); retry |
| `error: INSTANCE_NOT_FOUND` | Wrong `group`/`instanceId`, or the Instance is not running on that node | Check the Instance is alive on the named node |
| Snapshot has `fileCount: 0` | No files matched the patterns | Widen `patterns`, or confirm the Instance dir actually holds config files |
| File appears in `truncatedFiles` | File exceeds the 256 KiB per-file cap | Expected for large files; do not restore the partial over a complete file |
| Snapshot silently misses a deep file | Walk hit the 5 000-entry / 24-level cap (`truncated=true` on the walk) | Reduce the patterns; deep nested config beyond the cap is not enumerated |
| Periodic snapshots never run | Interval is `0`/absent or no valid target | Set both `PREXORCLOUD_BACKUP_INTERVAL_MINUTES` (> 0) and a valid `PREXORCLOUD_BACKUP_TARGETS`; restart the Controller |
| Module fails to load: missing capability | `prexor.instance.files` not registered (built-in; should always be present) | Check Controller startup logs; the capability registers before stored Modules load |

## Next up

- [Configuration reference](/operations/configuration/) — Controller and Daemon config keys
- [Modules](/concepts/modules/) — install, manage, and write platform Modules
- [HA setup](/operations/ha-setup/) — failure modes that do not need a restore
