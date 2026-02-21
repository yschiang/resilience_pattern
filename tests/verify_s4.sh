#!/bin/bash
# DEPRECATED: Use tests/verify_selfheal.sh instead (P5 learning roadmap)
# This script references old artifact paths (tmp/artifacts/S4/baseline|resilient)
# verify_s4.sh — Assert S4 resilient recovers vs baseline
# Parses tmp/artifacts/S4/{baseline,resilient}/app-a-*.prom
# Exit 0 = all assertions PASS, exit 1 = any FAIL

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
BASE_DIR="$REPO_ROOT/tmp/artifacts/S4/baseline"
RES_DIR="$REPO_ROOT/tmp/artifacts/S4/resilient"

PASS=0
FAIL=0

pass() { echo "PASS $1: $2"; PASS=$((PASS + 1)); }
fail() { echo "FAIL $1: $2"; FAIL=$((FAIL + 1)); }

# Require artifact directories
if [[ ! -d "$BASE_DIR" ]]; then
    echo "ERROR: missing $BASE_DIR — run ./scripts/run_scenario.sh S4 baseline first"
    exit 1
fi
if [[ ! -d "$RES_DIR" ]]; then
    echo "ERROR: missing $RES_DIR — run ./scripts/run_scenario.sh S4 resilient first"
    exit 1
fi

# Sum UNAVAILABLE errors across all A pods for a given directory
sum_unavailable() {
    local dir="$1"
    grep '^a_downstream_errors_total' "$dir"/app-a-*.prom \
        | grep 'code="UNAVAILABLE"' \
        | awk '{sum += $2} END {print int(sum+0)}'
}

BASE_UNAVAIL=$(sum_unavailable "$BASE_DIR")
RES_UNAVAIL=$(sum_unavailable "$RES_DIR")

# C05: baseline UNAVAILABLE > 100 (injection caused errors)
if [[ "$BASE_UNAVAIL" -gt 100 ]]; then
    pass "C05" "S4 baseline UNAVAILABLE=${BASE_UNAVAIL} (> 100)"
else
    fail "C05" "S4 baseline UNAVAILABLE=${BASE_UNAVAIL} should be > 100"
fi

# C06: resilient UNAVAILABLE < 100 (fail-fast + self-heal contained blast)
if [[ "$RES_UNAVAIL" -lt 100 ]]; then
    pass "C06" "S4 resilient UNAVAILABLE=${RES_UNAVAIL} (< 100)"
else
    fail "C06" "S4 resilient UNAVAILABLE=${RES_UNAVAIL} should be < 100"
fi

# C07: baseline UNAVAILABLE > resilient UNAVAILABLE (directional improvement)
if [[ "$BASE_UNAVAIL" -gt "$RES_UNAVAIL" ]]; then
    pass "C07" "baseline UNAVAILABLE (${BASE_UNAVAIL}) > resilient UNAVAILABLE (${RES_UNAVAIL})"
else
    fail "C07" "baseline UNAVAILABLE (${BASE_UNAVAIL}) should be > resilient UNAVAILABLE (${RES_UNAVAIL})"
fi

echo ""
echo "Results: PASS=${PASS} FAIL=${FAIL}"
[[ "$FAIL" -eq 0 ]]
