# Developer Prompt — T17: verify_scenario{2,3,4}.sh

## Context
GitHub issue #19. Read .clinerules/ before starting. Read tmp/T17/plan.md.
DEPENDS ON #18 (T16) being merged (artifact paths must match new script).

## Task
Create 3 new verify scripts + add deprecated header to 2 old scripts.

---

## 1. tests/verify_scenario2.sh (NEW)

```bash
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
```

---

## 2. tests/verify_scenario3.sh (NEW)

```bash
#!/bin/bash
# verify_scenario3.sh — Assert Scenario 3 (CB+bulkhead+deadline) improves over Scenario 1
# Compares tmp/artifacts/scenario1/ vs tmp/artifacts/scenario3/
# Exit 0 = all assertions PASS, exit 1 = any FAIL

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
S1_DIR="$REPO_ROOT/tmp/artifacts/scenario1"
S3_DIR="$REPO_ROOT/tmp/artifacts/scenario3"

PASS=0
FAIL=0

pass() { echo "PASS $1: $2"; PASS=$((PASS + 1)); }
fail() { echo "FAIL $1: $2"; FAIL=$((FAIL + 1)); }

if [[ ! -d "$S1_DIR" ]]; then
    echo "ERROR: missing $S1_DIR — run ./scripts/run_scenario.sh 1 first"
    exit 1
fi
if [[ ! -d "$S3_DIR" ]]; then
    echo "ERROR: missing $S3_DIR — run ./scripts/run_scenario.sh 3 first"
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
```

---

## 3. tests/verify_scenario4.sh (NEW)

```bash
#!/bin/bash
# verify_scenario4.sh — Assert Scenario 4 (keepalive+pool) self-heals after TCP reset
# Parses tmp/artifacts/scenario4/
# Exit 0 = all assertions PASS, exit 1 = any FAIL

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
S4_DIR="$REPO_ROOT/tmp/artifacts/scenario4"

PASS=0
FAIL=0

pass() { echo "PASS $1: $2"; PASS=$((PASS + 1)); }
fail() { echo "FAIL $1: $2"; FAIL=$((FAIL + 1)); }

if [[ ! -d "$S4_DIR" ]]; then
    echo "ERROR: missing $S4_DIR — run ./scripts/run_scenario.sh 4 first"
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
```

---

## 4. Add deprecated headers to old scripts

tests/verify_s1.sh — add at line 1 (before existing content):
```bash
# DEPRECATED: Use tests/verify_scenario3.sh instead (P5 learning roadmap)
# This script references old artifact paths (tmp/artifacts/S1/baseline|resilient)
```

tests/verify_s4.sh — add at line 1:
```bash
# DEPRECATED: Use tests/verify_scenario4.sh instead (P5 learning roadmap)
# This script references old artifact paths (tmp/artifacts/S4/baseline|resilient)
```

---

## DoD Proof Commands (save to tmp/T17/proof.txt)
```bash
# Proof 1: 3 new scripts exist
ls tests/verify_scenario{2,3,4}.sh | wc -l
# Expected: 3

# Proof 2: syntax check all three
bash -n tests/verify_scenario2.sh && echo "S2 OK"
bash -n tests/verify_scenario3.sh && echo "S3 OK"
bash -n tests/verify_scenario4.sh && echo "S4 OK"
# Expected: S2 OK, S3 OK, S4 OK

# Proof 3: deprecated headers present
grep "DEPRECATED" tests/verify_s1.sh
grep "DEPRECATED" tests/verify_s4.sh
```

## Commit Message (save to tmp/T17/commit_msg.txt)
```
test: add verify_scenario{2,3,4}.sh for learning roadmap assertions

- verify_scenario2.sh: C08/C09/C10 — retry reduces RATE_LIMITED errors
- verify_scenario3.sh: C01/C02 — CB+bulkhead reduce latency and shed load
- verify_scenario4.sh: C05/C06/C07 — self-heal after TCP reset
- Mark old verify_s1.sh and verify_s4.sh as deprecated

Fixes #19
```

## After Implementing
Follow 10_workflow_implement_task.md Steps 5-8.
Do NOT merge your own PR. Post proof to issue, wait for architect review.
