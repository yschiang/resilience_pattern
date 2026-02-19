#!/bin/bash
# verify_scenario2.sh — Assert Scenario 2 (retry) reduces RATE_LIMITED vs Scenario 1
# Compares tmp/artifacts/scenario1/ vs tmp/artifacts/scenario2/
# Exit 0 = all assertions PASS, exit 1 = any FAIL

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
S1_DIR="$REPO_ROOT/tmp/artifacts/scenario1"
S2_DIR="$REPO_ROOT/tmp/artifacts/scenario2"

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

S1_RATE_LIMITED=$(sum_error_code "$S1_DIR" "RATE_LIMITED")
S2_RATE_LIMITED=$(sum_error_code "$S2_DIR" "RATE_LIMITED")

# C08: Scenario 1 RATE_LIMITED > 1000 (failures happen without retry)
if [[ "$S1_RATE_LIMITED" -gt 1000 ]]; then
    pass "C08" "Scenario 1 RATE_LIMITED=${S1_RATE_LIMITED} (> 1000 — failures propagate)"
else
    fail "C08" "Scenario 1 RATE_LIMITED=${S1_RATE_LIMITED} should be > 1000"
fi

# C09: Scenario 2 RATE_LIMITED < 100 (retry absorbs ~90% of errors)
if [[ "$S2_RATE_LIMITED" -lt 100 ]]; then
    pass "C09" "Scenario 2 RATE_LIMITED=${S2_RATE_LIMITED} (< 100 — retry absorbing failures)"
else
    fail "C09" "Scenario 2 RATE_LIMITED=${S2_RATE_LIMITED} should be < 100"
fi

# C10: S1 RATE_LIMITED > S2 RATE_LIMITED (directional improvement)
if [[ "$S1_RATE_LIMITED" -gt "$S2_RATE_LIMITED" ]]; then
    pass "C10" "S1 RATE_LIMITED (${S1_RATE_LIMITED}) > S2 RATE_LIMITED (${S2_RATE_LIMITED})"
else
    fail "C10" "S1 RATE_LIMITED (${S1_RATE_LIMITED}) should be > S2 RATE_LIMITED (${S2_RATE_LIMITED})"
fi

echo ""
echo "Results: PASS=${PASS} FAIL=${FAIL}"
[[ "$FAIL" -eq 0 ]]
