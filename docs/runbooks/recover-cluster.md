# Recover from cluster failure

This runbook is for an **HA controller cluster** where one or more
controllers are dead and the cluster cannot self-heal. Single-controller
installs use [recover-controller.md](recover-controller.md) instead — a
single-controller install has no cluster to recover.

The cluster control plane is an embedded Raft group across N
controllers (see [the cluster model](../public/en/concepts/cluster-model.md)).
Raft tolerates `floor((N-1)/2)` simultaneous failures and refuses writes
beyond that. This runbook covers both: the failure you tolerated, and
the one you didn't.

## Decision tree

```
                  How many controllers are unreachable?
                                │
                  ┌─────────────┴─────────────┐
                  │                           │
            ≤ floor((N-1)/2)            > floor((N-1)/2)
        (quorum preserved)             (quorum lost)
                  │                           │
        prexorctl cluster status       Catastrophic
        still responds                 — no writes possible
                  │                           │
            Force-eject the              Offline single-survivor
            dead peers                   reset (destructive)
                  │                           │
            §Quorum-preserved            §Catastrophic recovery
            recovery                     (last resort)
```

The thresholds:

| Cluster size | Tolerated failures | Catastrophic threshold |
|---|---|---|
| 3 | 1 | 2 |
| 5 | 2 | 3 |
| 7 | 3 | 4 |

## Quorum-preserved recovery

The healthy survivors still have a leader; writes succeed; the dead
peers are still sitting in the member list, pinging nothing. The fix is
to drop them from the member list so they stop counting against quorum
math going forward.

### Step 1 — Confirm the cluster is healthy from a survivor

```bash
# from any surviving controller, against its REST endpoint
prexorctl cluster status
prexorctl cluster members
```

`cluster status` should report a non-empty leader and a member count
that includes the dead peers (they are still in the SM until ejected).

### Step 2 — Verify each peer you intend to eject is actually dead

`cluster eject` is destructive — the ejected peer cannot rejoin without
a fresh join token. Double-check that the peers you're about to remove
really are gone:

```bash
# from a survivor, confirm dead peers stop heartbeating
prexorctl cluster members --json | jq '.[] | {nodeId, lastSeen}'

# from the dead peer's host (if you have access), confirm no controller process
ssh dead-controller-host 'systemctl status prexorcloud-controller'
```

### Step 3 — Force-eject the dead peers

```bash
prexorctl cluster eject <nodeId> --reason "host destroyed 2026-05-31"
```

Repeat for each dead peer. Each eject is a single Raft entry; once
committed every survivor's membership reconciler updates the
Ratis-level group automatically.

### Step 4 — Confirm the cluster has shrunk

```bash
prexorctl cluster members
# member count == survivors, leader still elected, quorum math now based
# on the smaller set
```

If you need to grow the cluster back up to N, issue join tokens via
`prexorctl cluster join-token create` and run the controller setup
wizard on fresh hosts — see [the cluster model](../public/en/concepts/cluster-model.md).

## Catastrophic recovery — single-survivor reset

**This is destructive and unsafe.** Use only when quorum is genuinely
lost and the dead peers are unrecoverable. Any write committed to the
lost majority but not replicated to the survivor is gone. The audit log
entry of the recovery itself survives.

The equivalent operation in other systems:

- etcd: `etcdctl snapshot restore --force-new-cluster`
- Consul: `consul operator raft remove-peer -force`
- Apache Ozone: `ozone admin scm finalize --force`

You are doing the same kind of unilateral takeover.

### Pre-flight

1. **Confirm no quorum.** `prexorctl cluster status` against the
   survivor should fail with a Raft-unavailable error. If it succeeds,
   you have quorum-preserved recovery, not catastrophic — go back to
   the previous section.

2. **Confirm the dead peers are gone for good.** A peer you write off
   here cannot be brought back into this cluster — it would rejoin
   with a stale Raft log and corrupt the cluster's view of history.
   The only way to bring such a host back is to wipe its Raft data dir
   and join it as a fresh controller via a join token.

3. **Take a backup of everything.** Before destroying any state:

   ```bash
   sudo systemctl stop prexorcloud-controller
   sudo tar czf /backup/prexorcloud-pre-recover.tgz \
       /etc/prexorcloud/config/security/cluster/ \
       /etc/prexorcloud/data/raft/
   ```

