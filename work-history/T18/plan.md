# T18 Architect Plan — Docs: plan2.md + README + runbook

## Problem
README leads with "baseline vs resilient" binary framing which doesn't teach
which pattern does what. Runbook references old S1/S4 baseline/resilient commands.
Need a new design document (plan2.md) as the authoritative roadmap spine.

## Design Decisions

### docs/plan2.md (new file)
- The "spine" document: captures P5 learning roadmap design rationale
- Content: 4-scenario table, coverage matrix, pattern contribution per scenario
- Audience: future maintainers and architects, explains the why not just the what
- Include: the anti-pattern lesson (Scenario 2 retry makes overload worse)

### README.md (rewrite)
Structure:
1. Hero: 4-scenario roadmap table (scenario, name, patterns added, failure mode, lesson)
2. Coverage matrix as ASCII table (failure mode × scenario)
3. Quick start: ./scripts/run_scenario.sh 1-4
4. Pattern detail per scenario (what each pattern does and why)
5. Verify section (verify_scenario2/3/4.sh)

Keep the existing detailed pattern explanation sections but reorganize
around the scenario progression.

### docs/runbook.md (update)
Update "Drill" section drill commands:
  Old: ./scripts/run_scenario.sh S1 baseline
  New: ./scripts/run_scenario.sh 1

Update all 4 drill entries:
  ./scripts/run_scenario.sh 1   # baseline
  ./scripts/run_scenario.sh 2   # +retry+idempotency
  ./scripts/run_scenario.sh 3   # +deadline+bulkhead+CB
  ./scripts/run_scenario.sh 4   # +keepalive+pool (+TCP reset)

Update verification section:
  ./tests/verify_scenario2.sh
  ./tests/verify_scenario3.sh
  ./tests/verify_scenario4.sh

## Files Changed
- docs/plan2.md (new)
- README.md (rewrite sections)
- docs/runbook.md (update drill commands)

## DoD
1. test -f docs/plan2.md && wc -l docs/plan2.md → file exists, > 50 lines
2. grep "run_scenario.sh 1\|run_scenario.sh 2\|run_scenario.sh 3\|run_scenario.sh 4" docs/runbook.md | wc -l → 4
3. grep -c "Scenario" README.md → > 4
