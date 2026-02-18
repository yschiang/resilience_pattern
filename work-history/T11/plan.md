# T11 Plan — Verification Scripts (verify_s1.sh / verify_s4.sh)

## Branch
p4-verification-docs (already exists, checked out)

## Artifacts available (confirmed)
### S1 baseline — tmp/artifacts/S1/baseline/app-a-*.prom
- max latency values: 0.781s, 0.0s, 4.023s → max = 4.023s

### S1 resilient — tmp/artifacts/S1/resilient/app-a-*.prom
- max latency values: 0.995s, 0.932s → max = 0.995s
- QUEUE_FULL: 450 + 404 = 854
- CIRCUIT_OPEN: 5256 + 5737 = 10993
- Total fast-fail: 11847

### S4 baseline — tmp/artifacts/S4/baseline/app-a-*.prom
- UNAVAILABLE: 1797

### S4 resilient — tmp/artifacts/S4/resilient/app-a-*.prom
- UNAVAILABLE: 21

## Files to create
1. tests/verify_s1.sh (executable)
2. tests/verify_s4.sh (executable)

## Assertions

### verify_s1.sh
- C01: max(baseline a_downstream_latency_ms_seconds_max) > max(resilient a_downstream_latency_ms_seconds_max)
  - Parse: grep '^a_downstream_latency_ms_seconds_max' | awk '{print $2}' | sort -n | tail -1
  - Compare: awk "BEGIN { exit !($BASE_MAX > $RES_MAX) }"
  - Expected: 4.023 > 0.995 → PASS
- C02: sum(QUEUE_FULL + CIRCUIT_OPEN from S1 resilient) > 100
  - Parse: grep QUEUE_FULL|CIRCUIT_OPEN | awk sum
  - Expected: 11847 > 100 → PASS

### verify_s4.sh
- C05: sum(UNAVAILABLE from S4 baseline) > 100 → 1797 > 100 → PASS
- C06: sum(UNAVAILABLE from S4 resilient) < 100 → 21 < 100 → PASS
- C07: baseline UNAVAILABLE > resilient UNAVAILABLE → 1797 > 21 → PASS

## Script structure
- PASS/FAIL counters
- pass()/fail() helper functions
- Guard: check artifact dirs exist, print error and exit 1 if missing
- Final: print "Results: PASS=N FAIL=N", exit 0 if FAIL=0

## Commit message
feat: Add S1 and S4 verification scripts

## DoD
./tests/verify_s1.sh  → PASS=2 FAIL=0, exit 0
./tests/verify_s4.sh  → PASS=3 FAIL=0, exit 0
