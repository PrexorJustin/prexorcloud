# Scale

Two axes: cluster scale (more daemon nodes / more controllers) and group
scale (more instances of a group). This runbook covers both.

## Scaling the Cluster

### Add a Daemon Node

```bash
# On any controller host:
prexorctl token create --description "node-N" --ttl 1h
# -> Token: prxn_xxxxxxxxxxxxxxxx

# On the new daemon host:
sudo prexorctl setup --role daemon \
    --controller-grpc <controller-host>:9090 \
    --join-token prxn_xxxxxxxxxxxxxxxx
```

The setup flow registers the node, exchanges the token for a per-daemon
mTLS certificate, installs the systemd unit, and starts the daemon.
Within ~10 seconds:

```bash
prexorctl node list
```

The new node should show `READY` with whatever labels its
`daemon.yml: labels` declares.

The scheduler picks up the new node automatically. New instance
placements consider it on the next `scheduler.evaluationInterval`
tick (default 5s).

### Remove a Daemon Node

```bash
prexorctl node drain <node-id> --shutdown=true --timeout 10m
# Wait until the node reports zero running instances and 'DRAINED'.
prexorctl node info <node-id>

# On the daemon host:
sudo systemctl stop prexorcloud-daemon
sudo systemctl disable prexorcloud-daemon

# On the controller:
prexorctl node delete <node-id>
```

See [`drain-node.md`](drain-node.md) for the drain operation in detail.

### Add a Controller (HA)

```bash
# On the new controller host, point at the existing Mongo + Valkey.
sudo prexorctl setup --role controller \
    --mongo-uri "<existing-mongo-uri>" \
    --redis-uri "<existing-valkey-uri>" \
    --bootstrap=false
```

`--bootstrap=false` skips admin-user creation (the existing controller
already has one) and skips CA generation (the new controller reads the
existing CA from Mongo / shared filesystem — see HA install notes in
[`../architecture.md`](../architecture.md) §"HA model").

Verify:

```bash
prexorctl status
# Should list two controllers; both reach ready.
```

Both controllers compete for leases via Valkey. Mutating work is
distributed; reads served from any.

### Remove a Controller (HA)

```bash
# On the controller being removed.
sudo systemctl stop prexorcloud-controller
sudo systemctl disable prexorcloud-controller
```

The peer controller picks up any leases this controller held within
~lease-timeout seconds (default 15s). No additional action required.

## Scaling a Group

### Manual

Each group has `min` and `max` instance counts plus a `desired` setting.
The scheduler keeps the running count in `[min, max]` honoring `desired`.

```bash
# Set explicit count.
prexorctl group update <group> --min 2 --max 10 --desired 4

# Watch the rollout.
prexorctl group info <group>
```

The scheduler creates instances respecting `placement.spreadConstraint`
(across nodes, racks, etc.) and `placement.priority` ordering.

### Auto-scaling

Auto-scaling is driven by the controller's scaling evaluator. Per
group:

```yaml
# group config (POSTed via REST or set with `prexorctl group update`)
scaling:
  enabled: true
  metric: players      # players | cpu | memory | custom
  target: 0.7          # target utilization (0..1)
  scaleUpStep: 2
  scaleDownStep: 1
  cooldownSeconds: 60
```

The evaluator runs every `scheduler.evaluationInterval` (default 5s),
respects `cooldownSeconds`, and refuses to violate `min` or `max`.

Manual override:

```bash
prexorctl group scale <group> --to 8
```

A manual override sets a one-shot desired and disables the evaluator
for `cooldownSeconds`.

### Event Choreography

Time-bound scaling overlays (e.g. "scale `survival` min 8 between 18:00
and 23:00 Europe/Berlin on weekdays") are configured under
`controller.yaml: events:`. Cron-shaped overlays adjust
`minInstances` / `maxInstances` / `scalingMode` / `maintenance` for
their firing window. See [`../mc-domain.md`](../mc-domain.md) §"Event
Choreography."

```yaml
events:
  - id: evening-surge
    cron: "0 18 * * 1-5"
    duration: "PT5H"
    targetGroup: survival
    overlay:
      minInstances: 8
```

## Capacity Planning

Rough baselines. Treat as starting points; load-test against your own traffic.

| Resource              | Headroom rule of thumb                     |
| --------------------- | ------------------------------------------ |
| Controller CPU        | 1 vCPU per ~500 instances, plus 1 vCPU per 10k SSE clients. |
| Controller memory     | 1 GiB baseline + 1 MiB per active instance + per-module overhead. |
| Controller disk       | Mongo + Valkey hot dataset; growth dominated by audit log + crash records. |
| Daemon CPU            | Driven by hosted MC instances, not by daemon overhead (negligible). |
| Daemon memory         | Sum of MC instance heaps + ~256 MiB daemon overhead. |
| Mongo                 | ~1 GiB per 100 instances per month of audit retention. Tune `audit.retentionDays`. |
| Valkey                | ~50 MiB per 1000 instances; SSE replay buffer is the dominant term. |

The nightly perf-baseline job publishes drift signals (see [`../perf-baselines.md`](../perf-baselines.md)). Treat the rule of thumb above as an upper bound on initial sizing; revisit with real numbers from your own deployment.

## Common Failures

| Symptom                                                  | Likely cause                                | Fix                                       |
| -------------------------------------------------------- | ------------------------------------------- | ----------------------------------------- |
| New daemon stays in `PENDING_REGISTER`                   | Token expired or wrong controller URI       | Issue fresh token; check `daemon.yml: controller`. |
| `node list` shows daemon as `READY` but no instances land | Cordoned, drained, or labels mismatch      | `prexorctl node uncordon`; check group `placement.nodeSelector`. |
| Group fails to scale up                                  | `max` reached or no eligible nodes          | Increase `max`; add nodes; relax spread constraint. |
| Auto-scaling oscillates                                  | Cooldown too short for player churn         | Increase `cooldownSeconds`; raise `scaleDownStep` hysteresis. |
| Adding a controller — both stop accepting writes         | Both pointed at same Mongo, different Valkey | Ensure all controllers share **the same** coordination store. |

## Related

- [`drain-node.md`](drain-node.md)
- [`upgrade.md`](upgrade.md) — for rolling upgrades that look like scaling.
- [`../architecture.md`](../architecture.md) §"HA model" — multi-controller semantics.
