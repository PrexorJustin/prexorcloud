# Recover from Controller Failure

This runbook is for "the controller process is broken or unreachable."
It is **not** for "Mongo / Valkey is gone" — those have their own
runbooks. If both the controller and a backing store have failed,
recover the store first.

## Decision tree

```
                 Is the controller process running?
                          │
              ┌───────────┴───────────┐
              │                       │
           Yes, but                  No / Crashing
           unhealthy                  │
              │                       │
   /system/ready 503?           journalctl shows:
              │                       │
        ┌─────┴─────┐         ┌───────┴──────────┐
        │           │         │                  │
   coordination   state     OOM /              "Cannot bind"
   .store=unavail .store=  startup loop          │
        │       unavail.    │                    │
        │           │       │             port already used
   recover-redis  recover-  Heap dump        change config /
                  mongo     analysis          kill conflict
                            (§Crash loop)
```

For HA installs, the answer is almost always "fail over to the peer
and investigate the bad controller offline."

## Single-controller failure

### Step 1 — Capture diagnostics before restarting

If the controller is up but misbehaving, grab the data you need before
you change anything:

```bash
sudo journalctl -u prexorcloud-controller --since "30 min ago" > /tmp/ctl.log
sudo systemctl status prexorcloud-controller > /tmp/ctl.status
curl -fsSL http://localhost:8080/api/v1/system/health > /tmp/ctl.health || true
curl -fsSL http://localhost:8080/api/v1/system/ready > /tmp/ctl.ready || true
sudo ls -la /etc/prexorcloud/data/certs/ > /tmp/ctl.certs
```

If the JVM is alive but unresponsive, take a thread + heap dump:

```bash
sudo -u prexorcloud jstack $(systemctl show -p MainPID --value prexorcloud-controller) \
    > /tmp/ctl.threads
sudo -u prexorcloud jcmd $(systemctl show -p MainPID --value prexorcloud-controller) \
    GC.heap_dump /tmp/ctl.hprof
```

### Step 2 — Restart

```bash
sudo systemctl restart prexorcloud-controller
sudo journalctl -u prexorcloud-controller -f
```

Watch for `coordination.store=available` and `state.store=available`.

### Step 3 — If the controller won't start

Check the log for the first ERROR or the line right before exit:

```bash
sudo journalctl -u prexorcloud-controller --since "1 hour ago" | grep -iE 'ERROR|FATAL'
```

Common patterns:

| Log signature                                 | Cause                                | Fix                                                 |
| --------------------------------------------- | ------------------------------------ | --------------------------------------------------- |
| `unknown configuration key`                   | Recent upgrade changed schema        | Edit `controller.yml`; see [`upgrade.md`](upgrade.md). |
| `failed to connect to coordination store`     | Valkey down or wrong URI             | [`recover-redis.md`](recover-redis.md)              |
| `failed to connect to state store`            | Mongo down or wrong URI              | [`recover-mongo.md`](recover-mongo.md)              |
| `port already in use`                         | Another process owns 8080 / 9090     | `ss -tlnp \| grep -E '8080\|9090'`; reconfigure.    |
| `migration failed`                            | Bad upgrade                          | [`upgrade.md`](upgrade.md) §Rollback.               |
| `OutOfMemoryError`                            | Heap exhausted                       | Bump `-Xmx`; analyze hprof.                         |
| `failed to load CA private key`               | `data/certs/` corrupt or missing     | [`restore.md`](restore.md) (filesystem-only restore). |
| `signature verifier requires trust root` (production) | `modules.signing.required=true` but no `trustRoot` | Either configure trust root or — explicitly — set `required=false`. |

## HA failover

PrexorCloud HA is **active-active with lease-scoped work** (see
[`../public/en/concepts/architecture.md`](../public/en/concepts/architecture.md) §"HA model").
Failover is automatic: a peer picks up the dead controller's group
leases once they expire — at most `scheduler.evaluationIntervalSeconds * 2`
(30 s by default).

```bash
# From any host that can reach the survivor:
prexorctl --controller https://<survivor>:8080 status
# Confirms survivor is serving and shows reduced controller count.

# Restart attempts on the dead controller go in this order:
sudo systemctl restart prexorcloud-controller
# If that fails, capture diagnostics (Step 1 above) and treat as
# single-controller failure on this host. The survivor keeps serving
# while you debug.
```

If the dead controller cannot be brought back quickly:

```bash
# Disable so it doesn't auto-restart and flap leases.
sudo systemctl disable prexorcloud-controller

# Optionally: provision a replacement controller on a fresh host (see
# scale.md §Add a Controller). The replacement joins the existing
# Mongo + Valkey and starts taking leases.
```

## Crash loop

If the controller starts and immediately exits in a loop:

```bash
# Disable systemd auto-restart so logs don't churn.
sudo systemctl stop prexorcloud-controller

# Run in foreground for clearer output.
sudo -u prexorcloud /opt/prexorcloud/bin/prexorcloud-controller \
    --config /etc/prexorcloud/config/controller.yml \
    --foreground
```

Read the first error and the last 50 lines before exit.

## Lease stuck

Symptom: `prexorctl status` says "no leader for group X for >60s" or
operations on group X hang.

```bash
# Inspect the keyspace for stuck leases.
redis-cli -u "$REDIS_URI" --scan --pattern 'prexor:v1:lease:*'

# Inspect a specific lease.
redis-cli -u "$REDIS_URI" GET prexor:v1:lease:group:my-group

# As a last resort, force-release. The fencing token in subsequent
# acquisitions will reject any operation that started under the old
# token, so this is safe but may interrupt the in-flight operation.
redis-cli -u "$REDIS_URI" DEL prexor:v1:lease:group:my-group
```

The controller's lease reconciler will reacquire on the next tick.

## Stuck deployment or drain

If a deployment or a node drain hangs after a controller restart,
inspect it and act through the real commands.

A deployment (rolling restart) on a group:

```bash
# List deployment history for the affected group; the STATE column
# shows what's in flight.
prexorctl deploy list <group>

# Inspect one revision.
prexorctl deploy show <group> <rev>

# Pause it while you investigate, then resume — or roll it back.
prexorctl deploy pause <group> <rev>
prexorctl deploy resume <group> <rev>
prexorctl deploy rollback <group> <rev>
```

A node drain:

```bash
# Abort the drain by undraining the node.
prexorctl node undrain <node-id>
```

## After-recovery checklist

- [ ] `/system/ready` returns 200.
- [ ] `prexorctl status` shows expected nodes / groups.
- [ ] `prexorctl crash list --since "<incident start>"` is reviewed.
- [ ] The `audit_log` collection has entries for the recovery actions
  taken (workflow cancellations, lease force-releases, etc.).
- [ ] Captured diagnostics (`/tmp/ctl.*`) saved off-host for
  post-incident review.
- [ ] If applicable, opened a follow-up issue for the root cause.

## Related

- [`recover-redis.md`](recover-redis.md)
- [`recover-mongo.md`](recover-mongo.md)
- [`incident.md`](incident.md) — for paging-relevant signals.
- [`../public/en/concepts/architecture.md`](../public/en/concepts/architecture.md) §"HA model"
