#!/bin/bash
# verify_retry.sh — Assert Scenario 2 (retry) reduces BACKEND_ERROR vs Scenario 1
# Compares tmp/artifacts/baseline/ vs tmp/artifacts/retry/
# Exit 0 = all assertions PASS, exit 1 = any FAIL

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
S1_DIR="$REPO_ROOT/tmp/artifacts/baseline"
S2_DIR="$REPO_ROOT/tmp/artifacts/retry"

PASS=0
FAIL=0

pass() { echo "PASS $1: $2"; PASS=$((PASS + 1)); }
fail() { echo "FAIL $1: $2"; FAIL=$((FAIL + 1)); }

if [[ ! -d "$S1_DIR" ]]; then
    echo "ERROR: missing $S1_DIR — run ./scripts/run_scenario.sh 1 first"
    exit 1
fi
if [[ ! -d "$S2_DIR" ]]; then
    echo "ERROR: missing $S2_DIR — run ./scripts/run_scenario.sh 2 first"
    exit 1
fi

sum_error_code() {
    local dir="$1" code="$2"
    grep '^a_downstream_errors_total' "$dir"/app-a-*.prom \
        | grep "code=\"${code}\"" \
        | awk '{sum += $2} END {print int(sum+0)}'
}

S1_BACKEND_ERROR=$(sum_error_code "$S1_DIR" "BACKEND_ERROR")
S2_BACKEND_ERROR=$(sum_error_code "$S2_DIR" "BACKEND_ERROR")

# C08: Scenario 1 BACKEND_ERROR > 1000 (failures happen without retry)
if [[ "$S1_BACKEND_ERROR" -gt 1000 ]]; then
    pass "C08" "Scenario 1 BACKEND_ERROR=${S1_BACKEND_ERROR} (> 1000 — failures propagate)"
else
    fail "C08" "Scenario 1 BACKEND_ERROR=${S1_BACKEND_ERROR} should be > 1000"
fi

# C09: Scenario 2 BACKEND_ERROR < 100 (retry absorbs ~90% of errors)
if [[ "$S2_BACKEND_ERROR" -lt 100 ]]; then
    pass "C09" "Scenario 2 BACKEND_ERROR=${S2_BACKEND_ERROR} (< 100 — retry absorbing failures)"
else
    fail "C09" "Scenario 2 BACKEND_ERROR=${S2_BACKEND_ERROR} should be < 100"
fi

# C10: S1 BACKEND_ERROR > S2 BACKEND_ERROR (directional improvement)
if [[ "$S1_BACKEND_ERROR" -gt "$S2_BACKEND_ERROR" ]]; then
    pass "C10" "S1 BACKEND_ERROR (${S1_BACKEND_ERROR}) > S2 BACKEND_ERROR (${S2_BACKEND_ERROR})"
else
    fail "C10" "S1 BACKEND_ERROR (${S1_BACKEND_ERROR}) should be > S2 BACKEND_ERROR (${S2_BACKEND_ERROR})"
fi

echo ""
echo "Results: PASS=${PASS} FAIL=${FAIL}"
[[ "$FAIL" -eq 0 ]]
