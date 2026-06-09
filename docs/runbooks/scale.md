# Scale

Two axes: cluster scale (more Daemon nodes or more Controllers) and Group
scale (more Instances of a Group). This runbook covers both.

## Scaling the cluster

### Add a Daemon node

```bash
# On any Controller host, mint a single-use daemon join token:
prexorctl token create --ttl 1h
# -> Token: prxn_xxxxxxxxxxxxxxxx
```

```bash
# On the new Daemon host:
sudo prexorctl setup \
    --non-interactive \
    --component daemon \
    --install-mode native \
    --daemon-node-id node-fra-2 \
    --daemon-controller-host <controller-host> \
    --daemon-controller-grpc-port 9090 \
    --daemon-join-token prxn_xxxxxxxxxxxxxxxx
```

The setup flow registers the node, redeems the token for a per-Daemon mTLS
certificate, installs the systemd unit, and starts the Daemon. Within
~10 seconds:

```bash
prexorctl node list
```

The new node shows `ONLINE` with whatever labels its `daemon.yml: labels`
block declares.

The scheduler picks up the new node automatically. New Instance placements
consider it on the next `scheduler.evaluationIntervalSeconds` tick
(default `15`).

### Remove a Daemon node

```bash
prexorctl node drain <node-id>
# Wait until the node reports zero running instances (status DRAINING).
prexorctl node info <node-id>

# On the Daemon host:
sudo systemctl stop prexorcloud-daemon
sudo systemctl disable prexorcloud-daemon
```

There is no `prexorctl node delete`. Drop the node record through REST; the
call returns `409 CONFLICT` while the Daemon is still connected, so stop it
first:

```bash
curl -fsS -X DELETE "https://<controller-host>:8080/api/v1/nodes/<node-id>" \
    -H "Authorization: Bearer <token>"
```

See [`drain-node.md`](drain-node.md) for the drain operation in detail.

### Add a Controller (HA)

```bash
# On an existing Controller, mint a single-use cluster join token:
prexorctl cluster join-token create \
    --label controller-2 \
    --join-addr <existing-controller>:9090
# -> prints the wire token
```

```bash
# On the new Controller host, point at the existing Mongo + Valkey:
sudo prexorctl setup \
    --non-interactive \
    --component controller \
    --install-mode native \
    --controller-mongo-mode remote \
    --controller-mongo-uri "<existing-mongo-uri>" \
    --controller-redis-mode remote \
    --controller-redis-uri "<existing-valkey-uri>"
```

Stage the wire token before the first start, relative to the Controller
install dir:

```bash
printf '%s' "<wire-token>" > config/security/pending-join-token
```

On boot the new Controller detects the token, presents cluster-CA-signed
mTLS material, and the leader expands the Raft group through joint
consensus. Verify:

```bash
prexorctl cluster status
# Lists both Controllers; both reach the cluster, one is leader.
```

Both Controllers run active-active: they compete for per-Group leases via
Valkey, mutating work is distributed across them, and any Controller serves
reads. See [HA setup](../public/en/operations/ha-setup.md) for the full
procedure and [Cluster model](../public/en/concepts/cluster-model.md) for
the Raft join semantics.

### Remove a Controller (HA)

```bash
# On the Controller being removed:
sudo systemctl stop prexorcloud-controller
sudo systemctl disable prexorcloud-controller

# On a surviving Controller, drop the peer from the Raft member list:
prexorctl cluster eject <node-id> --reason "decommission"
```

Ejection removes the peer from the Raft group through joint consensus. A
surviving Controller picks up any per-Group leases the removed Controller
held within the lease timeout (default 15s). See
[`recover-cluster.md`](recover-cluster.md) for quorum-loss recovery.

## Scaling a Group

### Manual

Each Group has `minInstances` and `maxInstances` counts. The scheduler keeps
the running Instance count within those bounds; the Group's `scalingMode`
decides how it moves between them.

```bash
# Adjust the bounds.
prexorctl group update survival --min 2 --max 10

# Watch the rollout.
prexorctl group info survival
```

For an exact, fixed count, set `STATIC` mode with `minInstances` equal to
that count:

```bash
prexorctl group update survival --scaling-mode STATIC --min 4
```

The scheduler places Instances respecting the Group's `spreadConstraint`
(spread across a node-label bucket such as `rack` or `zone`) and `priority`
ordering.

### Auto-scaling

Auto-scaling is the default (`scalingMode: DYNAMIC`). The Controller's
`ScalingEvaluator` keeps a Group between `minInstances` and `maxInstances`
on player load. The per-Group fields:

