# Observability

Three signals come out of PrexorCloud: **metrics**, **logs**, and **events**. Pick what fits the question:

| Question | Signal |
|---|---|
| Is the cluster degrading over time? | Metrics |
| What did the controller / daemon do at 03:14? | Logs |
| What is happening *right now*? | SSE events |

We do not ship distributed tracing. See [`decisions.md`](decisions.md) §"Prometheus only, no OpenTelemetry."

## Metrics

The controller exposes a Prometheus-compatible endpoint at `GET /metrics`. No auth required by default — gate it behind a reverse proxy ACL if you need to.

`MetricsCollector` is the registration site. Look there for the canonical list of series and labels. The naming convention is `prexorcloud_<area>_<thing>_<unit>`.

### What you get

#### Cluster

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_nodes_total` | gauge | — |
| `prexorcloud_nodes_ready` | gauge | — |
| `prexorcloud_groups_total` | gauge | — |
| `prexorcloud_instances_total` | gauge | — |
| `prexorcloud_instances_by_state` | gauge | `state`, `group` |
| `prexorcloud_players_total` | gauge | — |
| `prexorcloud_players_by_group` | gauge | `group` |
| `prexorcloud_crashes_total` | counter | `group`, `exit_reason` |
| `prexorcloud_crash_loops_total` | counter | `group` |
| `prexorcloud_scaling_events_total` | counter | `group`, `direction` |
| `prexorcloud_deployments_active` | gauge | — |

#### Per-node

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_node_cpu_usage` | gauge | `node` |
| `prexorcloud_node_memory_used_bytes` | gauge | `node` |
| `prexorcloud_node_memory_total_bytes` | gauge | `node` |
| `prexorcloud_node_disk_used_bytes` | gauge | `node` |
| `prexorcloud_node_instances` | gauge | `node` |
| `prexorcloud_node_heartbeat_latency_ms` | histogram | `node` |

#### Scheduler

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_scheduler_tick_duration` | histogram | — |
| `prexorcloud_scheduler_tick_failures_total` | counter | — |
| `prexorcloud_scheduler_groups_per_tick` | gauge | — |
| `prexorcloud_scheduler_last_tick_lag_ms` | gauge | — |

#### gRPC

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_grpc_daemon_sessions_active` | gauge | — |
| `prexorcloud_grpc_inbound_messages_total` | counter | `payload_case` |
| `prexorcloud_grpc_outbound_messages_total` | counter | `payload_case` |
| `prexorcloud_grpc_outbound_dropped_total` | counter | `reason` |

#### Coordination + auth

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_coordination_lease_acquire_total` | counter | `scope` |
| `prexorcloud_coordination_lease_renew_total` | counter | `scope` |
| `prexorcloud_coordination_lease_contention_total` | counter | `scope` |
| `prexorcloud_coordination_jwt_revocations_total` | counter | — |
| `prexorcloud_sse_clients_active` | gauge | — |
| `prexorcloud_sse_replay_buffer_depth` | gauge | — |

#### HTTP

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_http_requests_total` | counter | `method`, `status_class` |
| `prexorcloud_http_request_duration_ms` | histogram | `method`, `status_class` |

#### Module classloader

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_module_classloader_tracked_total` | counter | `moduleId` |
| `prexorcloud_module_classloader_collected_total` | counter | `moduleId` |
| `prexorcloud_module_classloader_leaked` | counter | `moduleId` |
| `prexorcloud_module_classloader_pending` | gauge | — |

### Useful PromQL

Crash rate per group over the last hour:

```promql
rate(prexorcloud_crashes_total[1h])
```

Scheduler tick p95:

```promql
histogram_quantile(0.95, rate(prexorcloud_scheduler_tick_duration_bucket[5m]))
```

Lease contention rate (early warning of HA noise):

```promql
rate(prexorcloud_coordination_lease_contention_total[5m])
```

HTTP error budget (5xx ratio):

```promql
sum(rate(prexorcloud_http_requests_total{status_class="5xx"}[5m]))
  / sum(rate(prexorcloud_http_requests_total[5m]))
```

Module classloader leak warning:

```promql
rate(prexorcloud_module_classloader_leaked[1h])
```

### Scrape config

```yaml
scrape_configs:
  - job_name: prexorcloud
    metrics_path: /metrics
    scrape_interval: 15s
    static_configs:
      - targets: ['controller:8080']
```

### Alerts

Suggested baseline alert set, tunable to your environment. These are *not* installed for you.

```yaml
groups:
  - name: prexorcloud
    rules:
      - alert: PrexorCloudControllerDown
        expr: up{job="prexorcloud"} == 0
        for: 2m
        labels: { severity: critical }
        annotations:
          summary: "Controller scrape target is down"

      - alert: PrexorCloudCrashLoop
        expr: increase(prexorcloud_crash_loops_total[1h]) > 0
        labels: { severity: critical }
        annotations:
          summary: "Crash loop in group {{ $labels.group }}"

      - alert: PrexorCloudSchedulerLag
        expr: prexorcloud_scheduler_last_tick_lag_ms > 30000
        for: 2m
        labels: { severity: warning }
        annotations:
          summary: "Scheduler tick is more than 30s behind"

      - alert: PrexorCloudLeaseContention
        expr: rate(prexorcloud_coordination_lease_contention_total[5m]) > 1
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "Sustained lease contention — multiple controllers fighting for the same scope"

      - alert: PrexorCloudClassloaderLeak
        expr: increase(prexorcloud_module_classloader_leaked[24h]) > 0
        labels: { severity: warning }
        annotations:
          summary: "Module {{ $labels.moduleId }} leaked a classloader"

      - alert: PrexorCloudHttpErrorBudget
        expr: sum(rate(prexorcloud_http_requests_total{status_class="5xx"}[5m])) /
              sum(rate(prexorcloud_http_requests_total[5m])) > 0.05
        for: 5m
        labels: { severity: critical }
        annotations:
          summary: "HTTP 5xx ratio > 5% for 5 minutes"
