# T12 Review — Runbook DoD

## DoD check 1: values-s1 in plan.md
```bash
grep "values-s1" docs/plan.md | head -1
```
Output:
```
- `chart/values-s1.yaml` (B_DELAY_MS=200 + load)
```
Result: non-empty ✓

## DoD check 2: run_scenario commands across docs
```bash
grep -E "run_scenario.sh S1 baseline|run_scenario.sh S4 resilient" docs/*.md | wc -l
```
Output: `8` (≥ 2 required) ✓

Sources:
- docs/plan.md lines 287, 290 (run_scenario.sh S1 baseline, S4 resilient)
- docs/runbook.md drill section + verify section

## Commit
a6f9949 docs: Add runbook with 4-scenario drill and verification steps
