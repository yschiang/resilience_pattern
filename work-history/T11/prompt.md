# Developer Prompt — T11 + T12 (P4 Final)

## Context

- Branch: create `p4-t11-verify-docs` from master
- Issues: #13 (T11 verify scripts), #14 (T12 docs)
- All 4 artifact sets exist in `tmp/artifacts/`: S1/baseline, S1/resilient, S4/baseline, S4/resilient
- `tests/` directory exists (empty)
- `docs/plan.md` already exists and is accurate

---

## Task A — T11: Verification Scripts (issue #13)

Create two executable scripts in `tests/`.

---

### `tests/verify_s1.sh`

```bash
#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
ARTIFACTS="$REPO_ROOT/tmp/artifacts"

PASS=0; FAIL=0

pass() { echo "PASS: $1"; ((PASS++)); }
fail() { echo "FAIL: $1 — $2"; ((FAIL++)); }

# Max latency across all A pod prom files in a directory
max_latency() {
  grep "^a_downstream_latency_ms_seconds_max" "$1"/*.prom 2>/dev/null \
    | awk '{if ($NF+0 > max) max = $NF+0} END {print max+0}'
}

# Sum an error code across all A pod prom files
sum_errors() {
  grep "a_downstream_errors_total{code=\"$2\"" "$1"/*.prom 2>/dev/null \
    | awk '{sum += $NF} END {print int(sum+0)}'
}

echo "==> verify_s1.sh: S1 baseline vs resilient"
echo ""

# C01: resilient max latency < baseline max latency
BL_MAX=$(max_latency "$ARTIFACTS/S1/baseline")
RS_MAX=$(max_latency "$ARTIFACTS/S1/resilient")
echo "    S1 baseline max latency : ${BL_MAX}s"
echo "    S1 resilient max latency: ${RS_MAX}s"
if (( $(echo "$BL_MAX > $RS_MAX" | bc -l) )); then
  pass "C01: resilient max latency (${RS_MAX}s) < baseline (${BL_MAX}s)"
else
  fail "C01: resilient max latency" "expected resilient (${RS_MAX}s) < baseline (${BL_MAX}s)"
fi

# C02: resilient has significant QUEUE_FULL + CIRCUIT_OPEN (fail-fast active)
RS_QF=$(sum_errors "$ARTIFACTS/S1/resilient" "QUEUE_FULL")
RS_CO=$(sum_errors "$ARTIFACTS/S1/resilient" "CIRCUIT_OPEN")
RS_FAILFAST=$(( RS_QF + RS_CO ))
echo "    S1 resilient QUEUE_FULL=${RS_QF} CIRCUIT_OPEN=${RS_CO} total=${RS_FAILFAST}"
if [[ $RS_FAILFAST -gt 100 ]]; then
  pass "C02: resilient fail-fast errors (${RS_FAILFAST}) > 100"
else
  fail "C02: resilient fail-fast errors" "expected >100 QUEUE_FULL+CIRCUIT_OPEN, got ${RS_FAILFAST}"
fi

echo ""
echo "Results: PASS=$PASS FAIL=$FAIL"
[[ $FAIL -eq 0 ]] && echo "PASS" && exit 0 || echo "FAIL" && exit 1
```

---

### `tests/verify_s4.sh`

```bash
#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
ARTIFACTS="$REPO_ROOT/tmp/artifacts"

PASS=0; FAIL=0

pass() { echo "PASS: $1"; ((PASS++)); }
fail() { echo "FAIL: $1 — $2"; ((FAIL++)); }

sum_errors() {
  grep "a_downstream_errors_total{code=\"$2\"" "$1"/*.prom 2>/dev/null \
    | awk '{sum += $NF} END {print int(sum+0)}'
}

echo "==> verify_s4.sh: S4 baseline vs resilient"
echo ""

BL_UNAVAIL=$(sum_errors "$ARTIFACTS/S4/baseline" "UNAVAILABLE")
RS_UNAVAIL=$(sum_errors "$ARTIFACTS/S4/resilient" "UNAVAILABLE")
echo "    S4 baseline UNAVAILABLE : $BL_UNAVAIL"
echo "    S4 resilient UNAVAILABLE: $RS_UNAVAIL"

# C05: baseline had a clear UNAVAILABLE burst (>100)
if [[ $BL_UNAVAIL -gt 100 ]]; then
  pass "C05: baseline UNAVAILABLE burst (${BL_UNAVAIL}) > 100"
else
  fail "C05: baseline UNAVAILABLE burst" "expected >100, got ${BL_UNAVAIL}"
fi

# C06: resilient recovered (UNAVAILABLE < 100)
if [[ $RS_UNAVAIL -lt 100 ]]; then
  pass "C06: resilient UNAVAILABLE (${RS_UNAVAIL}) < 100 — self-heal effective"
else
  fail "C06: resilient UNAVAILABLE" "expected <100, got ${RS_UNAVAIL}"
fi

# C07: baseline UNAVAILABLE > resilient UNAVAILABLE (directional)
if [[ $BL_UNAVAIL -gt $RS_UNAVAIL ]]; then
  pass "C07: baseline (${BL_UNAVAIL}) > resilient (${RS_UNAVAIL}) — directional improvement"
else
  fail "C07: directional improvement" "expected baseline > resilient"
fi

echo ""
echo "Results: PASS=$PASS FAIL=$FAIL"
[[ $FAIL -eq 0 ]] && echo "PASS" && exit 0 || echo "FAIL" && exit 1
```