```

### Performance baselines

A nightly CI job runs perf baselines (cold start, coordination latency, SSE latency, scheduler tick at 1k groups) and surfaces drift > 25% as a soft signal. See [`perf-baselines.md`](perf-baselines.md).

## Logs

The controller and daemon both use SLF4J + Logback. There is no `System.out.println` anywhere — see [`conventions.md`](conventions.md).

### Format

Two formats, set per-process via `logging.format`:

- `HUMAN` (default) — human-readable single-line layout.
- `JSON` — line-delimited structured JSON via `JsonLogEncoder`.

```yaml
logging:
  format: JSON
  level: INFO
  file: logs/latest.log
  maxFileSize: 50MB
  maxHistory: 30        # days
```

JSON output includes: `timestamp`, `level`, `thread`, `logger`, `message`, MDC fields (e.g. `requestId`), and any structured argument the call passed.

### Levels

- `ERROR` — unexpected failures requiring attention.
- `WARN` — recoverable issues (crash reports, reconnects, stale data).
- `INFO` — lifecycle events (node connected, instance started, module loaded, lease acquired).
- `DEBUG` — operational detail (gRPC payload cases, template hashing, lease renewal).
- `TRACE` — very detailed (gRPC frame bodies, SQL-shaped Mongo queries).

### MDC correlation

`RequestIdMiddleware` adds a `requestId` to MDC for every REST request. Plumb it forward into module code by reading MDC and re-binding around any thread switch.

### Streaming logs

For live debugging without ssh:

```bash
prexorctl logs controller --follow                     # main controller log buffer
prexorctl logs controller --tail 200 --level WARN      # warn+ over the last 200 lines
prexorctl logs controller --logger org.eclipse         # filter by logger prefix
prexorctl logs daemon node-1 --follow                  # daemon log over its gRPC channel
```

Both commands are SSE-backed (`/api/v1/system/logs/stream`, `/api/v1/nodes/{id}/logs/stream`) and gated on `system.logs`.

A controller restart clears the controller log ring (`ControllerLogBuffer`) and the per-node `DaemonLogStore`. Logs are also persisted to disk by Logback (`logging.file`), so historical reads go through your file aggregation pipeline.

## Events (SSE)

The 22 SSE event types are the live narrative of the cluster.

### How to consume

```bash
# Get a 30-second SSE ticket
TICKET=$(curl -s -X POST -H "Authorization: Bearer $JWT" \
    "$CONTROLLER/api/v1/events/ticket" | jq -r .ticket)

# Stream
curl -N "$CONTROLLER/api/v1/events/stream?ticket=$TICKET"
```

The dashboard does the same internally via `useSseEventBus()`. Modules consume the in-process `EventBus` (which the SSE bridge mirrors).

### Event categories

| Category | Examples |
|---|---|
| **Group** | `GROUP_CREATED`, `GROUP_UPDATED`, `GROUP_DELETED`, `GROUP_PAUSED` |
| **Instance** | `INSTANCE_SCHEDULED`, `INSTANCE_STARTED`, `INSTANCE_STOPPING`, `INSTANCE_STOPPED`, `INSTANCE_CRASHED` |
| **Node** | `NODE_CONNECTED`, `NODE_DISCONNECTED`, `NODE_DRAINED` |
| **Player** | `PLAYER_JOIN`, `PLAYER_LEAVE`, `PLAYER_TRANSFER`, `PlayerJourneyEvent` |
| **Deployment** | `DEPLOYMENT_STARTED`, `DEPLOYMENT_PAUSED`, `DEPLOYMENT_RESUMED`, `DEPLOYMENT_COMPLETED`, `DEPLOYMENT_ROLLED_BACK` |
| **Module** | `MODULE_INSTALLED`, `MODULE_ACTIVATED`, `MODULE_DEACTIVATED`, `MODULE_UNINSTALLED` |
| **Capability** | `CAPABILITY_REGISTERED`, `CAPABILITY_DEREGISTERED` |
| **Network** | `NETWORK_UPDATED` |
| **Choreography** | `CHOREOGRAPHY_OVERLAY_ACTIVATED`, `CHOREOGRAPHY_OVERLAY_DEACTIVATED` |

The full enumeration lives under `cloud-api/.../api/event/`.

### Sequence + replay

Each event carries a monotonic sequence. Reconnect with `Last-Event-ID: <seq>` to replay missed events from the buffer. Production buffers in Valkey (survives controller restart); development buffers in process memory.

### Why SSE and not WebSocket

See [`decisions.md`](decisions.md) §"SSE for live data, not WebSocket."

## Diagnostics bundle

`prexorctl diagnostics bundle` produces a tar.gz with:

- Redacted controller config (`controller.yml` with secrets blanked out).
- `/api/v1/system/readiness` snapshot.
- `/api/v1/system/overview` snapshot.
- `/api/v1/system/settings`.
- Valkey keyspace summary (`/api/v1/system/redis/keyspace`).
- Lease state.
- Log statistics.

Attach the bundle when filing an incident report or asking for help. The redaction keeps secrets out by default; review the bundle before sharing.

## What you do *not* get

- **Distributed tracing.** See ADR 9.
- **OTel exporter.** Same.
- **Pre-built Grafana dashboard pack.** See ADR 10. The metric names and labels are stable; build dashboards against them.
- **Log aggregation.** Use Loki, Elastic, Datadog, whatever. The JSON format is consumable by any of them.
- **In-app alert configuration.** Use Alertmanager. The example rules above are a starting point.
