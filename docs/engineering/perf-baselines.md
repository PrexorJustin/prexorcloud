# Performance baselines

Five operational performance numbers, four of them measured nightly:

| metric                          | how it is measured                                                                  |
| ------------------------------- | ------------------------------------------------------------------------------------ |
| controller cold start (ms)      | `TestCluster.startWithRedis()` → first 200 from `/api/v1/system/status`               |
| coordination SET+GET (ms p50/p95) | 500 round trips against the harness's Lettuce client                                  |
| SSE event latency (ms p50/p95)  | `POST /api/v1/groups` → matching `GROUP_CREATED` SSE event                            |
| scheduler tick (ms p50/p95)     | `prexorcloud.scheduler.tick.duration` percentiles after seeding 1k synthetic groups   |
| instance start p50/p95          | **deferred**: requires real MC process spawn; not part of the nightly drift signal    |

The harness lives at
`java/cloud-test-harness/src/test/java/me/prexorjustin/prexorcloud/perf/PerformanceBaselineTest.java`
and is gated by the JUnit `@Tag("perf")` exclusion — `./gradlew test` skips it
by default.

## Running locally

```bash
# Requires reachable Mongo + Valkey/Redis. PREXOR_TEST_MONGO_URI / PREXOR_TEST_REDIS_URI
# override the defaults (mongodb://127.0.0.1:27017 / redis://127.0.0.1:6379).
cd java
./gradlew :cloud-test-harness:perfBaselines

# Tunables (system properties):
./gradlew :cloud-test-harness:perfBaselines \
  -Dperf.scheduler.groups=1000 \
  -Dperf.scheduler.samples=8 \
  -Dperf.coordination.samples=500 \
  -Dperf.sse.samples=30
```

The runner writes `build/reports/perf-baselines/baseline-report.json`. The
drift comparator (`scripts/perf-baseline-check.sh`) diffs this against
`infra/perf/baselines.json` and surfaces drift > `driftThresholdPercent` (25%
by default) — never failing the build, per the plan's "not a hard CI gate"
guidance.

## Nightly CI

`.github/workflows/ci.yml` adds:

- a `schedule:` cron at 03:17 UTC (and `workflow_dispatch:` for manual runs);
- a `perf-baselines` job gated on `github.event_name == 'schedule' ||
  workflow_dispatch'` so PRs and `push: main` don't pay the cost;
- Mongo + Valkey service containers;
- the report uploaded as a 30-day artefact.

The job exits 0 even on drift — drift is reported via `::warning` and the run
summary table (see [`decisions.md`](decisions.md) §"Performance baselines, not performance gates"). Promote a drift signal to an action item by updating `infra/perf/baselines.json` (refresh policy below) or filing a follow-up.

## Refreshing committed baselines

The committed values in `infra/perf/baselines.json` are intentionally
conservative ceilings on initial seed. Once 7+ nightly runs have collected
data on a representative GitHub Actions runner:

1. Pick the **p95 of observed p95s** (or the median of observed p50s) over
   the window — single-run noise should not move a baseline.
2. Edit `infra/perf/baselines.json` and bump `generatedAt`.
3. Note the refresh in the PR description with a link to the runs that
   informed the new numbers.

Do not refresh against locally-run numbers — laptop wall-clock is not
comparable to GHA runners.

## Why instance-start is deferred

Instance-start p50/p95 is the fifth baseline in spirit but is not measured
nightly. Driving it end-to-end requires the daemon to spawn real MC
processes. With `FakeMinecraftServer`, 1k iterations would take 30+ minutes
per nightly run without producing latency numbers operators can act on (the
dominant cost is JVM warmup of the fake, not the cloud control plane).

A meaningful instance-start baseline needs either:

- a daemon-side timer for "received Start RPC → process accepting on the
  port", recorded by `MetricsCollector` and scraped here;
- or a synthetic placement-only path that exercises
  `InstancePlacementCoordinator.dispatch(...)` without a real process spawn.

Either is a follow-up; the four shipped baselines already give us drift
signal on the controller-side fast paths.
