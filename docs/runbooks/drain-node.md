# Drain a Node

Draining moves work off a daemon node so it can be upgraded, rebooted,
decommissioned, or hardware-serviced without dropping player sessions.
Draining is reversible (`undrain`) and runs entirely server-side — no
manual instance migration required.

## When to Drain

| Situation                              | Drain?                       |
| -------------------------------------- | ---------------------------- |
| Daemon binary upgrade                  | Yes, with `--shutdown=false`. |
| Host reboot for kernel update          | Yes, with `--shutdown=true`. |
| Hardware fault (e.g. disk dying)       | Yes, with `--shutdown=true`, short timeout. |
| Removing the node permanently          | Yes, then `node delete`.     |
| Brief OS package update (no restart)   | `cordon` is enough — see below. |

## Cordon vs. Drain

- **Cordon** — stops new placements, leaves existing instances running.
  Reversible. Use when you want to depool *capacity* without disturbing
  current sessions (e.g. for a quick troubleshoot).
- **Drain** — implies cordon plus moves running instances off (or stops
  them). Use for any operation that interrupts the daemon process.

Commands:

```bash
prexorctl node cordon <node-id>
prexorctl node uncordon <node-id>
prexorctl node drain <node-id> [--shutdown=true|false] [--timeout 10m] [--kick-message "..."]
prexorctl node undrain <node-id>
```

Both also exposed via REST:

| Method | Path                                  | Permission         |
| ------ | ------------------------------------- | ------------------ |
| POST   | `/api/v1/nodes/{id}/cordon`           | `nodes.drain`      |
| POST   | `/api/v1/nodes/{id}/uncordon`         | `nodes.drain`      |
| POST   | `/api/v1/nodes/{id}/drain`            | `nodes.drain`      |
| POST   | `/api/v1/nodes/{id}/undrain`          | `nodes.drain`      |

## Drain Procedure

### 1. Pre-flight

Confirm the cluster has capacity to absorb the node's instances.

```bash
prexorctl node info <node-id>
# Note: instances running on this node, and groups they belong to.

prexorctl node list
# Confirm other nodes have free capacity, and at least one matches each
# affected group's placement constraints.
```

If you're draining the only node matching a group's `nodeSelector`,
expect those instances to **not** be rescheduled — they'll stop and
stay stopped until you uncordon or add capacity.

### 2. Start the Drain

For an upgrade where the host stays up:

```bash
prexorctl node drain <node-id> \
    --shutdown=false \
    --timeout 10m \
    --kick-message "Maintenance — please reconnect in a few minutes."
```

For decommission / hardware service:

```bash
prexorctl node drain <node-id> \
    --shutdown=true \
    --timeout 10m
```

What happens server-side:

1. The node is cordoned (no new placements).
2. The drain reconciler issues stop intents for each running instance,
   in groups, respecting the group's `drainPolicy` (e.g. transfer
   players to a fallback group first when the group declares it).
3. Velocity / Bungee plugins are notified via the proxy event channel
   so player kicks include the supplied `--kick-message`.
4. The reconciler waits up to `--timeout` for instances to stop
   gracefully before force-stopping.
5. If `--shutdown=true`, the daemon exits cleanly when the last
   instance is gone.

### 3. Watch Progress

```bash
watch -n 2 prexorctl node info <node-id>
```

Look for:

- `runningInstances` decreasing.
- `state: DRAINING` then `DRAINED`.

Or via dashboard: the node card shows a drain progress bar and an
event log for each instance stop / transfer.

### 4. After Drain Completes

If the node is up and you only wanted to upgrade the daemon:

```bash
sudo systemctl stop prexorcloud-daemon
# upgrade...
sudo systemctl start prexorcloud-daemon

# Re-enable scheduling.
prexorctl node undrain <node-id>
```

If you're decommissioning:

```bash
# Daemon should be stopped. Then on the controller:
prexorctl node delete <node-id>
```

`node delete` removes the node record from Mongo and revokes its mTLS
certificate (see [`rotate-secrets.md`](rotate-secrets.md)).

## Lease Behavior During Drain

Drain is a persisted workflow intent (`workflow_intent` collection in
Mongo). If the controller that started the drain dies mid-operation,
its peer picks up the lease and resumes the drain — see the harness
test
`RecoveryTest.standbyPromotionResumesPlacementAfterMidPlacementFailover`.

## Common Failures

| Symptom                                                    | Likely cause                                | Fix                                       |
| ---------------------------------------------------------- | ------------------------------------------- | ----------------------------------------- |
| Drain hangs at `runningInstances=N`                        | Instance ignored stop signal                 | Force-stop: `prexorctl instance stop <id> --force`. |
| Instances don't reschedule onto other nodes                | No node matches placement constraints       | Relax constraints; add nodes; accept that the group runs reduced. |
| Drain timeout fires; instances killed mid-session          | `--timeout` too short for player saves      | Increase `--timeout`; check group's `gracefulStopSeconds`. |
| Drain marked done but daemon process still up              | `--shutdown=false` was set                  | If you wanted shutdown, run `systemctl stop prexorcloud-daemon` manually. |
| `undrain` rejected with `node not draining`                 | Drain already completed                     | Just `uncordon`. |
| Drain stuck after controller restart                       | Workflow intent not picked up                | Confirm a controller holds the relevant lease; check `coordination.store=available`. |

## Related

- [`upgrade.md`](upgrade.md)
- [`scale.md`](scale.md)
- [`recover-controller.md`](recover-controller.md) — drain semantics under failover.
- [`../architecture.md`](../architecture.md) §"HA model"
