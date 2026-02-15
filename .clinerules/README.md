# .clinerules — Developer Agent Rules

## Purpose
These rules ensure consistency, quality, and demo-readiness across all tasks.

## How to Use
1. **Before starting any task:** Read `00_global_invariants.md`
2. **When implementing a task:** Follow `10_workflow_implement_task.md`
3. **When closing an issue:** Follow `20_workflow_close_issue.md`
4. **For skill execution:** Check `30_skill_index.md` for relevant skills
5. **For resilience code:** Satisfy all rules in `40_resilience_patterns.md`

## File Index
- `00_global_invariants.md` — Constants, commit policy, DoD bar
- `10_workflow_implement_task.md` — Implement task workflow
- `20_workflow_close_issue.md` — Close issue workflow
- `30_skill_index.md` — Skill pointers
- `40_resilience_patterns.md` — Resilience engineering rules (P0→P3)

## Hard Gates (Non-negotiable)
- Architecture constants MUST NOT change: A=2, B=3, ports, env vars
- Every commit MUST pass git_commit_skill.md checks (no Co-authored-by)
- Every issue close MUST include proof artifacts
- Every resilience pattern in 40_resilience_patterns.md is MANDATORY

## Quick Start
```bash
# Read this first
cat .clinerules/README.md

# Check architecture constants
cat .clinerules/00_global_invariants.md

# Pick next task
gh issue list --state open --milestone "P1 - Baseline Evidence Ready"

# Implement following workflow
cat .clinerules/10_workflow_implement_task.md
cat .clinerules/20_workflow_close_issue.md
```

---

**Read files in numeric order: 00 → 10 → 20 → 30 → 40**
