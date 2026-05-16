---
title: Performance Benchmarks
description: Drift-tracked performance targets for the PrexorCloud control plane — controller cold start, coordination round-trip, SSE fan-out, scheduler tick.
---

> **Generated from `infra/perf/baselines.json` — do not edit by hand.** Run
> `pnpm --filter prexorcloud-website gen:bench` to refresh after bumping
> the committed baselines.

These are the **drift-tracked performance targets** for the PrexorCloud control
plane. They live in [`infra/perf/baselines.json`](https://github.com/prexorcloud/prexorcloud/blob/main/infra/perf/baselines.json),
get compared nightly against a fresh run on a GitHub Actions runner, and
surface as a warning in the CI summary when any metric drifts more than
**25 %** above the committed target.

The numbers are conservative ceilings, not aspirations — they will be
tightened over time as more nightly samples accumulate. See
[engineering/perf-baselines.md](https://github.com/prexorcloud/prexorcloud/blob/main/docs/engineering/perf-baselines.md)
for the measurement methodology and the refresh policy.

:::note[What is — and is not — measured]
The four published metrics exercise the **controller-side fast paths**: cold
start, the coordination store (Valkey/Redis), the SSE event bus, and the
scheduler tick. Instance start latency is intentionally deferred: driving a
real MC process spawn nightly is not yet practical on shared CI runners.
See "Why instance-start is deferred" in the engineering doc.
:::

## Current targets

Snapshot: **2026-05-04** · Drift threshold: **25 %** · Schema: v1

### Controller cold start

| Metric | Target |
| ------ | ------ |
| First `200` on `/api/v1/system/status` after process start | **8.0 s** |

Measured by `TestCluster.startWithRedis()` in the harness — boots a fresh
controller against an ephemeral Mongo + Valkey, then polls the system status
endpoint until it returns 200.

### Coordination store SET + GET round trip

| Metric | Target |
| ------ | ------ |
| p50 | **2 ms** |
| p95 | **5 ms** |

500 sequential SET-then-GET round trips against the harness's Lettuce client.
This is the round-trip latency operators see on every lease acquire, every
fencing-token check, every SSE replay-ticket lookup.

### SSE event latency

| Metric | Target |
| ------ | ------ |
| p50 — `POST /api/v1/groups` → matching `GROUP_CREATED` | **50 ms** |
| p95 — same                                                     | **200 ms** |

End-to-end latency from the REST mutation that triggers a domain event to the
moment a subscribed SSE client receives the event payload. Covers the full
event-bus + per-subscriber filter path.

### Scheduler tick

| Metric | Target |
| ------ | ------ |
| Groups in placement state | **1,000** |
| Tick duration p50 | **50 ms** |
| Tick duration p95 | **150 ms** |

Distribution of `prexorcloud.scheduler.tick.duration` after seeding
1,000 synthetic groups —
the metric an operator running a real network would scrape.

## How drift is reported

The nightly job at `.github/workflows/ci.yml :: perf-baselines` runs
`./gradlew :cloud-test-harness:perfBaselines` against Mongo 7 + Valkey 8
service containers, then `scripts/perf-baseline-check.sh` diffs the fresh
report against the committed baseline. Every drifted metric becomes a
`::warning` in the run summary and a row in the GitHub Actions step
summary — but the job always exits 0. **Performance is a soft signal here,
not a merge gate**; see
[ADR 23 — Performance baselines, not performance gates](https://github.com/prexorcloud/prexorcloud/blob/main/docs/engineering/decisions.md#adr-23-performance-baselines-not-performance-gates)
for the rationale.

## Running locally

```bash
cd java
./gradlew :cloud-test-harness:perfBaselines
```

Requires reachable Mongo + Valkey/Redis (override via
`PREXOR_TEST_MONGO_URI` / `PREXOR_TEST_REDIS_URI`). The harness writes
`build/reports/perf-baselines/baseline-report.json` — the same shape as
the committed file plus an `env` block for the run.

## Refreshing the committed baseline

Local laptop numbers are not comparable to GitHub Actions runners. The
refresh policy is documented in the engineering doc but the short version:
collect 7+ nightly samples on a representative runner, take the **p95 of
observed p95s** (median of observed p50s) over that window, bump the value
and `generatedAt` in `infra/perf/baselines.json`, regenerate this page
with `pnpm --filter prexorcloud-website gen:bench`, and commit both.
