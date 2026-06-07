# Recover from Cluster Failure

This runbook is for an **HA controller cluster** where one or more
controllers are dead and the cluster cannot self-heal. Single-controller
installs use [recover-controller.md](recover-controller.md) instead — a
single-controller install has no cluster to recover.

The cluster control plane is an embedded Raft group across N
controllers (see
[`docs/engineering/cluster-join-plan.md`](../engineering/cluster-join-plan.md)).
Raft tolerates `floor((N-1)/2)` simultaneous failures and refuses writes
beyond that. This runbook covers both "we tolerated it" and "we
didn't."

## Decision Tree

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
peers are just sitting in the member list pinging nothing. The fix is
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
wizard on fresh hosts — see
[`docs/engineering/cluster-join-plan.md`](../engineering/cluster-join-plan.md).

## Catastrophic recovery — single-survivor reset

**This is destructive and unsafe.** Use only when quorum is genuinely
lost and the dead peers are unrecoverable. Any writes that were
committed to the lost majority but not yet replicated to the survivor
are gone. The audit log entry of the recovery itself survives.

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

The survivor's Raft state machine snapshot under
`data/raft/<groupId>/sm/` already contains the latest committed cluster
state. We discard the Raft log and config (which were waiting on a
quorum that's never coming), then restart the controller as a brand-new
single-member Raft group. The state machine reads back from the
preserved snapshot — clusterId, cluster CA, join tokens, every Raft
entry that committed before the failure.

```bash
# from the survivor's host

# 1. Make sure the controller is stopped.
sudo systemctl stop prexorcloud-controller

# 2. Locate the Raft group dir.
GROUP_ID="00000000-0000-0000-0000-707265786f72"   # fixed, see ClusterControlService
RAFT_DIR=/etc/prexorcloud/data/raft/${GROUP_ID}

# 3. Preserve the state machine snapshot, blow away log + meta.
sudo mv ${RAFT_DIR}/current ${RAFT_DIR}/.broken-current-$(date +%s)
sudo mv ${RAFT_DIR}/log_inprogress ${RAFT_DIR}/.broken-log-$(date +%s) 2>/dev/null || true
sudo find ${RAFT_DIR} -maxdepth 1 -name 'raft-meta*' -exec mv {} {}.broken-$(date +%s) \;

# 4. Confirm only sm/ remains under the group dir.
sudo ls -la ${RAFT_DIR}

# 5. Restart. The controller boots as a single-member group, the SM
#    reloads from sm/, and the surviving cluster state is intact.
sudo systemctl start prexorcloud-controller
sudo journalctl -u prexorcloud-controller -f
```

### Post-flight

1. Confirm the cluster came back as a single-member cluster:

   ```bash
   prexorctl cluster status
   prexorctl cluster members
   # member count == 1 (just the survivor), same clusterId as before
   ```

2. **Rotate the cluster seed.** Any join token in flight when the
   failure happened was minted under the pre-recovery cluster — drop
   them all:

   ```bash
   prexorctl cluster seed rotate --yes
   ```

3. **Grow the cluster back to HA.** Issue fresh join tokens and run
   the controller install wizard on N-1 new hosts (see
   [`docs/engineering/cluster-join-plan.md`](../engineering/cluster-join-plan.md)).
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
