---
title: Performance benchmarks
description: Drift-tracked performance targets for the PrexorCloud control plane — controller cold start, coordination round-trip, SSE fan-out, scheduler tick.
---

This page lists the control-plane performance targets the project tracks for
drift, how each one is measured, and how to reproduce a run yourself. The
numbers below are committed targets — conservative ceilings, not marketing
figures — read straight from the source of truth.

> This page is generated from
> [`infra/perf/baselines.json`](https://github.com/prexorjustin/prexorcloud/blob/main/infra/perf/baselines.json) by
> `tools/gen-benchmarks.ts`. Don't edit it by hand — change the JSON and run
> `pnpm --filter prexorcloud-website gen:bench`. A CI step re-runs the
> generator and fails on any working-tree diff.

The targets live in version control, get compared nightly against a fresh run
on a GitHub Actions runner, and surface a warning in the run summary when a
metric drifts more than 25% above its committed value.
Drift never fails the build — see
[ADR 23](https://github.com/prexorjustin/prexorcloud/blob/main/docs/engineering/decisions.md#adr-23-performance-baselines-not-performance-gates).

## What is measured, and what is not

Four metrics exercise the controller-side fast paths: cold start, the
coordination store (Valkey/Redis), the SSE event bus, and the scheduler tick.
The harness that produces them is
[`PerformanceBaselineTest`](https://github.com/prexorjustin/prexorcloud/blob/main/java/cloud-test-harness/src/test/java/me/prexorjustin/prexorcloud/perf/PerformanceBaselineTest.java)
(`@Tag("perf")`, excluded from the default test pass).

Some numbers are deliberately not published here because they need real-cluster
data the nightly CI run can't produce honestly:

- **Committed targets are p50 and p95 only.** The harness also records a p99 in
  its report, but p99 is not yet a tracked target.
- **Instance-start latency is deferred.** Driving a real Minecraft process
  spawn on every nightly run is not practical on shared CI runners.
- **Production-scale figures are not modelled.** Scheduler p99 at ~100 real
  groups on an external MongoDB, and a long-horizon (e.g. 60-day) drift trend,
  need a real cluster — neither is published yet.

## Current targets

Snapshot: 2026-05-04 · Drift threshold: 25% · Schema: v1

### Controller cold start

| Metric | Target |
| ------ | ------ |
| First `200` on `/api/v1/system/status` after process start | 8.0 s |

`TestCluster.startWithRedis()` boots a fresh controller against an ephemeral
Mongo + Valkey, then polls the authenticated status endpoint until it returns
`200`. The target is wall-clock time from process start to first `200`.

### Coordination store SET + GET round trip

| Metric | Target |
| ------ | ------ |
| p50 | 2 ms |
| p95 | 5 ms |

500 sequential SET-then-GET round trips (after 50 warmup pairs) against the
harness's Lettuce client. This is the round-trip latency behind every lease
acquire, every fencing-token check, and every SSE replay-ticket lookup.

### SSE event latency

| Metric | Target |
| ------ | ------ |
| p50 — `POST /api/v1/groups` → matching `GROUP_CREATED` | 50 ms |
| p95 — same | 200 ms |

End-to-end latency over 30 samples, from the REST mutation that triggers a
domain event to the moment a subscribed SSE client receives the payload. Covers
the full event-bus and per-subscriber filter path.

### Scheduler tick

| Metric | Target |
| ------ | ------ |
| Groups in placement state | 1,000 |
| Tick duration p50 | 50 ms |
| Tick duration p95 | 150 ms |

Percentiles of the `prexorcloud.scheduler.tick.duration` timer after seeding
1,000 synthetic groups — the same
metric an operator scrapes in production as
`prexorcloud_scheduler_tick_duration_seconds` (see
[Monitoring and metrics](/operations/monitoring/)).

## How drift is reported

The nightly job at
[`.github/workflows/nightly.yml`](https://github.com/prexorjustin/prexorcloud/blob/main/.github/workflows/nightly.yml)
(`perf-baselines`) runs `./gradlew :cloud-test-harness:perfBaselines`
against Mongo 7 + Valkey 8 service containers, then
[`scripts/perf-baseline-check.sh`](https://github.com/prexorjustin/prexorcloud/blob/main/scripts/perf-baseline-check.sh)
diffs the fresh report against the committed baseline. Every drifted metric
becomes a `::warning` and a row in the GitHub Actions step summary, and the
job always exits `0`. Performance is a soft signal, not a merge gate.

## Run it locally

```bash
cd java
./gradlew :cloud-test-harness:perfBaselines
```

The run needs a reachable Mongo and Valkey/Redis; override the defaults with
`PREXOR_TEST_MONGO_URI` and `PREXOR_TEST_REDIS_URI`. The harness writes
`build/reports/perf-baselines/baseline-report.json` — the same shape as the
committed file, plus an `env` block describing the run. Laptop numbers are not
comparable to CI runners; use a local run to spot large regressions, not to set
targets.

## Refresh the committed baseline

Collect 7+ nightly samples on a representative runner, take the p95 of the
observed p95s (and the median of the observed p50s) over that window, then bump
the value and `generatedAt` in
[`infra/perf/baselines.json`](https://github.com/prexorjustin/prexorcloud/blob/main/infra/perf/baselines.json). Regenerate
this page with `pnpm --filter prexorcloud-website gen:bench` and commit both.
