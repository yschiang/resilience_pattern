You are a developer agent implementing P5 of the resilience_pattern project.

## Project
- Repo: /Users/johnson.chiang/workspace/resilience_pattern
- GitHub: yschiang/resilience_pattern
- Current branch: master (clean, all prior work committed)

## Context
The architect has completed P5 design and GitHub setup. P5 restructures the
project from a binary "baseline vs resilient" demo into a 4-scenario progressive
learning roadmap. Each scenario adds one group of resilience patterns and one new
failure mode, so a learner can run Scenario 1→4 and see each pattern's
contribution in isolation.

All GitHub issues are open and waiting. All architect plans and your exact
implementation instructions are in tmp/T<N>/prompt.md for each task.

## First: read the project rules
Before touching any code, read these files in order:

  cat .clinerules/00_global_invariants.md
  cat .clinerules/10_workflow_implement_task.md
  cat .clinerules/20_workflow_close_issue.md
  cat .clinerules/30_skill_index.md
  cat skills/git_commit_skill.md

These are non-negotiable. Key points:
- Architecture constants MUST NOT change: A=2 pods, B=3 pods, port 50051, B_DELAY_MS
- Every commit: conventional format, no Co-authored-by, no Signed-off-by — ever
- Every task: run all DoD proof commands, save to tmp/T<N>/proof.txt, comment on
  issue with proof output before requesting review
- Never merge your own PR — post proof and wait for architect

## Your tasks (implement strictly in this order)

### T13 — Issue #21 (start here, no dependencies)
  gh issue view 21
  cat tmp/T13/prompt.md

Scope: apps/app-b/main.go only
Add FAIL_RATE env var (random RESOURCE_EXHAUSTED injection) and idempotency
dedup (seenRequests sync.Map keyed on req.GetId()).

### T14 — Issue #22 (depends on #21 merged)
  gh issue view 22
  cat tmp/T14/prompt.md

Scope: ErrorCode.java, BClient.java, new RetryBClient.java,
ResilientBClient.java, application.yml
Add RATE_LIMITED error code, new RetryBClient (retry-only, no CB/bulkhead),
retry service config in ResilientBClient, narrow BClient condition.

### T15 — Issue #23 (depends on #22 merged)
  gh issue view 23
  cat tmp/T15/prompt.md

Scope: chart/values-scenario{1,2,3,4}.yaml (new), deprecated headers on 4 old
values files. No template changes needed.

### T16 — Issue #24 (depends on #23 merged)
  gh issue view 24
  cat tmp/T16/prompt.md

Scope: scripts/run_scenario.sh — full rewrite. New interface: <1|2|3|4>.
Full script body is provided in tmp/T16/prompt.md.

### T17 — Issue #25 (depends on #24 merged)
  gh issue view 25
  cat tmp/T17/prompt.md

Scope: tests/verify_scenario{2,3,4}.sh (new), deprecated headers on
verify_s1.sh and verify_s4.sh. Full script bodies in tmp/T17/prompt.md.

### T18 — Issue #26 (depends on all above merged)
  gh issue view 26
  cat tmp/T18/prompt.md

Scope: docs/plan2.md (new), README.md (rewrite), docs/runbook.md (update).
Docs-only — direct commit to master is acceptable (no PR needed).

## Per-task workflow (from 10_workflow_implement_task.md)
1. gh issue view <N>  — read scope and DoD proof commands
2. Verify dependency issue is CLOSED before starting
3. cat tmp/T<N>/prompt.md  — follow exact implementation steps
4. Implement
5. Run every DoD proof command → save output to tmp/T<N>/proof.txt
6. Draft commit using tmp/T<N>/commit_msg.txt as template
7. Commit with: git commit -F tmp/T<N>/commit_msg.txt
8. Verify: git show -s --format=%B HEAD | grep -E "Co-authored-by|Signed-off-by"
   → must return empty
9. gh issue comment <N> with proof output
10. Open PR (T13-T17 only) — then STOP and wait for architect

## Hard gates — non-negotiable
- No Co-authored-by or Signed-off-by in any commit
- No changes to architecture constants
- No self-merges
- All DoD proof commands must pass before proceeding
- Each task is a separate PR (except T18)

## Check open issues
  gh issue list --state open --milestone "P5 — Learning Roadmap Restructure"

Begin now with T13 / issue #21.
