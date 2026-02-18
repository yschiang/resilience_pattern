#!/bin/bash
# verify_s1.sh — Assert S1 resilient improves over baseline
# Parses tmp/artifacts/S1/{baseline,resilient}/app-a-*.prom
# Exit 0 = all assertions PASS, exit 1 = any FAIL

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
BASE_DIR="$REPO_ROOT/tmp/artifacts/S1/baseline"
RES_DIR="$REPO_ROOT/tmp/artifacts/S1/resilient"

PASS=0
FAIL=0

pass() { echo "PASS $1: $2"; PASS=$((PASS + 1)); }
fail() { echo "FAIL $1: $2"; FAIL=$((FAIL + 1)); }

# Require artifact directories
if [[ ! -d "$BASE_DIR" ]]; then
    echo "ERROR: missing $BASE_DIR — run ./scripts/run_scenario.sh S1 baseline first"
    exit 1
fi
if [[ ! -d "$RES_DIR" ]]; then
    echo "ERROR: missing $RES_DIR — run ./scripts/run_scenario.sh S1 resilient first"
    exit 1
fi

# C01: baseline max latency > resilient max latency
# Source: a_downstream_latency_ms_seconds_max gauge (max across all A pods)
BASE_MAX=$(grep '^a_downstream_latency_ms_seconds_max' "$BASE_DIR"/app-a-*.prom \
    | awk '{print $2}' | sort -n | tail -1)
RES_MAX=$(grep '^a_downstream_latency_ms_seconds_max' "$RES_DIR"/app-a-*.prom \
    | awk '{print $2}' | sort -n | tail -1)

if awk "BEGIN { exit !($BASE_MAX > $RES_MAX) }"; then
    pass "C01" "baseline max latency ${BASE_MAX}s > resilient max latency ${RES_MAX}s"
else
    fail "C01" "baseline max latency ${BASE_MAX}s should be > resilient max latency ${RES_MAX}s"
fi

# C02: resilient QUEUE_FULL + CIRCUIT_OPEN > 100 (fail-fast patterns firing)
FAST_FAIL=$(grep '^a_downstream_errors_total' "$RES_DIR"/app-a-*.prom \
    | grep -E 'code="QUEUE_FULL"|code="CIRCUIT_OPEN"' \
    | awk '{sum += $2} END {print int(sum)}')

if [[ "$FAST_FAIL" -gt 100 ]]; then
    pass "C02" "resilient QUEUE_FULL+CIRCUIT_OPEN=${FAST_FAIL} (> 100)"
else
    fail "C02" "resilient QUEUE_FULL+CIRCUIT_OPEN=${FAST_FAIL} should be > 100"
fi

echo ""
echo "Results: PASS=${PASS} FAIL=${FAIL}"
[[ "$FAIL" -eq 0 ]]
