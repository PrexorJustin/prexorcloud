# Backup

A PrexorCloud backup captures four things:

| Tier                     | Source                                  | Loss impact |
| ------------------------ | --------------------------------------- | ----------- |
| **Durable platform**     | MongoDB                                 | Catastrophic — every Group, deployment, audit entry, Module record. |
| **Coordination**         | Valkey / Redis                          | Tolerable — login lockouts and SSE replay reset, JWT revocations forgiven, in-flight workflows resume from Mongo intent. |
| **Filesystem**           | `/etc/prexorcloud/` and Module storage  | Catastrophic for config and CA; recoverable for Module storage. |
| **Per-Module storage**   | Mongo (`module_storage_*` collections) and Valkey (`prexor:v1:module:<id>:*`) | Module-defined. |

> **Recommended path:** `prexorctl backup create|list|verify|prune`. The
> CLI wraps the same `BackupCreator` and `BackupCatalog` the Controller
> uses internally and writes the on-disk tarball that is the source of
> truth. The manual procedure below is for emergencies when the CLI is
> unavailable.

## What to back up

### MongoDB

Full database dump. Collections of interest:

- `users`, `roles`, `audit_log`
- `groups`, `templates`, `deployments`, `composition_plans`, `modules`,
  `module_storage_*`
- `workflow_intent`, `crash_records`, `recovery_metadata`
- `backups` (manifests of prior backups)

`mongodump` covers the whole database. Don't try to be selective.

### Valkey / Redis

A point-in-time `BGSAVE` snapshot of the keyspace. Most keys live under
the `prexor:v1:` prefix:

- `prexor:v1:lease:*` — leases (regenerate naturally)
- `prexor:v1:jwt:revoked:*` — JWT revocations
- `prexor:v1:rate:*` — rate-limit windows
- `prexor:v1:lockout:*` — login attempts
- `prexor:v1:sse:*` — replay buffers and tickets
- `prexor:v1:module:<id>:*` — per-module ephemeral storage

Backing up Valkey is **optional** for disaster recovery — if Valkey is
empty after restore, the Controller rebuilds soft state. Back it up
anyway if any of your Modules use it as their primary store (e.g. a
session Module).

### Filesystem

Back up the Controller install root, default `/etc/prexorcloud/`:

- `config/controller.yml` — config (contains JWT secret and DB URIs).
- `data/certs/` — Controller CA private key and any cached Daemon
  certificates. **This is the irreplaceable secret material.** If lost,
  every Daemon must rejoin from scratch.
- `data/modules/` — installed Module jars and per-Module data
  directories.

Daemon hosts also hold state worth keeping:

- `/etc/prexorcloud/config/daemon.yml`
- `/etc/prexorcloud/data/certs/` — per-Daemon mTLS material.

### Per-Module storage

The Mongo and Valkey backups capture this automatically. The filesystem
backup captures Modules that write to disk under their own scoped
directory (`data/modules/<id>/`).

## Manual backup procedure

Run on a Controller host. Create a dated backup directory:

```bash
BK=/var/backups/prexorcloud/$(date -u +%Y-%m-%dT%H%M%SZ)
sudo mkdir -p "$BK"
```

### 1. Snapshot Mongo

```bash
MONGO_URI=$(sudo grep -E '^\s*uri:' /etc/prexorcloud/config/controller.yml \
    | head -1 | awk '{print $2}' | tr -d '"')

sudo mongodump --uri "$MONGO_URI" --gzip --out "$BK/mongo"
```

### 2. Snapshot Valkey

```bash
REDIS_URI=$(sudo grep -A1 '^redis:' /etc/prexorcloud/config/controller.yml \
    | grep uri | awk '{print $2}' | tr -d '"')

# Trigger background save and wait until SAVE timestamp advances.
redis-cli -u "$REDIS_URI" BGSAVE
# Poll until LASTSAVE moves; usually <5s on a fresh box.
redis-cli -u "$REDIS_URI" --no-raw LASTSAVE

# Copy the dump.rdb file from the Valkey data directory.
sudo cp /var/lib/valkey/dump.rdb "$BK/valkey-dump.rdb"
```

If Valkey is on a different host, copy from there. If you can't reach
the data directory, fall back to a scoped `--scan` + JSONL dump of every
key matching `prexor:v1:*` (slower; this is what `prexorctl` uses internally).

### 3. Snapshot filesystem

```bash
sudo systemctl stop prexorcloud-controller   # or: enter maintenance mode
sudo tar -czf "$BK/etc-prexorcloud.tar.gz" \
    -C /etc prexorcloud
sudo systemctl start prexorcloud-controller
```

For HA installs, stop only one Controller, or rely on lease handoff and
skip the stop:

```bash
sudo tar -czf "$BK/etc-prexorcloud.tar.gz" \
    --exclude='*/logs/*' \
    -C /etc prexorcloud
```

(Skipping the stop risks a torn config write if you happen to be
editing during the snapshot. The recommended path is a brief stop.)

### 4. Write a manifest

```bash
sudo tee "$BK/manifest.json" <<JSON
{
  "createdAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "controllerVersion": "$(prexorctl version --json | jq -r .controller_version)",
  "host": "$(hostname -f)",
  "scope": ["mongo", "valkey", "filesystem"],
  "files": {
    "mongo":     "mongo/",
    "valkey":    "valkey-dump.rdb",
    "filesystem": "etc-prexorcloud.tar.gz"
  }
}
JSON
```

The manifest is what [`restore.md`](restore.md) expects.

### 5. Encrypt and ship off-host

A backup that lives only on the Controller host is one disk failure
away from useless. Pipe the directory through `age` or `gpg` and ship
to S3 / borg / restic / your preferred off-host store:

```bash
sudo tar -cf - -C /var/backups/prexorcloud \
    "$(basename "$BK")" \
  | age -r age1xxxxxxxxxxxxxxxxxxxx... \
        > "$(basename "$BK").tar.age"
aws s3 cp "$(basename "$BK").tar.age" \
    s3://your-bucket/prexorcloud/
```

## Schedule

Recommended baseline:

| Frequency | Scope                | Retention |
| --------- | -------------------- | --------- |
| Hourly    | Mongo only           | 24 hours  |
| Daily     | Full (Mongo + Valkey + filesystem) | 14 days |
| Weekly    | Full + off-host ship | 90 days   |
| Pre-upgrade | Full                | Until next stable upgrade window |

Adjust to your operator profile. The audit log lives in Mongo, so the
hourly cadence keeps audit-trail loss bounded.

## Verification

A backup you've never restored is not a backup. Once a quarter:

1. Spin up a throwaway host or container.
2. Install the same Controller version.
3. Run [`restore.md`](restore.md) end-to-end.
4. Confirm `prexorctl status` shows the expected Groups and nodes, and
   `prexorctl module list` the expected Modules.
5. Discard the host.

`prexorctl backup verify` validates checksums and runs a structural
restore-dry-run automatically.

## What a backup does not capture

- The state of running MC Instances. Per-world / per-player game data is
  the operator's responsibility (use the world's own backup mechanism;
  most server jars include hot snapshots).
- DNS, load-balancer, and reverse-proxy config in front of the
  Controller.
- Operator dashboard browser sessions (cookies; transient anyway).

## Related

- [`restore.md`](restore.md) — restore procedure.
- [`recover-controller.md`](recover-controller.md) — when to restore vs.
  fail over.
- [storage schema](../public/en/internals/storage-schema.md) — what's stored where.