```yaml
# Group config (set with `prexorctl group update` or POSTed via REST)
scalingMode: DYNAMIC
minInstances: 2
maxInstances: 10
maxPlayers: 100
scaleUpThreshold: 0.8       # scale up when every instance is >= 80% full
scaleDownAfterSeconds: 300  # an empty instance must idle this long before teardown
scaleCooldownSeconds: 60    # no further scaling for this long after an action
```

The evaluator runs every `scheduler.evaluationIntervalSeconds` (default
`15`), honors `scaleCooldownSeconds`, and never crosses `minInstances` or
`maxInstances`. Scale-up adds one Instance only when every `RUNNING`
Instance is at or above `scaleUpThreshold`; scale-down stops one empty
Instance that has idled past `scaleDownAfterSeconds`. See
[Scheduling and scaling](../public/en/concepts/scheduling-and-scaling.md)
for the full evaluator logic.

There is no `group scale` command. Change the count by moving the bounds
with `group update`. To add or remove one Instance out of band — pre-warming,
or driving a `MANUAL` Group — use:

```bash
prexorctl instance start survival   # place one more instance
prexorctl instance stop survival-3  # stop a specific instance
```

### Event Choreography

Time-bound scaling overlays (for example, "raise `survival` to
`minInstances` 8 between 18:00 and 23:00 Europe/Berlin on weekdays") live
under the top-level `events:` list in `controller.yaml`. Each entry fires on
its cron schedule, stays active for `durationSeconds`, and temporarily
overlays `minInstances` / `maxInstances` / `scalingMode` / `maintenance` on
the target Group without editing it.

```yaml
events:
  - name: evening-surge
    group: survival
    cron: "0 18 * * 1-5"      # 18:00 on weekdays
    timezone: Europe/Berlin
    durationSeconds: 18000    # active for 5 hours
    overlay:
      minInstances: 8
```

See [Scheduling and scaling](../public/en/concepts/scheduling-and-scaling.md)
for how overlays resolve.

## Capacity planning

Rough baselines. Treat as starting points; load-test against your own traffic.

| Resource              | Headroom rule of thumb                     |
| --------------------- | ------------------------------------------ |
| Controller CPU        | 1 vCPU per ~500 Instances, plus 1 vCPU per 10k SSE clients. |
| Controller memory     | 1 GiB baseline + 1 MiB per active Instance + per-Module overhead. |
| Controller disk       | Mongo + Valkey hot dataset; growth dominated by audit log + crash records. |
| Daemon CPU            | Driven by hosted MC Instances, not by Daemon overhead (negligible). |
| Daemon memory         | Sum of MC Instance heaps + ~256 MiB Daemon overhead. |
| Mongo                 | ~1 GiB per 100 Instances per month of audit retention. Tune `scheduler.auditRetentionDays`. |
| Valkey                | ~50 MiB per 1000 Instances; SSE replay buffer is the dominant term. |

The nightly perf-baseline job publishes drift signals (see
[Benchmarks](../public/en/benchmarks.md)). Treat the rule of thumb above as
an upper bound on initial sizing; revisit with real numbers from your own
deployment.

## Common failures

| Symptom                                                  | Likely cause                                | Fix                                       |
| -------------------------------------------------------- | ------------------------------------------- | ----------------------------------------- |
| New Daemon stays in `PENDING`                            | Token expired or wrong Controller URI       | Issue a fresh token; check `daemon.yml: controller.host`. |
| `node list` shows the node as `ONLINE` but no Instances land | Cordoned, drained, or labels mismatch   | `prexorctl node undrain <node-id>`; check the Group's `nodeAffinity`. |
| Group fails to scale up                                  | `maxInstances` reached or no eligible nodes | Raise `maxInstances`; add nodes; relax the spread constraint. |
| Auto-scaling oscillates                                  | Cooldown too short for player churn         | Raise `scaleCooldownSeconds`; raise `scaleDownAfterSeconds` so empty Instances aren't torn down too eagerly. |
| Adding a Controller — both stop accepting writes         | Both pointed at same Mongo, different Valkey | Ensure all Controllers share **the same** coordination store. |

## Related

- [`drain-node.md`](drain-node.md)
- [`upgrade.md`](upgrade.md) — for rolling upgrades that look like scaling.
- [Architecture](../public/en/concepts/architecture.md) — the active-active HA model and lease rules.
- [Cluster model](../public/en/concepts/cluster-model.md) — Raft membership, join tokens, and ejection.
