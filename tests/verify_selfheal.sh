#!/bin/bash
# verify_selfheal.sh — Assert Scenario 4 (keepalive+pool) self-heals after TCP reset
# Parses tmp/artifacts/selfheal/
# Exit 0 = all assertions PASS, exit 1 = any FAIL

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
S4_DIR="$REPO_ROOT/tmp/artifacts/selfheal"

PASS=0
FAIL=0

pass() { echo "PASS $1: $2"; PASS=$((PASS + 1)); }
fail() { echo "FAIL $1: $2"; FAIL=$((FAIL + 1)); }

if [[ ! -d "$S4_DIR" ]]; then
    echo "ERROR: missing $S4_DIR — run ./scripts/run_scenario.sh selfheal first"
    exit 1
fi

sum_error_code() {
    local dir="$1" code="$2"
    grep '^a_downstream_errors_total' "$dir"/app-a-*.prom \
        | grep "code=\"${code}\"" \
        | awk '{sum += $2} END {print int(sum+0)}'
}

sum_success() {
    local dir="$1"
    grep '^a_downstream_calls_total' "$dir"/app-a-*.prom \
        | grep 'code="SUCCESS"' \
        | awk '{sum += $2} END {print int(sum+0)}'
}

S4_UNAVAIL=$(sum_error_code "$S4_DIR" "UNAVAILABLE")
S4_SUCCESS=$(sum_success "$S4_DIR")

# C05: Scenario 4 UNAVAILABLE > 50 (fault injection caused some errors)
if [[ "$S4_UNAVAIL" -gt 50 ]]; then
    pass "C05" "S4 UNAVAILABLE=${S4_UNAVAIL} (> 50 — TCP reset errors visible)"
else
    fail "C05" "S4 UNAVAILABLE=${S4_UNAVAIL} should be > 50 (fault injection may not have fired)"
fi

# C06: Scenario 4 SUCCESS > 10000 (majority of traffic succeeded — self-heal worked)
if [[ "$S4_SUCCESS" -gt 10000 ]]; then
    pass "C06" "S4 SUCCESS=${S4_SUCCESS} (> 10000 — self-heal confirmed)"
else
    fail "C06" "S4 SUCCESS=${S4_SUCCESS} should be > 10000"
fi

# C07: UNAVAILABLE < SUCCESS / 10 (errors are small fraction — not sustained outage)
THRESHOLD=$((S4_SUCCESS / 10))
if [[ "$S4_UNAVAIL" -lt "$THRESHOLD" ]]; then
    pass "C07" "S4 UNAVAILABLE (${S4_UNAVAIL}) < 10% of SUCCESS (${S4_SUCCESS}) — contained blast"
else
    fail "C07" "S4 UNAVAILABLE (${S4_UNAVAIL}) should be < 10% of SUCCESS (${S4_SUCCESS})"
fi

echo ""
echo "Results: PASS=${PASS} FAIL=${FAIL}"
[[ "$FAIL" -eq 0 ]]
