#!/usr/bin/env -S node --experimental-strip-types
/*
 * tools/gen-benchmarks.ts — render the public performance benchmarks page
 * from `infra/perf/baselines.json`.
 *
 * Output goes to `docs/public/en/benchmarks.md`. The committed JSON in
 * `infra/perf/` is the authoritative target set; this script projects it
 * into Starlight-shaped markdown so prexor.cloud/benchmarks/ shows the
 * same numbers a contributor would see in the file.
 *
 * Drift is caught the same way as gen-cli-docs.ts: a CI step re-runs this
 * generator and fails on any working-tree diff. If you bump the baseline,
 * remember to `pnpm gen:bench` and commit the regenerated page.
 *
 * Usage:
 *   node --experimental-strip-types tools/gen-benchmarks.ts
 *   pnpm --filter prexorcloud-website gen:bench   (same thing)
 *
 * Run from the repo root.
 *
 * Requires: Node >= 22 (built-in TS stripping). Pure stdlib — no deps.
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const ROOT = resolve(import.meta.dirname, '..');
const INPUT = resolve(ROOT, 'infra/perf/baselines.json');
const OUTPUT = resolve(ROOT, 'docs/public/en/benchmarks.md');

interface Baselines {
  schemaVersion: number;
  generatedAt: string;
  driftThresholdPercent: number;
  metrics: {
    controllerColdStartMs: { value: number };
    coordinationStoreSetGetMs: { p50: number; p95: number };
    sseEventLatencyMs: { p50: number; p95: number };
    schedulerTickMs: { groups: number; p50: number; p95: number };
  };
}

function load(): Baselines {
  const raw = readFileSync(INPUT, 'utf8');
  const parsed = JSON.parse(raw) as Baselines;
  if (parsed.schemaVersion !== 1) {
    throw new Error(`unsupported baselines schemaVersion ${parsed.schemaVersion} (expected 1)`);
  }
  return parsed;
}

function fmtMs(value: number): string {
  if (value >= 1000) {
    return `${(value / 1000).toFixed(1)} s`;
  }
  if (Number.isInteger(value)) {
    return `${value} ms`;
  }
  return `${value.toFixed(1)} ms`;
}

function fmtDate(iso: string): string {
  // YYYY-MM-DD slice — generatedAt is always ISO-8601 with a Z suffix.
  return iso.slice(0, 10);
}

function render(b: Baselines): string {
  const m = b.metrics;
  const repo = 'https://github.com/prexorjustin/prexorcloud/blob/main';
  return `---
title: Performance benchmarks
description: Drift-tracked performance targets for the PrexorCloud control plane — controller cold start, coordination round-trip, SSE fan-out, scheduler tick.
---

This page lists the control-plane performance targets the project tracks for
drift, how each one is measured, and how to reproduce a run yourself. The
numbers below are committed targets — conservative ceilings, not marketing
figures — read straight from the source of truth.

> This page is generated from
> [\`infra/perf/baselines.json\`](${repo}/infra/perf/baselines.json) by
> \`tools/gen-benchmarks.ts\`. Don't edit it by hand — change the JSON and run
> \`pnpm --filter prexorcloud-website gen:bench\`. A CI step re-runs the
> generator and fails on any working-tree diff.

The targets live in version control, get compared nightly against a fresh run
on a GitHub Actions runner, and surface a warning in the run summary when a
metric drifts more than ${b.driftThresholdPercent}% above its committed value.
Drift never fails the build — see
[ADR 23](${repo}/docs/engineering/decisions.md#adr-23-performance-baselines-not-performance-gates).

## What is measured, and what is not

Four metrics exercise the controller-side fast paths: cold start, the
coordination store (Valkey/Redis), the SSE event bus, and the scheduler tick.
The harness that produces them is
[\`PerformanceBaselineTest\`](${repo}/java/cloud-test-harness/src/test/java/me/prexorjustin/prexorcloud/perf/PerformanceBaselineTest.java)
(\`@Tag("perf")\`, excluded from the default test pass).

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

Snapshot: ${fmtDate(b.generatedAt)} · Drift threshold: ${b.driftThresholdPercent}% · Schema: v${b.schemaVersion}

### Controller cold start

| Metric | Target |
| ------ | ------ |
| First \`200\` on \`/api/v1/system/status\` after process start | ${fmtMs(m.controllerColdStartMs.value)} |

\`TestCluster.startWithRedis()\` boots a fresh controller against an ephemeral
Mongo + Valkey, then polls the authenticated status endpoint until it returns
\`200\`. The target is wall-clock time from process start to first \`200\`.

### Coordination store SET + GET round trip

| Metric | Target |
| ------ | ------ |
| p50 | ${fmtMs(m.coordinationStoreSetGetMs.p50)} |
| p95 | ${fmtMs(m.coordinationStoreSetGetMs.p95)} |

500 sequential SET-then-GET round trips (after 50 warmup pairs) against the
harness's Lettuce client. This is the round-trip latency behind every lease
acquire, every fencing-token check, and every SSE replay-ticket lookup.

### SSE event latency

| Metric | Target |
| ------ | ------ |
| p50 — \`POST /api/v1/groups\` → matching \`GROUP_CREATED\` | ${fmtMs(m.sseEventLatencyMs.p50)} |
| p95 — same | ${fmtMs(m.sseEventLatencyMs.p95)} |

End-to-end latency over 30 samples, from the REST mutation that triggers a
domain event to the moment a subscribed SSE client receives the payload. Covers
the full event-bus and per-subscriber filter path.

### Scheduler tick

| Metric | Target |
| ------ | ------ |
| Groups in placement state | ${m.schedulerTickMs.groups.toLocaleString('en-US')} |
| Tick duration p50 | ${fmtMs(m.schedulerTickMs.p50)} |
| Tick duration p95 | ${fmtMs(m.schedulerTickMs.p95)} |

Percentiles of the \`prexorcloud.scheduler.tick.duration\` timer after seeding
${m.schedulerTickMs.groups.toLocaleString('en-US')} synthetic groups — the same
metric an operator scrapes in production as
\`prexorcloud_scheduler_tick_duration_seconds\` (see
[Monitoring and metrics](/operations/monitoring/)).

## How drift is reported

The nightly job at
[\`.github/workflows/nightly.yml\`](${repo}/.github/workflows/nightly.yml)
(\`perf-baselines\`) runs \`./gradlew :cloud-test-harness:perfBaselines\`
against Mongo 7 + Valkey 8 service containers, then
[\`scripts/perf-baseline-check.sh\`](${repo}/scripts/perf-baseline-check.sh)
diffs the fresh report against the committed baseline. Every drifted metric
becomes a \`::warning\` and a row in the GitHub Actions step summary, and the
job always exits \`0\`. Performance is a soft signal, not a merge gate.

## Run it locally

\`\`\`bash
cd java
./gradlew :cloud-test-harness:perfBaselines
\`\`\`

The run needs a reachable Mongo and Valkey/Redis; override the defaults with
\`PREXOR_TEST_MONGO_URI\` and \`PREXOR_TEST_REDIS_URI\`. The harness writes
\`build/reports/perf-baselines/baseline-report.json\` — the same shape as the
committed file, plus an \`env\` block describing the run. Laptop numbers are not
comparable to CI runners; use a local run to spot large regressions, not to set
targets.

## Refresh the committed baseline

Collect 7+ nightly samples on a representative runner, take the p95 of the
observed p95s (and the median of the observed p50s) over that window, then bump
the value and \`generatedAt\` in
[\`infra/perf/baselines.json\`](${repo}/infra/perf/baselines.json). Regenerate
this page with \`pnpm --filter prexorcloud-website gen:bench\` and commit both.
`;
}

function main(): void {
  const baselines = load();
  const markdown = render(baselines);
  mkdirSync(dirname(OUTPUT), { recursive: true });
  writeFileSync(OUTPUT, markdown);
  console.log(`[gen-benchmarks] wrote ${OUTPUT}`);
}

main();
