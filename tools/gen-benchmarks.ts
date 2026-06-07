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
  return `---
title: Performance Benchmarks
description: Drift-tracked performance targets for the PrexorCloud control plane — controller cold start, coordination round-trip, SSE fan-out, scheduler tick.
---

> **Generated from \`infra/perf/baselines.json\` — do not edit by hand.** Run
> \`pnpm --filter prexorcloud-website gen:bench\` to refresh after bumping
> the committed baselines.

These are the **drift-tracked performance targets** for the PrexorCloud control
plane. They live in [\`infra/perf/baselines.json\`](https://github.com/prexorcloud/prexorcloud/blob/main/infra/perf/baselines.json),
get compared nightly against a fresh run on a GitHub Actions runner, and
surface as a warning in the CI summary when any metric drifts more than
**${b.driftThresholdPercent} %** above the committed target.

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

Snapshot: **${fmtDate(b.generatedAt)}** · Drift threshold: **${b.driftThresholdPercent} %** · Schema: v${b.schemaVersion}

### Controller cold start

| Metric | Target |
| ------ | ------ |
| First \`200\` on \`/api/v1/system/status\` after process start | **${fmtMs(m.controllerColdStartMs.value)}** |

Measured by \`TestCluster.startWithRedis()\` in the harness — boots a fresh
controller against an ephemeral Mongo + Valkey, then polls the system status
endpoint until it returns 200.

### Coordination store SET + GET round trip

| Metric | Target |
| ------ | ------ |
| p50 | **${fmtMs(m.coordinationStoreSetGetMs.p50)}** |
| p95 | **${fmtMs(m.coordinationStoreSetGetMs.p95)}** |

500 sequential SET-then-GET round trips against the harness's Lettuce client.
This is the round-trip latency operators see on every lease acquire, every
fencing-token check, every SSE replay-ticket lookup.

### SSE event latency

| Metric | Target |
| ------ | ------ |
| p50 — \`POST /api/v1/groups\` → matching \`GROUP_CREATED\` | **${fmtMs(m.sseEventLatencyMs.p50)}** |
| p95 — same                                                     | **${fmtMs(m.sseEventLatencyMs.p95)}** |

End-to-end latency from the REST mutation that triggers a domain event to the
moment a subscribed SSE client receives the event payload. Covers the full
event-bus + per-subscriber filter path.

### Scheduler tick

| Metric | Target |
| ------ | ------ |
| Groups in placement state | **${m.schedulerTickMs.groups.toLocaleString('en-US')}** |
| Tick duration p50 | **${fmtMs(m.schedulerTickMs.p50)}** |
| Tick duration p95 | **${fmtMs(m.schedulerTickMs.p95)}** |

Distribution of \`prexorcloud.scheduler.tick.duration\` after seeding
${m.schedulerTickMs.groups.toLocaleString('en-US')} synthetic groups —
the metric an operator running a real network would scrape.

## How drift is reported

The nightly job at \`.github/workflows/ci.yml :: perf-baselines\` runs
\`./gradlew :cloud-test-harness:perfBaselines\` against Mongo 7 + Valkey 8
service containers, then \`scripts/perf-baseline-check.sh\` diffs the fresh
report against the committed baseline. Every drifted metric becomes a
\`::warning\` in the run summary and a row in the GitHub Actions step
summary — but the job always exits 0. **Performance is a soft signal here,
not a merge gate**; see
[ADR 23 — Performance baselines, not performance gates](https://github.com/prexorcloud/prexorcloud/blob/main/docs/engineering/decisions.md#adr-23-performance-baselines-not-performance-gates)
for the rationale.

## Running locally

\`\`\`bash
cd java
./gradlew :cloud-test-harness:perfBaselines
\`\`\`

Requires reachable Mongo + Valkey/Redis (override via
\`PREXOR_TEST_MONGO_URI\` / \`PREXOR_TEST_REDIS_URI\`). The harness writes
\`build/reports/perf-baselines/baseline-report.json\` — the same shape as
the committed file plus an \`env\` block for the run.

## Refreshing the committed baseline

Local laptop numbers are not comparable to GitHub Actions runners. The
refresh policy is documented in the engineering doc but the short version:
collect 7+ nightly samples on a representative runner, take the **p95 of
observed p95s** (median of observed p50s) over that window, bump the value
and \`generatedAt\` in \`infra/perf/baselines.json\`, regenerate this page
with \`pnpm --filter prexorcloud-website gen:bench\`, and commit both.
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