---

### Make both executable

```bash
chmod +x tests/verify_s1.sh tests/verify_s4.sh
```

---

### DoD for T11 (issue #13)

```bash
# 1. Scripts exist and are executable
ls -la tests/verify_s1.sh tests/verify_s4.sh
# Expected: -rwxr-xr-x for both

# 2. verify_s1.sh passes
./tests/verify_s1.sh
# Expected: prints PASS=2 FAIL=0, final line "PASS", exits 0
echo $?
# Expected: 0

# 3. verify_s4.sh passes
./tests/verify_s4.sh
# Expected: prints PASS=3 FAIL=0, final line "PASS", exits 0
echo $?
# Expected: 0
```

---

## Task B — T12: Docs Packaging (issue #14)

Create `docs/runbook.md`. Do NOT edit `docs/plan.md` (it is accurate).

---

### `docs/runbook.md`

Write a runbook with these sections:

**1. Prerequisites**
- kind cluster named `resilience-pattern` running
- kubectl, helm, docker available in PATH
- Images built: `docker build -t app-a:dev apps/app-a/ && docker build -t app-b:dev apps/app-b/`
- Images loaded: `kind load docker-image app-a:dev app-b:dev --name resilience-pattern`

**2. One-Command Drill (run all 4 scenarios)**
```bash
./scripts/run_scenario.sh S1 baseline
./scripts/run_scenario.sh S1 resilient
./scripts/run_scenario.sh S4 baseline
./scripts/run_scenario.sh S4 resilient
```
Each produces 8 artifacts in `tmp/artifacts/<SCENARIO>/<MODE>/`.

**3. Verify Results**
```bash
./tests/verify_s1.sh   # exits 0 = PASS
./tests/verify_s4.sh   # exits 0 = PASS
```

**4. Expected Evidence Summary**

| Scenario | Mode | Key evidence |
|---|---|---|
| S1 | baseline | max latency ~4s (queueing collapse) |
| S1 | resilient | max latency ~1s; QUEUE_FULL+CIRCUIT_OPEN > 10000 (fail-fast) |
| S4 | baseline | UNAVAILABLE ~1797 on injected pod (correlated burst) |
| S4 | resilient | UNAVAILABLE ~21 on injected pod (−99%, self-heal working) |

**5. Troubleshooting**

- `ImagePullBackOff`: rebuild and re-load docker images
- `inject_s4.sh iptables failed`: NET_ADMIN capability missing — check app-a-deployment.yaml securityContext
- `fortio-load pod stuck`: `kubectl delete pod fortio-load -n demo` and retry
- `verify_*.sh exits 1`: re-run the failing scenario first, then re-verify

---

### DoD for T12 (issue #14)

```bash
# 1. Plan doc has accurate helm command
grep "values-s1" docs/plan.md | head -1
# Expected: line containing helm upgrade with values-s1.yaml

# 2. Runbook references run_scenario.sh for all modes
grep -E "run_scenario.sh S1 baseline|run_scenario.sh S4 resilient" docs/*.md | wc -l
# Expected: >= 2
```

---

## Commit Strategy

Two separate commits, one PR:

**Commit 1 — T11:**
Save to `tmp/T11/commit_msg.txt`:
```
feat: Add S1/S4 verification scripts (verify_s1.sh, verify_s4.sh)

- tests/verify_s1.sh: asserts resilient max latency < baseline;
  QUEUE_FULL+CIRCUIT_OPEN > 100 (fail-fast active)
- tests/verify_s4.sh: asserts baseline UNAVAILABLE burst > 100;
  resilient UNAVAILABLE < 100 (self-heal effective)
- Exit 0 = PASS, exit 1 = FAIL with per-check output

Closes #13
```

**Commit 2 — T12:**
Save to `tmp/T11/commit_msg_t12.txt` (T12 goes in same tmp dir):
```
docs: Add runbook.md (one-command drill + expected evidence)

- docs/runbook.md: prerequisites, 4-scenario drill, verify commands,
  evidence summary table, troubleshooting guide

Closes #14
```

---

## PR

Title: `feat: P4 verification scripts + runbook (T11 + T12)`
Body: show verify_s1.sh and verify_s4.sh output
Closes: #13, #14

Do not merge your own PR.

---

## Invariants (DO NOT CHANGE)

- Namespace: `demo`, A=2, B=3
- No `Co-authored-by:` in commits
- Conventional commit prefix required
