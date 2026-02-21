#!/bin/bash
# verify_failfast.sh — Assert Scenario 3 (CB+bulkhead+deadline) improves over Scenario 1
# Compares tmp/artifacts/baseline/ vs tmp/artifacts/failfast/
# Exit 0 = all assertions PASS, exit 1 = any FAIL

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
S1_DIR="$REPO_ROOT/tmp/artifacts/baseline"
S3_DIR="$REPO_ROOT/tmp/artifacts/failfast"

PASS=0
FAIL=0

pass() { echo "PASS $1: $2"; PASS=$((PASS + 1)); }
fail() { echo "FAIL $1: $2"; FAIL=$((FAIL + 1)); }

if [[ ! -d "$S1_DIR" ]]; then
    echo "ERROR: missing $S1_DIR — run ./scripts/run_scenario.sh baseline first"
    exit 1
fi
if [[ ! -d "$S3_DIR" ]]; then
    echo "ERROR: missing $S3_DIR — run ./scripts/run_scenario.sh failfast first"
    exit 1
fi

# C01: Scenario 1 max latency > Scenario 3 max latency
S1_MAX=$(grep '^a_downstream_latency_ms_seconds_max' "$S1_DIR"/app-a-*.prom \
    | awk '{print $2}' | sort -n | tail -1)
S3_MAX=$(grep '^a_downstream_latency_ms_seconds_max' "$S3_DIR"/app-a-*.prom \
    | awk '{print $2}' | sort -n | tail -1)

if awk "BEGIN { exit !($S1_MAX > $S3_MAX) }"; then
    pass "C01" "S1 max latency ${S1_MAX}s > S3 max latency ${S3_MAX}s (CB sheds load)"
else
    fail "C01" "S1 max latency ${S1_MAX}s should be > S3 max latency ${S3_MAX}s"
fi

# C02: Scenario 3 QUEUE_FULL + CIRCUIT_OPEN > 100 (fail-fast patterns firing)
FAST_FAIL=$(grep '^a_downstream_errors_total' "$S3_DIR"/app-a-*.prom \
    | grep -E 'code="QUEUE_FULL"|code="CIRCUIT_OPEN"' \
    | awk '{sum += $2} END {print int(sum)}')

if [[ "$FAST_FAIL" -gt 100 ]]; then
    pass "C02" "S3 QUEUE_FULL+CIRCUIT_OPEN=${FAST_FAIL} (> 100 — fail-fast active)"
else
    fail "C02" "S3 QUEUE_FULL+CIRCUIT_OPEN=${FAST_FAIL} should be > 100"
fi

echo ""
echo "Results: PASS=${PASS} FAIL=${FAIL}"
[[ "$FAIL" -eq 0 ]]
