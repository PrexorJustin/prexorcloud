# Upgrade — v1.0 → v1.1 (embedded Raft cluster control plane)

v1.1 replaces the v1.0 Mongo-based cluster join story (`cluster_meta`
collection + `/api/v1/admin/cluster/join-template`) with an embedded
Apache Ratis Raft group across the Controllers. The cluster's identity,
shared config, members, join tokens, and coarse leases now live in the
Raft state machine. Mongo stays for business data; Redis stays for
ephemeral fan-out.

This is a one-time, one-way upgrade. Once the first v1.1 Controller
commits its initial Raft config entry, the cluster is on v1.1.

For routine in-place upgrades within v1.x, use
[`upgrade.md`](upgrade.md). For disaster recovery after a v1.1 cluster
has lost quorum, use [`recover-cluster.md`](recover-cluster.md).

## Before you start

1. **Read the v1.1 release notes** end-to-end. The release notes call
   out the new required `controller.yml` keys (notably `raft.host` /
   `raft.port`, default `9190`) and the new `config/security/cluster/`
   directory.
2. **Take a full backup.** See [`backup.md`](backup.md). The migration
   is idempotent and rollback-safe up to the first Raft commit (see
   [Points of no return](#points-of-no-return)) — but if anything else
   goes sideways during the upgrade window, you want a backup.
3. **Decide your target cluster size now.**
   - Single Controller: works as a 1-node Raft group; no HA.
   - HA: 3 nodes tolerate one failure; 5 nodes tolerate two. Even
     counts give you no extra fault tolerance and one extra vote to
     coordinate — don't use them.
4. **Confirm the v1.0 install is healthy** before touching it:
   ```bash
   prexorctl status
   curl -fs http://localhost:8080/api/v1/system/ready
   ```
5. **Plan a maintenance window.** The first-Controller restart in
   Step 1 below is the only required downtime for single-Controller
   installs (~10–60s). HA installs see the same downtime on the first
   node, then stay live while the remaining Controllers re-join.

## Architecture change at a glance

| Concern                | v1.0                                                  | v1.1                                                                 |
| ---------------------- | ----------------------------------------------------- | -------------------------------------------------------------------- |
| Cluster identity       | `cluster_meta` Mongo collection                       | Raft state machine (`ClusterMeta`)                                   |
| Shared config          | Operator copied `controller.yml` sections by hand     | Versioned config in Raft; `PATCH /api/v1/cluster/config`             |
| Join handshake         | `GET /api/v1/admin/cluster/join-template` + paste     | gRPC `ClusterMembership.RequestJoin` + signed CSR                    |
| Auth between nodes     | Shared admin token                                    | Cluster-CA-pinned mTLS over Raft + gRPC                              |
| New permissions        | `CLUSTER_JOIN`                                        | `CLUSTER_VIEW`, `CLUSTER_CONFIG_WRITE`, `CLUSTER_MANAGE`             |
| Leader-elected work    | Per-feature Redis locks                               | Coarse leases via `ClusterLeaseManager` (Raft-backed)                |
| New on-disk state      | —                                                     | `data/raft/` + `config/security/cluster/`                            |

## Step 1 — Upgrade the first Controller

Pick one Controller as the seed. In HA installs this is the Controller
whose `controller.yml` is the source of truth for cluster-shared config;
in single-Controller installs it is the only Controller.

```bash
# 1. Stop the controller.
sudo systemctl stop prexorcloud-controller

# 2. Take a backup (you already did this in pre-flight; re-do if any
#    writes happened since).
prexorctl backup create

# 3. Add the Raft bind to controller.yml. Defaults shown:
#    raft:
#      host: 0.0.0.0
#      port: 9190
#      dataDir: /etc/prexorcloud/data/raft
#    Pick a host that other controllers can reach. The port only needs
#    to be reachable peer-to-peer between controllers.
sudo $EDITOR /etc/prexorcloud/controller.yml

# 4. Replace the JAR / package.
sudo apt-get install --only-upgrade prexorcloud-controller

# 5. Start.
sudo systemctl start prexorcloud-controller

# 6. Watch the log. You're looking for the migration line.
sudo journalctl -u prexorcloud-controller -f
```

### What you should see in the log

In order, on first start:

```
Stamped Day-0 cluster CA + self leaf cert; persisted to <…>/config/security/cluster (CA fingerprint=…)
Stamped fresh cluster.id=<uuid> into Raft state (yamlSource=yes|no)
Stamped cluster CA (fingerprint=…) into Raft state
Migrated cluster-shared config from controller.yml into Raft as version 1 (N top-level keys)
```

The fourth line is the v1.0 → v1.1 migration itself. `N` is the number
of top-level keys projected by `ClusterJoinTemplate.buildSharedMap`:
`runtime`, `http.cors`, `network.allowedSubnets`, `database`, `redis`,
`backup`, `security` (minus `initialAdminPassword`), `metrics`,
`scheduler`, `heartbeat`, `crashes`, `modules`, `maintenance`, `share`.
Node-local sections (`uuid`, `grpc`, `logging`, `dashboard`, `http.host`,
`http.port`, `raft`) are intentionally omitted.

If `controller.yml` had no cluster-shared sections (unusual for a real
v1.0 install) you'll instead see:

```
No cluster-shared config detected in controller.yml — leaving cluster_config empty until the wizard or first PATCH writes one.
```

### Validate

```bash
prexorctl cluster status      # member count = 1, leader = this node
prexorctl cluster members     # one entry, state READY
curl -fs http://localhost:8080/api/v1/system/ready
```

If `cluster.id` was already set in your v1.0 `controller.yml` the
migration re-uses it. If not, v1.1 mints a fresh UUID and writes a
mirror back into `controller.yml` on first successful boot. Either way,
on subsequent restarts the Controller refuses to start when the
`controller.yml` mirror disagrees with the Raft state — the guard exists
specifically so a misconfigured data dir fails loud rather than
silently. The exact error names both values:

```
Configured cluster.id=<yaml> but Raft state holds cluster.id=<raft>.
Either restore the original Raft data dir, or remove cluster.id from
controller.yml to adopt this Raft state's existing id.
```

## Step 2 — Adopt additional Controllers (HA only)

For a single-Controller install, you are done; skip to
[Step 3](#step-3--post-upgrade-cleanup).

In v1.0 every Controller carried its own copy of the cluster-shared
config and you joined by hand-pasting the join template. In v1.1 a new
Controller boots into "join mode" with a single-use token written into
its `config/security/pending-join-token` file; bootstrap drives the rest
of the handshake automatically.

### From the seed Controller — issue a join token

```bash
prexorctl cluster join-token create \
    --ttl-seconds 86400 \
    --label "controller-2" \
    --join-addr "<seed-controller-grpc-host>:<grpc-port>"
# → prexor-jt:v1:<base64url(payload)>.<base64url(hmac)>
```

The token embeds `joinAddrs`, `clusterId`, a unique `jti`, and an
expiry. Its HMAC binds it to the cluster seed secret, which is held in
Raft state — that is the authentication for the gRPC join handshake.
Treat the token like any short-lived secret; it cannot be derived from
anything else and revocation
(`prexorctl cluster join-token revoke <jti>`) is the only way to defang
it before its TTL.

### On each new Controller — adopt v1.1

For each additional node:

1. **Stop the old v1.0 Controller** if it is running:
   ```bash
   sudo systemctl stop prexorcloud-controller
   ```
2. **Wipe v1.0 cluster state** on this node. The v1.0 `cluster_meta`
   collection only mattered in Mongo on the seed; on a per-node basis
   the old `controller.yml` cluster-shared sections become advisory
   only — they are *not* read on a v1.1 join (the joining node gets its
   shared config from the leader via Raft snapshot). You can leave them
   in `controller.yml` for now; clean up in [Step 3](#step-3--post-upgrade-cleanup).

   ⚠️ **Do not copy the seed Controller's `config/security/cluster/`
   or `data/raft/` directories.** Each node mints / receives its own
   TLS material and its own Raft storage. Copying these is the surest
   way to two Controllers fighting over one Raft identity.
3. **Install the v1.1 package.**
4. **Run the wizard in `controller-join` mode**, or do it by hand:
   ```bash
   sudo prexorctl-installer controller-join \
       --token "prexor-jt:v1:…"
   ```
   Equivalent by hand: write the token to the pending file and start.
   ```bash
   sudo install -m 0600 -o prexorcloud -g prexorcloud /dev/null \
       /etc/prexorcloud/config/security/pending-join-token
   sudo bash -c 'echo -n "prexor-jt:v1:…" > /etc/prexorcloud/config/security/pending-join-token'
   sudo systemctl start prexorcloud-controller
   ```
5. **Watch the log.** Expect:
   ```
   Found pending join token at .../pending-join-token — joining cluster as <node-uuid> (raft=…, rest=…, grpc=…)
   Cluster join complete — deleted .../pending-join-token
   ```
6. **Validate from any Controller:**
   ```bash
   prexorctl cluster members      # new node in READY
   prexorctl cluster status       # member count incremented; quorum healthy
   ```

The bootstrap is restart-safe: if the join handshake fails partway
through, the pending token file stays in place and the next restart
retries with the same token. Revoke the token if you want to abort.

Repeat for each remaining Controller. The v1.1 plan still applies:
size for an odd count (3 or 5).

## Step 3 — Post-upgrade cleanup

Once every Controller is on v1.1 and `prexorctl cluster members` lists
the expected set:

1. **Trim the cluster-shared sections from `controller.yml` on every
   node.** The Raft state machine is authoritative; the fields are no
   longer read at boot (except `cluster.id`, which stays as the
   mismatch-guard mirror). Leaving them is harmless but invites drift.
   Keep:
   - `uuid`
   - `grpc.*`, `http.host`, `http.port`
   - `raft.*`
   - `logging.*`
   - `cluster.id` (the mirror)
   Drop everything else and let the Raft `cluster_config` version drive
   live behaviour.
2. **Drop the v1.0 admin permission.** `CLUSTER_JOIN` is gone; the
   builtin admin role gets `CLUSTER_MANAGE`,
   `CLUSTER_CONFIG_WRITE`, and `CLUSTER_VIEW` automatically on first
   v1.1 boot via the `EXCLUDED_FROM_DEFAULT_ADMIN` mechanism. Re-bind
   any custom roles that referenced `CLUSTER_JOIN` to the new ones;
   list custom roles with `prexorctl user role list`.
3. **Confirm the v1.0 join endpoint is gone:**
   ```bash
   curl -i http://localhost:8080/api/v1/admin/cluster/join-template
   # → 404. If this returns anything else the upgrade did not complete.
   ```
4. **Validate one full live-config patch round-trip** to confirm
   replication. Cluster config is managed over REST — there is no
   `prexorctl cluster config` subcommand. Read the active version,
   propose a patch against it, then confirm the new version landed.
   `$TOKEN` is an admin bearer token with `cluster.config.write`.
   ```bash
   PARENT=$(curl -fs http://localhost:8080/api/v1/cluster/config \
     -H "Authorization: Bearer $TOKEN" | jq .activeVersion)

   curl -fs -X POST http://localhost:8080/api/v1/cluster/config \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d "{\"parentVersion\": $PARENT,
          \"patch\": {\"maintenance\": {\"message\": \"post-upgrade smoke\"}},
          \"reason\": \"post-upgrade smoke\"}"

   # Confirm on every controller that activeVersion advanced and matches.
   curl -fs http://localhost:8080/api/v1/cluster/config/versions \
     -H "Authorization: Bearer $TOKEN" | jq '.activeVersion, .versions[0]'
   ```

## Points of no return

| Moment                                                            | Rollback                                                                                                                                |
| ----------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| v1.1 binary installed but not yet started                         | Trivial: reinstall the v1.0 package.                                                                                                    |
| v1.1 started, log shows "Stamped fresh cluster.id"                | Possible: stop, delete `data/raft/` and `config/security/cluster/`, reinstall v1.0. You restart from the Mongo state at that moment.    |
| v1.1 logged "Migrated cluster-shared config … as version 1"       | **Committed.** Raft state is now authoritative for cluster identity and shared config. To roll back you must restore from backup and accept any business writes since the upgrade started are lost. |
| Additional Controller's `pending-join-token` written but unjoined | Trivial: delete the file, stop the new Controller, revoke the token from a healthy v1.1 node.                                           |
| Additional Controller's join completed, token file deleted        | Member is live. To remove it, use `prexorctl cluster leave` on that node, or `prexorctl cluster eject <node-id>` from another.          |

The migration itself is idempotent on restart: the
`controlPlane.getActiveConfigVersion() > 0` check in
`ClusterControlService.runV10MigrationIfNeeded` short-circuits on every
subsequent boot. Crashing mid-migration is safe — restart and the
state-machine apply completes the partially-proposed entry the same
way any other entry would resume.

## TLS material — what v1.0 material survives

None of the v1.0 Controller-to-Controller TLS material is reused. v1.1
mints a fresh cluster CA in-memory on Day-0 of the seed Controller and
persists it both into the local `config/security/cluster/` directory
(for Raft transport on this node) and into the Raft state machine (for
future joiners to fetch via snapshot). Each joining Controller submits
a CSR during the gRPC handshake, the seed signs it with that CA, and
the joiner persists the signed leaf cert + private key under its own
`config/security/cluster/`.

The existing Controller↔Daemon mTLS material (separate keystore, signed
by a separate Daemon CA) is unaffected by this upgrade.

## Validation checklist

After every Controller is on v1.1:

- [ ] `prexorctl cluster status` lists every Controller, leader is
      stable for at least 30s.
- [ ] `prexorctl cluster members` shows each node in `READY`, with
      matching `clusterId`.
- [ ] `GET /api/v1/cluster/config` returns an `activeVersion` ≥ 1.
- [ ] `curl -i http://<any-controller>:8080/api/v1/admin/cluster/join-template`
      returns 404.
- [ ] `prexorctl status` is green; Daemons reconnected.
- [ ] One `POST /api/v1/cluster/config` patch replicates to every
      Controller's `GET /api/v1/cluster/config` within a second or two.
- [ ] `journalctl -u prexorcloud-controller --since "10 min ago"` has
      no `ERROR` lines.

## Common failures

| Symptom                                                              | Likely cause                                                                       | Fix                                                                                                                              |
| -------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| First boot fails with `Configured cluster.id=… but Raft state holds…` | `controller.yml` `cluster.id` doesn't match the Raft data dir.                     | Either restore the correct Raft data dir, or remove `cluster.id` from `controller.yml` to adopt the data dir's id.               |
| `RequestJoin` fails with `unknown token` on a joiner                 | Token was revoked, expired, or issued by a different cluster seed.                  | Issue a fresh token from a healthy Controller and rewrite the joiner's `pending-join-token`.                                     |
| Joiner stuck in "Found pending join token" loop                      | The token's `joinAddrs` is unreachable or points at the wrong port. **`joinAddrs` must be the seed's gRPC port (`9090`), not the Raft port (`9190`)** — the joiner has no cluster cert yet, so dialing `9190` fails the mTLS handshake (`CERTIFICATE_REQUIRED`). | Re-issue the token with `--join-addr <seed>:9090`; confirm the seed's gRPC `9090` (token redemption) and, post-join, Raft `9190` (peer replication) are reachable; fix firewall; restart joiner — the token is reusable until it expires or is revoked. |
| Old admin role lost the cluster page                                 | Custom role was bound to `CLUSTER_JOIN`, which no longer exists.                   | `prexorctl user role update <role> --permissions …,cluster.view,cluster.config.write,cluster.manage` — the flag replaces the full set, so include the role's existing permissions. |
| `cluster_config` is empty after seed upgrade                         | v1.0 `controller.yml` had no cluster-shared sections (atypical).                   | Seed it with `POST /api/v1/cluster/config` and body `{"parentVersion": 0, "patch": {…}, "reason": "seed"}`.                       |

## Related

- [`upgrade.md`](upgrade.md) — routine in-place upgrades (v1.x → v1.y).
- [`recover-cluster.md`](recover-cluster.md) — disaster recovery on a
  v1.1 cluster that has lost quorum.
- [`backup.md`](backup.md), [`restore.md`](restore.md) — pre-flight and
  rollback.
- [Cluster model](../public/en/concepts/cluster-model.md) — the
  embedded-Raft control plane this upgrade implements.
