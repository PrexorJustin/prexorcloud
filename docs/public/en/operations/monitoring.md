---
title: Monitoring + Metrics
description: Prometheus scrape config, the canonical metric series, PromQL recipes, and the alerts that page when something is actually broken.
---

PrexorCloud emits three signals: **metrics** for "is the cluster
degrading over time?", **logs** for "what did we do at 03:14?", and
**SSE events** for "what is happening right now?". This page covers
the metrics. Logs live in [Logs and Audit](/operations/logs-and-audit/);
SSE is documented under [Architecture](/internals/architecture/).

## What you'll learn

- How to scrape PrexorCloud with Prometheus
- The full canonical metric set, broken down by area
- PromQL recipes for the questions operators ask first
- Alert rules with sensible thresholds

## What you do *not* get

- A pre-built Grafana dashboard pack. By design — see
  [Architecture decisions](/internals/architecture/#design-decisions).
  Metric names and labels are stable; build the panels you need.
- Distributed tracing. PrexorCloud is two services with one well-defined
  gRPC contract; OTel adds runtime cost without buying anything.
- In-app alert configuration. Use Alertmanager.

## Scrape config

The controller serves Prometheus exposition at `GET /metrics`. No auth
by default — gate it via reverse-proxy ACL if needed.

```yaml
# prometheus.yml
scrape_configs:
  - job_name: prexorcloud
    metrics_path: /metrics
    scrape_interval: 15s
    static_configs:
      - targets:
          - 'controller-1:8080'
          - 'controller-2:8080'
```

`metrics.enabled` is on by default. Set `metrics.enabled=false` if you
want to disable the endpoint completely.

Naming convention: `prexorcloud_<area>_<thing>_<unit>`. Labels are
short and stable.

## Cluster metrics

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

## Per-node

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_node_cpu_usage` | gauge | `node` |
| `prexorcloud_node_memory_used_bytes` | gauge | `node` |
| `prexorcloud_node_memory_total_bytes` | gauge | `node` |
| `prexorcloud_node_disk_used_bytes` | gauge | `node` |
| `prexorcloud_node_instances` | gauge | `node` |
| `prexorcloud_node_heartbeat_latency_ms` | histogram | `node` |

## Scheduler

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_scheduler_tick_duration` | histogram | — |
| `prexorcloud_scheduler_tick_failures_total` | counter | — |
| `prexorcloud_scheduler_groups_per_tick` | gauge | — |
| `prexorcloud_scheduler_last_tick_lag_ms` | gauge | — |

## gRPC

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_grpc_daemon_sessions_active` | gauge | — |
| `prexorcloud_grpc_inbound_messages_total` | counter | `payload_case` |
| `prexorcloud_grpc_outbound_messages_total` | counter | `payload_case` |
| `prexorcloud_grpc_outbound_dropped_total` | counter | `reason` |

## Coordination + auth

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_coordination_lease_acquire_total` | counter | `scope` |
| `prexorcloud_coordination_lease_renew_total` | counter | `scope` |
| `prexorcloud_coordination_lease_contention_total` | counter | `scope` |
| `prexorcloud_coordination_jwt_revocations_total` | counter | — |
| `prexorcloud_sse_clients_active` | gauge | — |
| `prexorcloud_sse_replay_buffer_depth` | gauge | — |

## HTTP

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_http_requests_total` | counter | `method`, `status_class` |
| `prexorcloud_http_request_duration_ms` | histogram | `method`, `status_class` |

## Module classloader

These pair with the leaked-classloader endpoint at
`GET /api/v1/modules/platform/leaked-classloaders`.

| Metric | Type | Labels |
|---|---|---|
| `prexorcloud_module_classloader_tracked_total` | counter | `moduleId` |
| `prexorcloud_module_classloader_collected_total` | counter | `moduleId` |
| `prexorcloud_module_classloader_leaked` | counter | `moduleId` |
| `prexorcloud_module_classloader_pending` | gauge | — |

## PromQL recipes

Crash rate per group over the last hour:

```text
rate(prexorcloud_crashes_total[1h])
```

Scheduler tick p95 (target: under 200ms at 1k groups):

```text
histogram_quantile(0.95, rate(prexorcloud_scheduler_tick_duration_bucket[5m]))
```

Lease contention rate (early-warning of HA noise):

```text
rate(prexorcloud_coordination_lease_contention_total[5m])
```

HTTP error budget (5xx ratio):

```text
sum(rate(prexorcloud_http_requests_total{status_class="5xx"}[5m]))
  / sum(rate(prexorcloud_http_requests_total[5m]))
```

Instance state distribution per group (stacked area panel):

```text
sum by (group, state) (prexorcloud_instances_by_state)
```

Per-node memory pressure:

```text
prexorcloud_node_memory_used_bytes / prexorcloud_node_memory_total_bytes
```

Module classloader leak signal:

```text
rate(prexorcloud_module_classloader_leaked[1h])
```

## Alerts

This is the recommended baseline. Tune thresholds to your environment.

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
        expr: |
          sum(rate(prexorcloud_http_requests_total{status_class="5xx"}[5m])) /
            sum(rate(prexorcloud_http_requests_total[5m])) > 0.05
        for: 5m
        labels: { severity: critical }
        annotations:
          summary: "HTTP 5xx ratio > 5% for 5 minutes"

      - alert: PrexorCloudNodeOffline
        expr: prexorcloud_nodes_total - prexorcloud_nodes_ready > 0
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "{{ $value }} daemon node(s) not ready"
```

## Building Grafana boards

Suggested rows for a "single pane of glass" board (we don't ship one,
but this is what we'd build first):

1. **Cluster overview** — `nodes_ready`, `nodes_total`,
   `groups_total`, `instances_total`, `players_total`. Big-number panels.
2. **Instance state breakdown** — stacked series of
   `prexorcloud_instances_by_state` by group.
3. **Scheduler health** — tick p95 + tick lag + scheduler failure rate.
4. **HTTP** — RPS by `method`, p95 by `status_class`, 5xx ratio.
5. **HA health** — lease acquire / renew / contention rates by `scope`.
6. **Per-node** — CPU, memory, disk, instance count, heartbeat latency.
7. **Modules** — classloader tracked / collected / leaked / pending.

The controller version label on `up{job="prexorcloud"}` is your
canonical "what's running where" dimension; pin it to a row header.

## Performance baselines

The nightly CI job `:cloud-test-harness:perfBaselines` (tagged
`@Tag("perf")`) runs four scenarios — controller cold start,
coordination-store latency, SSE latency, and scheduler tick at 1k
groups — and surfaces drift > 25% as a soft signal in the run summary.
This is a regression-detection nudge, not a CI gate. See the
perf-baselines doc
for the methodology.

Capture local numbers and trend them in your own monitoring.

## Diagnostics bundle

`prexorctl diagnostics bundle` produces a tar.gz with redacted
controller config, `/system/readiness`, `/system/overview`,
`/system/settings`, Valkey keyspace summary, lease state, and log
statistics. Attach it to incident reports — secrets are blanked by
default; review before sharing.

## Next up

- [Logs and Audit](/operations/logs-and-audit/) — controller / daemon / module logs and the Mongo audit trail
- [Production Checklist](/operations/production-checklist/) — alert wiring step-by-step
- [Architecture](/internals/architecture/) — what each metric measures
