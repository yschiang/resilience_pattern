# T12 Plan — docs/runbook.md

## Branch
p4-verification-docs (same branch as T11, second commit)

## Files to create
- docs/runbook.md (new file, do NOT edit docs/plan.md)

## DoD checks
1. grep "values-s1" docs/plan.md | head -1
   → Already present at line 142/151 in plan.md — no edit needed
2. grep -E "run_scenario.sh S1 baseline|run_scenario.sh S4 resilient" docs/*.md | wc -l
   → plan.md already has both (lines 287, 290) = 2 occurrences
   → runbook.md will add ≥2 more → total ≥ 4 → PASS

## Runbook sections
1. Prerequisites
   - kind cluster running (kind create cluster)
   - Images built and loaded: app-a:dev, app-b:dev
   - Helm + kubectl + fortio available
   - Namespace: demo (created by run_scenario.sh)

2. Four-command drill
   ./scripts/run_scenario.sh S1 baseline
   ./scripts/run_scenario.sh S1 resilient
   ./scripts/run_scenario.sh S4 baseline
   ./scripts/run_scenario.sh S4 resilient

3. Expected evidence table
   | Scenario | Key metric | Baseline | Resilient |
   | S1 | max latency | ~4s | ~1s (deadline-capped) |
   | S1 | CIRCUIT_OPEN | 0 | >10000 |
   | S4 | UNAVAILABLE | >1000 | <100 |

4. Verify commands
   ./tests/verify_s1.sh
   ./tests/verify_s4.sh

5. Troubleshooting
   - Pods not ready: kubectl rollout restart deployment/app-a -n demo
   - Old image: kind load docker-image app-a:dev + kubectl rollout restart
   - fortio not found: image=fortio/fortio pulled automatically by kubectl run
   - NET_ADMIN missing: chart/templates/app-a-deployment.yaml must have capabilities.add NET_ADMIN

## Commit message
docs: Add runbook with 4-scenario drill and verification steps
