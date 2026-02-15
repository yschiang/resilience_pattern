# skills/issue_ops_skill.md

## Purpose
Create **milestones + labels + issues** (idempotent) from `docs/plan.md`, enforcing a strict issue template with runnable DoD proof commands.

## Inputs
- Repo: `yschiang/resilience_pattern`
- SSOT: `docs/plan.md`
- Required issue titles: T01–T12 (+ T09A/B/C) as defined in plan.md

## Outputs
- GitHub milestones:
  - P0 Baseline E2E Running
  - P1 Baseline Evidence Ready
  - P2 Resilient P0 (Stop the bleeding)
  - P3 Resilient P1 (Self-heal + Correlation)
  - P4 Verification + Docs
- Labels:
  - task
  - area/app-a, area/app-b, area/helm, area/scripts, area/docs
  - prio/P0, prio/P1, prio/P2
- Issues created/updated with enforced template
- Optional: `docs/execution_order.md`

## Steps
1) Precheck `gh` auth and set default repo.
2) Create labels (idempotent).
3) Create milestones (idempotent).
4) Create issues (idempotent by title). Each issue body MUST include:
   - Refs (docs/plan.md)
   - Scope (3–6 bullets)
   - DoD with **Proof commands** + **Expected**
   - Depends on
   - Out of scope
5) Verify every issue meets the DoD bar:
   - At least 2 proof commands per issue
   - Deploy issues include helm + kubectl proof
   - App issues include docker build + runtime proof
6) Create `docs/execution_order.md` (short) aligned with plan.

## DoD (Proof commands + Expected)

### Proof commands
```bash
gh --version
gh auth status
gh repo set-default yschiang/resilience_pattern

gh label list
gh api repos/yschiang/resilience_pattern/milestones --jq '.[].title'

gh issue list --limit 50
gh issue view 1 --json body --jq '.body' | sed -n '1,120p'
```

### Expected
- Milestones exist with correct titles
- Labels exist
- Issues exist with exact titles
- Issue bodies contain required sections and at least 2 runnable proof commands

## Guardrails
- Architect agent MUST NOT implement application code.
- Be idempotent: rerun should not create duplicates.
- Keep issue scope minimal for the phase; avoid scope creep.

## Commit policy
- Prefer no commits. If committing (e.g., `docs/execution_order.md`), use:
  - Title: short, imperative
  - Body: what/why, include proof commands run
  - **No “Co-authored-by:” lines**
