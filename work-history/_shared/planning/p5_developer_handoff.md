# P5 Developer Handoff

## Your Role
You are the **developer agent**. The architect has designed P5 and created all
GitHub issues. Your job is to implement each task in order, following the
`.clinerules/` workflow exactly.

---

## Before Every Task
Read these files in order:
1. `.clinerules/00_global_invariants.md` — architecture constants (never change)
2. `.clinerules/10_workflow_implement_task.md` — implementation workflow
3. `.clinerules/20_workflow_close_issue.md` — close issue workflow
4. `.clinerules/30_skill_index.md` — skill pointers
5. `skills/git_commit_skill.md` — commit rules (no Co-authored-by, ever)

---

## Execution Order (strict — do not skip ahead)

### Task 1 — T13 (#21)
```bash
gh issue view 21
cat tmp/T13/prompt.md   # full implementation instructions
```
- Scope: `apps/app-b/main.go` only
- Skill: `git_commit_skill.md`
- No dependencies

### Task 2 — T14 (#22)
```bash
gh issue view 22
cat tmp/T14/prompt.md
```
- Scope: `ErrorCode.java`, `BClient.java`, new `RetryBClient.java`,
  `ResilientBClient.java`, `application.yml`
- Skill: `git_commit_skill.md`
- **Depends on #21 merged**

### Task 3 — T15 (#23)
```bash
gh issue view 23
cat tmp/T15/prompt.md
```
- Scope: 4 new `chart/values-scenario*.yaml` files + deprecated headers on 4 old files
- Skill: `helm_packaging_skill.md`, `git_commit_skill.md`
- **Depends on #22 merged**

### Task 4 — T16 (#24)
```bash
gh issue view 24
cat tmp/T16/prompt.md   # full script body provided
```
- Scope: `scripts/run_scenario.sh` rewrite
- Skill: `git_commit_skill.md`
- **Depends on #23 merged**

### Task 5 — T17 (#25)
```bash
gh issue view 25
cat tmp/T17/prompt.md   # full script bodies provided
```
- Scope: 3 new `tests/verify_scenario*.sh` + deprecated headers on 2 old scripts
- Skill: `git_commit_skill.md`
- **Depends on #24 merged**

### Task 6 — T18 (#26)
```bash
gh issue view 26
cat tmp/T18/prompt.md
```
- Scope: `docs/plan2.md` (new), `README.md` (rewrite), `docs/runbook.md` (update)
- Skill: `git_commit_skill.md`
- Direct commit to master (docs-only, per `20_workflow_close_issue.md`)
- **Depends on all above merged**

---

## Per-Task Workflow (10_workflow_implement_task.md)

For each task:
1. `gh issue view <N>` — read issue, note DoD proof commands
2. Check dependencies are closed before starting
3. Read `tmp/T<N>/prompt.md` — exact implementation steps
4. Implement
5. Run all DoD proof commands, save output to `tmp/T<N>/proof.txt`
6. Commit via `git_commit_skill.md` (use `tmp/T<N>/commit_msg.txt` as template)
7. Comment on issue with proof outputs
8. **Do NOT merge your own PR** — post proof, wait for architect review

---

## Hard Rules
- **Never** add `Co-authored-by:` or `Signed-off-by:` to commits
- **Never** change architecture constants (A=2, B=3, ports, B_DELAY_MS)
- **Never** merge your own PR
- **Always** run all DoD proof commands before moving on
- **Always** save proof output to `tmp/T<N>/proof.txt`
- **Always** comment on the issue with proof before requesting review

---

## PR vs Direct Commit
- T13–T17: Create PR, post proof, wait for architect merge approval
- T18 (docs only): Direct commit to master is acceptable

---

## Issue Tracker
```bash
gh issue list --state open --milestone "P5 — Learning Roadmap Restructure"
```