4. **Capture diagnostics.** The recovery destroys the Raft log; if you
   need to understand what happened later, you'll want a copy of the
   pre-recovery log:

   ```bash
   sudo cp -r /etc/prexorcloud/data/raft \
       /backup/prexorcloud-raft-pre-recover-$(date +%s)
   ```

### The reset

**What survives and what does not.** The reset re-forms a brand-new
single-member Raft group on the survivor. It **preserves the clusterId**
(taken from `cluster.id` in `controller.yml`, which every controller mirrors
after it first joins) so daemons, integrations, and operators keep seeing the
same cluster identity. It **regenerates** the cluster CA, the join-token seed
secret, and the cluster config history — these live only in the Raft state and
cannot be carried across the reset. (Do not be misled by the `sm/` snapshot
directory: re-forming a Raft group formats the storage, which clears `sm/` too —
there is no FS trick that both re-forms a single-member group *and* keeps the old
state machine snapshot. Preserving the clusterId via `controller.yml` is the
supported guarantee.) Daemons are unaffected — they authenticate against the
*daemon* CA, not the cluster (controller-to-controller) CA.

```bash
# from the survivor's host

# 1. Stop the controller.
sudo systemctl stop prexorcloud-controller

# 2. Make sure controller.yml carries the cluster.id you want to keep. After this
#    controller's first join it is already mirrored there; confirm it is non-empty:
sudo grep -A1 '^cluster:' /etc/prexorcloud/config/controller.yml   # cluster: \n   id: "<uuid>"
#    If it is null/empty and you want to retain the old id, set it now to the
#    clusterId you recorded from `prexorctl cluster status` before the failure.

# 3. Locate the Raft group dir and wipe ALL of it (log, meta, AND the sm/ snapshot).
GROUP_ID="00000000-0000-0000-0000-707265786f72"   # fixed, see ClusterControlService
RAFT_DIR=/etc/prexorcloud/data/raft/${GROUP_ID}
sudo mv ${RAFT_DIR} ${RAFT_DIR}.broken-$(date +%s)   # keep a copy for forensics

# 4. Restart. With an empty Raft dataDir and a configured cluster.id, the controller
#    re-bootstraps a fresh single-member group, reuses the clusterId, mints a NEW
#    cluster CA + seed, and writes a `cluster.recovery.unsafe-reset` audit entry.
sudo systemctl start prexorcloud-controller
sudo journalctl -u prexorcloud-controller -f
```

> A future release will add a state-preserving reset (keeping the CA, join
> tokens, and config history). Until then the procedure above is the supported
> catastrophic path; the regenerated material is replaced by the post-flight
> steps (seed rotation + fresh join tokens) anyway.

### Post-flight

1. Confirm the cluster came back as a single-member cluster:

   ```bash
   prexorctl cluster status
   prexorctl cluster members
   # member count == 1 (only the survivor), same clusterId as before
   ```

2. **Rotate the cluster seed.** Any join token in flight when the
   failure happened was minted under the pre-recovery cluster — drop
   them all:

   ```bash
   prexorctl cluster seed rotate --yes
   ```

3. **Grow the cluster back to HA.** Issue fresh join tokens and run
   the controller install wizard on N-1 new hosts (see
   [the cluster model](../public/en/concepts/cluster-model.md)).
   Do NOT reuse the dead peers' hosts unless you have wiped their
   `data/raft/` first — a rejoining stale Raft log would corrupt the
   new cluster's view.

4. **Document the incident.** A catastrophic recovery is an audit
   event. Write up what failed, what was lost, and who authorized the
   reset. The `cluster.recovery.unsafe-reset` audit entry the
   controller writes on first boot after recovery records the fact;
   the why has to come from a human.

## When to ask for help

- Single-survivor recovery succeeds but `prexorctl cluster members`
  shows phantom dead peers — the snapshot replay produced a state
  machine that thinks the dead peers are still members. Eject them
  via `prexorctl cluster eject` as in the quorum-preserved path.
- The controller refuses to start after recovery, citing a clusterId
  mismatch — the survivor's `controller.yml` was edited to a wrong
  `cluster.id`. Restore it from the pre-recovery backup.
- You have NO surviving controller. The cluster is gone; restore the
  v1.0-style `cluster_meta` from Mongo if your install pre-dates the
  embedded Raft control plane, otherwise restore from the most recent
  Mongo backup and accept the data loss between then and the failure.
