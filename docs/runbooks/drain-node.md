# Drain a node

Draining moves work off a Daemon node so you can upgrade, reboot, decommission,
or service the hardware without dropping player sessions. Drain is reversible
(`undrain`) and runs server-side — you don't migrate Instances by hand.

## When to drain

| Situation                              | Drain?                                       |
| -------------------------------------- | -------------------------------------------- |
| Daemon binary upgrade                  | Yes, with `shutdown=false` so the host stays up. |
| Host reboot for kernel update          | Yes, with `shutdown=true`.                   |
| Hardware fault (e.g. disk dying)       | Yes, with `shutdown=true` and a short timeout. |
| Removing the node permanently          | Yes, then delete the node record.            |
| Brief OS package update (no restart)   | Cordon is enough — see below.                |

## Cordon vs. drain

- **Cordon** stops new placements and leaves existing Instances running.
  Reversible. Use it to depool capacity without disturbing current sessions
  (a quick troubleshoot, for example).
- **Drain** is cordon plus moving running Instances off the node (or stopping
  them). Use it for any operation that interrupts the Daemon process.

`prexorctl` covers drain and undrain. Cordon, uncordon, and the drain options
(`shutdown`, `timeout`, `kickMessage`) are REST-only today — call the controller
directly for those.

```bash
# Drain / undrain via the CLI. Drain defaults to shutdown=true, a 60s timeout,
# and the kick message "This server is shutting down for maintenance."
prexorctl node drain <node-id>
prexorctl node undrain <node-id>

# Cordon / uncordon — REST only.
curl -X POST https://controller:8080/api/v1/nodes/<node-id>/cordon \
  -H "Authorization: Bearer $TOKEN"
curl -X POST https://controller:8080/api/v1/nodes/<node-id>/uncordon \
  -H "Authorization: Bearer $TOKEN"
```

The REST surface:

| Method | Path                                  | Permission         |
| ------ | ------------------------------------- | ------------------ |
| POST   | `/api/v1/nodes/{id}/cordon`           | `nodes.drain`      |
| POST   | `/api/v1/nodes/{id}/uncordon`         | `nodes.drain`      |
| POST   | `/api/v1/nodes/{id}/drain`            | `nodes.drain`      |
| POST   | `/api/v1/nodes/{id}/undrain`          | `nodes.drain`      |
| DELETE | `/api/v1/nodes/{id}`                  | `nodes.drain`      |

`drain` takes three optional query params: `shutdown` (default `true`),
`timeout` in seconds (default `60`), and `kickMessage`.

## Drain procedure

### 1. Pre-flight

Confirm the cluster has capacity to absorb the node's Instances.

```bash
prexorctl node info <node-id>
# Note the Instances running on this node and the Groups they belong to.

prexorctl node list
# Confirm other nodes have free capacity, and at least one matches each
# affected Group's placement constraints.
```

If you drain the only node matching a Group's `nodeSelector`, those Instances
won't reschedule — they stop and stay stopped until you uncordon or add
capacity.

### 2. Start the drain

For an upgrade where the host stays up, keep the Daemon process alive with
`shutdown=false`:

```bash
curl -X POST "https://controller:8080/api/v1/nodes/<node-id>/drain?shutdown=false&timeout=600&kickMessage=Maintenance" \
  -H "Authorization: Bearer $TOKEN"
```

For a decommission or hardware service, let the Daemon shut down once the last
Instance is gone — this is the CLI default:

```bash
prexorctl node drain <node-id>
```

What happens server-side:

1. The node is cordoned — no new placements.
2. Player-occupied Instances move to `DRAINING`. Their players are queued for
   transfer to another Instance in the same Group, or to the Group's
   `fallbackGroup` when one is set. Empty Instances stop immediately.
3. As players leave, drained Instances stop.
4. When the `timeout` elapses, the controller kicks any remaining players with
   the kick message.
5. Once the node is fully drained: with `shutdown=true` the Daemon is told to
   shut down; otherwise the node is left `CORDONED`.

### 3. Watch progress

```bash
watch -n 2 prexorctl node info <node-id>
```

Look for:

- the instance count dropping toward zero,
- the node status moving to `DRAINING`, then `CORDONED` once it's empty (or the
  node dropping off entirely when `shutdown=true`).

The dashboard shows the same on the node card: a drain progress bar and an event
log for each Instance stop or transfer.

### 4. After drain completes

If the node is up and you only upgraded the Daemon:

```bash
sudo systemctl stop prexorcloud-daemon
# upgrade...
sudo systemctl start prexorcloud-daemon

# Re-enable scheduling.
prexorctl node undrain <node-id>
```

If you're decommissioning, stop the Daemon, then delete the node record from a
controller:

```bash
curl -X DELETE https://controller:8080/api/v1/nodes/<node-id> \
  -H "Authorization: Bearer $TOKEN"
```

Delete refuses a still-connected node with `409 Cannot delete a connected node`,
so stop the Daemon first. Delete removes the node record from Mongo; it does not
revoke the node's mTLS certificate. To revoke the cert, use the revoke-cert
endpoint — see [`rotate-secrets.md`](rotate-secrets.md).

## Lease behavior during drain

Drain is a persisted workflow intent (the `workflow_drains` collection in
Mongo). If the controller that started the drain dies mid-operation, a peer
picks up the drain lease (`node-drain:<node-id>`) and resumes — covered by the
harness test
`RecoveryTest.standbyControllerFailoverRecoversPersistedNodeDrainAfterDaemonReconnect`.

## Common failures

| Symptom                                                    | Likely cause                                | Fix                                       |
| ---------------------------------------------------------- | ------------------------------------------- | ----------------------------------------- |
| Drain hangs with the instance count stuck at N             | An Instance ignored its stop signal         | Force-stop it: `prexorctl instance stop <id> --force`. |
| Instances don't reschedule onto other nodes                | No node matches the placement constraints   | Relax constraints, add nodes, or accept that the Group runs reduced. |
| Drain timeout fires; players kicked mid-session            | `timeout` too short for player saves        | Raise `timeout`; check the Group's `shutdownGraceSeconds`. |
| Drain done but the Daemon process is still up              | `shutdown=false` was set                    | If you wanted a shutdown, run `systemctl stop prexorcloud-daemon`. |
| `undrain` rejected with `node not draining`                | Drain already completed                     | Run `uncordon` instead. |
| Drain stuck after a controller restart                     | Workflow intent not resumed                 | Confirm a controller holds the drain lease and the shared coordination store (Valkey) is reachable. |

## Related

- [`upgrade.md`](upgrade.md)
- [`scale.md`](scale.md)
- [`recover-controller.md`](recover-controller.md) — drain semantics under failover.
- [`../public/en/concepts/architecture.md`](../public/en/concepts/architecture.md) — the HA model and topology.
