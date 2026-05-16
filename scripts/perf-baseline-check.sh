#!/usr/bin/env bash
# Compare a fresh perf-baseline report against infra/perf/baselines.json. Surfaces drift in the
# GitHub Actions summary when running under CI; otherwise prints to stdout. Always exits 0 — perf
# is a soft signal, not a hard CI gate.
#
# Usage: scripts/perf-baseline-check.sh <fresh-report.json>
set -euo pipefail

REPORT="${1:-java/cloud-test-harness/build/reports/perf-baselines/baseline-report.json}"
BASELINE="${BASELINE_FILE:-infra/perf/baselines.json}"

if [[ ! -f "$REPORT" ]]; then
  echo "::warning ::perf-baseline report not found at $REPORT — skipping drift check"
  exit 0
fi
if [[ ! -f "$BASELINE" ]]; then
  echo "::warning ::baseline file not found at $BASELINE — skipping drift check"
  exit 0
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "::warning ::jq is required for drift comparison; skipping"
  exit 0
fi

THRESHOLD=$(jq -r '.driftThresholdPercent // 25' "$BASELINE")
SUMMARY=()

compare() {
  local label="$1" path="$2"
  local fresh baseline
  fresh=$(jq -r "$path // empty" "$REPORT")
  baseline=$(jq -r "$path // empty" "$BASELINE")
  if [[ -z "$fresh" || -z "$baseline" || "$fresh" == "null" || "$baseline" == "null" ]]; then
    return 0
  fi
  local drift_pct
  drift_pct=$(awk -v f="$fresh" -v b="$baseline" 'BEGIN { if (b == 0) { print 0 } else { printf "%.1f", ((f - b) / b) * 100 } }')
  local abs_drift
  abs_drift=$(awk -v d="$drift_pct" 'BEGIN { print (d < 0 ? -d : d) }')
  local marker="ok"
  if awk -v a="$abs_drift" -v t="$THRESHOLD" 'BEGIN { exit !(a > t) }'; then
    marker="DRIFT"
  fi
  SUMMARY+=("| $label | $baseline | $fresh | ${drift_pct}% | $marker |")
  if [[ "$marker" == "DRIFT" ]]; then
    echo "::warning ::perf drift on $label — baseline=$baseline observed=$fresh delta=${drift_pct}% (threshold ${THRESHOLD}%)"
  fi
}

compare "controller cold start (ms)"           ".metrics.controllerColdStartMs.value"
compare "coordination SET+GET p50 (ms)"        ".metrics.coordinationStoreSetGetMs.p50"
compare "coordination SET+GET p95 (ms)"        ".metrics.coordinationStoreSetGetMs.p95"
compare "SSE event latency p50 (ms)"           ".metrics.sseEventLatencyMs.p50"
compare "SSE event latency p95 (ms)"           ".metrics.sseEventLatencyMs.p95"
compare "scheduler tick p50 (ms)"              ".metrics.schedulerTickMs.p50"
compare "scheduler tick p95 (ms)"              ".metrics.schedulerTickMs.p95"

{
  echo "## Performance baselines"
  echo
  echo "Report: \`$REPORT\`  ·  Baseline: \`$BASELINE\`  ·  Threshold: ${THRESHOLD}%"
  echo
  echo "| metric | baseline | observed | drift | status |"
  echo "| --- | ---: | ---: | ---: | :---: |"
  for row in "${SUMMARY[@]}"; do
    echo "$row"
  done
} | tee >(if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then cat >> "$GITHUB_STEP_SUMMARY"; fi)

exit 0
