# Architect Plan — T11 + T12 (P4 Final)

**Date:** 2026-02-18
**Issues:** #13 (T11 verify scripts), #14 (T12 docs)
**Milestone:** P4 Verification + Docs

---

## Artifact Reality Check

All 4 artifact sets are present and valid:

| Set | Files | Key Numbers |
|---|---|---|
| S1/baseline | 10 (extra files from re-run, harmless) | max latency = 4.023s |
| S1/resilient | 8 | max latency = 0.995s; QUEUE_FULL=854; CIRCUIT_OPEN=10993 |
| S4/baseline | 8 | UNAVAILABLE = 1797 |
| S4/resilient | 8 | UNAVAILABLE = 21 |

---

## T11 Design

### Metric extraction patterns (Prometheus text format)

```bash
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
```

### verify_s1.sh — 2 assertions

| # | Assertion | Expected values | Pass condition |
|---|---|---|---|
| C01 | baseline max latency > resilient max latency | 4.023s vs 0.995s | `bl_max > rs_max` (bc -l float compare) |
| C02 | resilient QUEUE_FULL + CIRCUIT_OPEN > 100 | 854+10993=11847 | integer comparison |

Both thresholds have large margins — safe against run-to-run variance.

### verify_s4.sh — 3 assertions

| # | Assertion | Expected values | Pass condition |
|---|---|---|---|
| C05 | baseline UNAVAILABLE > 100 | 1797 | integer > 100 |
| C06 | resilient UNAVAILABLE < 100 | 21 | integer < 100 |
| C07 | baseline UNAVAILABLE > resilient UNAVAILABLE | 1797 > 21 | integer comparison |

### Script structure

Both scripts follow the same pattern:
- `ARTIFACTS_DIR` defaults to `tmp/artifacts` relative to repo root
- Helper functions: `max_latency()`, `sum_errors()`
- `check_pass` / `check_fail` counters
- Final: print "PASS" or "FAIL" with counts, exit 0 or 1

---

## T12 Design

`docs/plan.md` is accurate — helm commands and artifact paths are up to date.

Create `docs/runbook.md`:
1. Prerequisites (kind, kubectl, helm, docker)
2. One-command drill (4 scenarios)
3. Expected artifact counts and key evidence numbers
4. verify commands (tests/verify_s1.sh, tests/verify_s4.sh)
5. Troubleshooting (common issues: image not found, NET_ADMIN permission, fortio pod stuck)

---

## PR Strategy

One PR for T11 + T12 together (both are finishing touches, low risk):
- `tests/verify_s1.sh` (new, executable)
- `tests/verify_s4.sh` (new, executable)
- `docs/runbook.md` (new)
- Closes #13 and #14
