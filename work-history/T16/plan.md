# T16 Architect Plan — Scripts: run_scenario.sh restructure

## Problem
Current run_scenario.sh takes <scenario> <mode> (S1|S4, baseline|resilient).
The new model has 4 numbered scenarios, each self-contained — no "mode" concept.
Artifacts go to tmp/artifacts/scenario<N>/ (not S1/baseline, S4/resilient).

## Design Decisions

### New interface
./scripts/run_scenario.sh <1|2|3|4>

Validation: arg must be 1, 2, 3, or 4.
Artifacts: tmp/artifacts/scenario<N>/

### Values composition
helm upgrade --install resilience-demo chart \
  -f chart/values-common.yaml \
  -f chart/values-scenario<N>.yaml

### Per-scenario fortio params
| Scenario | QPS | C  | T   | Fault at t=15s? |
|---|---|---|---|---|
| 1 | 200 | 50 | 60s | No |
| 2 | 200 | 50 | 60s | No |
| 3 | 200 | 80 | 60s | No (need overload) |
| 4 | 200 | 50 | 60s | Yes (iptables reset) |

### Scenario 4 fault injection
- Same logic as old S4 resilient: run fortio in background, sleep 15, inject
- Calls scripts/inject_s4.sh (existing script, unchanged)
- inject_s4.sh targets first app-a pod

### Artifact collection
- Same as existing: A pod .prom + .log, B pod .metrics, fortio.txt
- 8 files expected per scenario (1 fortio + 2 A prom + 2 A log + 3 B metrics)

### Old interface removal
- Remove SCENARIO/MODE args entirely
- Keep inject_s4.sh unchanged (no reason to touch it)
- Old artifact dirs (tmp/artifacts/S1/, tmp/artifacts/S4/) remain on disk (gitignored)

## Files Changed
- scripts/run_scenario.sh (rewrite)

## DoD
1. bash -n scripts/run_scenario.sh → no errors (syntax check)
2. grep "Usage.*1|2|3|4" scripts/run_scenario.sh → matches
3. ./scripts/run_scenario.sh 99 2>&1 | grep "Error" → exits with error
