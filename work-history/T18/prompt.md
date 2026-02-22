# Developer Prompt — T18: Docs — plan2.md + README + runbook

## Context
GitHub issue #20. Read .clinerules/ before starting. Read tmp/T18/plan.md.
DEPENDS ON all T13-T17 (#15-#19) being merged.
This is documentation only — no code changes.

## Task
Create docs/plan2.md, rewrite README.md, update docs/runbook.md.

---

## 1. docs/plan2.md (NEW FILE)

Create a full roadmap design document. Structure:

```markdown
# P5 Learning Roadmap — Design Document

## Purpose
[Explain why: binary baseline/resilient doesn't teach WHICH pattern does WHAT]

## The Four Scenarios

| # | Name | Patterns added | Failure mode | Key lesson |
|---|---|---|---|---|
| 1 | Baseline | none | FAIL_RATE=0.3 | Raw failure propagates |
| 2 | +Retry+Idempotency | gRPC retry, dedup in B | same | Retryable errors drop 30%→3% |
| 3 | +Deadline+Bulkhead+CB | deadline, semaphore, Resilience4j CB | + B_DELAY_MS=200 | Overload cascade severed |
| 4 | +Keepalive+ChannelPool | keepalive, channel pool | + iptables tcp-reset | Connection failure self-heals |

## Coverage Matrix

| Failure mode | Scenario 1 | Scenario 2 | Scenario 3 | Scenario 4 |
|---|---|---|---|---|
| RESOURCE_EXHAUSTED (30%) | ❌ | ✅ | ✅ | ✅ |
| Slow B / overload | ❌ | ❌ worse | ✅ | ✅ |
| TCP connection reset | ❌ | ❌ | ❌ slow | ✅ |

## The Anti-Pattern Lesson (Scenario 2 → 3)
[Explain: retry without circuit breaker amplifies load under slow B]
[This is the most important distributed systems lesson in the demo]

## Pattern Inventory
[Map each pattern to scenario and file]

## Verification Scripts
[Map verify_scenario2/3/4.sh to assertions]

## Architecture Invariants
[A=2 pods, B=3 pods, ports, etc.]

## Execution Order
[T13 → T14 → T15 → T16 → T17 → T18 and why]
```

Target: > 80 lines, substantive prose.

---

## 2. README.md (REWRITE)

Lead with 4-scenario roadmap table. Make coverage matrix the hero diagram.

Structure:
1. Title + one-line description
2. The Four Scenarios (table)
3. Coverage Matrix (ASCII table, primary visual)
4. Quick Start
   ```bash
   ./scripts/run_scenario.sh 1   # baseline
   ./scripts/run_scenario.sh 2   # +retry+idempotency
   ./scripts/run_scenario.sh 3   # +deadline+bulkhead+CB
   ./scripts/run_scenario.sh 4   # +keepalive+pool (+TCP reset)
   ```
5. Per-scenario detail (what each pattern does and why)
6. Verify section
   ```bash
   ./tests/verify_scenario2.sh
   ./tests/verify_scenario3.sh
   ./tests/verify_scenario4.sh
   ```
7. Architecture (A=2, B=3, ports)

Keep existing pattern explanation prose but reorganize around scenario flow.

---

## 3. docs/runbook.md (UPDATE)

Find the drill section and update commands:

Old:
```bash
./scripts/run_scenario.sh S1 baseline
./scripts/run_scenario.sh S1 resilient
./scripts/run_scenario.sh S4 baseline
./scripts/run_scenario.sh S4 resilient
```

New:
```bash
./scripts/run_scenario.sh 1   # baseline — raw failure
./scripts/run_scenario.sh 2   # +retry+idempotency
./scripts/run_scenario.sh 3   # +deadline+bulkhead+CB (slow B)
./scripts/run_scenario.sh 4   # +keepalive+pool (+TCP reset)
```

Update verification section:
```bash
./tests/verify_scenario2.sh   # PASS=3 FAIL=0
./tests/verify_scenario3.sh   # PASS=2 FAIL=0
./tests/verify_scenario4.sh   # PASS=3 FAIL=0
```

---

## DoD Proof Commands (save to tmp/T18/proof.txt)
```bash
# Proof 1: plan2.md exists and has substance
test -f docs/plan2.md && wc -l docs/plan2.md
# Expected: > 50 lines

# Proof 2: runbook has all 4 new scenario commands
grep "run_scenario.sh [1-4]" docs/runbook.md | wc -l
# Expected: >= 4

# Proof 3: README references all 4 scenarios
grep -c "Scenario [1-4]" README.md
# Expected: >= 4
```

## Commit Message (save to tmp/T18/commit_msg.txt)
```
docs: add plan2.md roadmap, rewrite README with 4-scenario structure

- plan2.md captures P5 learning roadmap design (anti-pattern lesson, coverage matrix)
- README leads with 4-scenario table and coverage matrix as hero diagram
- runbook updated: drill commands use run_scenario.sh 1-4

Fixes #20
```

## After Implementing
Follow 10_workflow_implement_task.md Steps 5-8.
Direct commit to master is acceptable for docs-only changes (per 20_workflow).
Verify commit is clean (no Co-authored-by).
