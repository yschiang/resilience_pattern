# .clinerules Proposal — Developer Agent Guidance

## Section 3: .clinerules File Structure

### Current State Assessment
**Exists:**
- `.clinerules/40_resilience_patterns.md` — Engineering rules for P0→P3 resilience (GOOD, keep as-is)

**Missing:**
- README (how to use .clinerules)
- Global invariants (commit policy, DoD enforcement)
- Workflow orchestration (implement task, close issue)
- Skill wiring (pointer to skills/*.md)

---

### Proposed .clinerules Structure

```
.clinerules/
├── README.md                      # How Developer should use these rules
├── 00_global_invariants.md        # Architecture constants, commit policy, DoD bar
├── 10_workflow_implement_task.md  # Step-by-step: read issue → code → proof → commit
├── 20_workflow_close_issue.md     # Step-by-step: verify → comment → close
├── 30_skill_index.md              # Pointers to ../skills/*.md (avoid duplication)
└── 40_resilience_patterns.md      # (EXISTING, no changes)
```

---

### File 1: `.clinerules/README.md`

**Purpose:** Entry point for Developer agent; explains how to use .clinerules.

**Key bullets:**
- `.clinerules/` contains rules that MUST be followed during implementation
- Read files in numeric order (00 → 10 → 20 → 30 → 40)
- Every commit must pass gates in `00_global_invariants.md`
- Every task follows workflow in `10_workflow_implement_task.md`
- Resilience code must satisfy `40_resilience_patterns.md`

**Content outline:**
```markdown
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
```

---

### File 2: `.clinerules/00_global_invariants.md`

**Purpose:** Architecture constants, commit policy, DoD bar (prevents drift).

**Key bullets:**
- A=2 pods, B=3 pods (IMMUTABLE)
- B gRPC port=50051, B metrics port=8080, env var=B_DELAY_MS
- Commit policy: conventional commits, no Co-authored-by, ≤80 chars with prefix
- DoD bar: every task needs ≥2 runnable proof commands

**Content outline:**
```markdown
# Global Invariants — IMMUTABLE

## Architecture Constants (DO NOT CHANGE)
- **A pods:** 2 (replicas=2 in Helm)
- **B pods:** 3 (replicas=3 in Helm)
- **B gRPC port:** 50051
- **B metrics HTTP port:** 8080
- **B delay env var:** B_DELAY_MS (not DELAY_MS)
- **A HTTP port:** 8080
- **Namespace:** demo (K8S)
- **Kind cluster name:** resilience-pattern

## Commit Policy (skills/git_commit_skill.md)
- Use conventional commits: `<type>: <description>`
- Common types: feat, fix, chore, docs, refactor, test
- Title: ≤72 chars (plain) or ≤80 chars (with prefix)
- **HARD GATE:** No `Co-authored-by:` or `Signed-off-by:` lines
- Verify: `git show -s --format=%B HEAD | grep -E "Co-authored-by|Signed-off-by"` returns nothing

## DoD Bar (Non-negotiable)
Every task MUST have:
1. At least 2 runnable proof commands in issue DoD
2. Proof commands must be copy/paste executable
3. Expected outputs must be 1-2 lines, specific

Deploy tasks: helm + kubectl pod count
App tasks: docker build + runtime curl proof
Script tasks: run script + show artifacts count

## File/Directory Conventions
- Apps: `apps/app-a/`, `apps/app-b/`
- Helm: `chart/`
- Scripts: `scripts/`
- Tests: `tests/`
- Artifacts: `tmp/artifacts/` (gitignored)
- Proto: `proto/`

## Proto Contract
- Package: `demo.v1`
- Service: `DemoService` (or similar)
- Method: `Work(WorkRequest) returns (WorkReply)`
- WorkReply fields: `bool ok`, `string code`, `int64 latency_ms`
```

---

### File 3: `.clinerules/10_workflow_implement_task.md`

**Purpose:** Step-by-step workflow for implementing a GitHub issue.

**Key bullets:**
- Read issue → identify skill → implement → run proofs → commit → comment
- Never skip proof commands
- Always check global invariants before committing
- Comment on issue with proof outputs BEFORE closing

**Content outline:**
```markdown
# Workflow: Implement Task

## Steps (Execute in Order)

### Step 1: Read Issue
```bash
gh issue view <N> --json body,title,milestone
```
- Note: issue title, scope, DoD proof commands, dependencies
- Check "Skill references" section for relevant skills

### Step 2: Verify Dependencies
- Check "Depends on" section
- If dependencies are not closed, STOP and work on dependencies first
- Verify with: `gh issue list --state open --milestone "<milestone>"`

### Step 3: Identify Relevant Skill
- Check "Skill references" in issue body
- Read skill from `skills/<skill>.md`
- Follow skill's steps and DoD exactly

### Step 4: Implement
- Follow skill instructions
- Verify against `00_global_invariants.md` (ports, env vars, pod counts)
- If implementing resilience code, satisfy `40_resilience_patterns.md`

### Step 5: Run All DoD Proof Commands
- Copy proof commands from issue DoD
- Execute each one
- Save outputs to `./tmp/proof_output_<N>.txt`
- **GATE:** All proofs must pass before proceeding

### Step 6: Commit
- Follow `skills/git_commit_skill.md`
- Use conventional commit format: `<type>: <description>`
- **GATE:** No Co-authored-by lines (verify with grep)
- Include issue reference in body: `Relates to #<N>` or `Fixes #<N>`

### Step 7: Comment on Issue
```bash
gh issue comment <N> --body "$(cat <<'EOF'
## Implementation Complete

Proof commands executed:
\`\`\`bash
<paste proof command 1>
\`\`\`
Output:
\`\`\`
<paste output>
\`\`\`

\`\`\`bash
<paste proof command 2>
\`\`\`
Output:
\`\`\`
<paste output>
\`\`\`

Commit: <commit-hash>
EOF
)"
```

### Step 8: DO NOT close yet
- Proceed to `20_workflow_close_issue.md` for final verification
```

---

### File 4: `.clinerules/20_workflow_close_issue.md`

**Purpose:** Final verification before closing issue.

**Key bullets:**
- Re-run all proof commands as final verification
- Check commit message quality
- Ensure artifacts are in place (for scenario tasks)
- Close with summary comment

**Content outline:**
```markdown
# Workflow: Close Issue

## Prerequisites
- Implementation complete (see `10_workflow_implement_task.md`)
- All proof commands passed
- Commit created and verified

## Steps (Execute in Order)

### Step 1: Final Verification
Re-run ALL proof commands from issue DoD:
```bash
# Example for app build task
docker build -t app-a:dev ./apps/app-a
curl -s http://localhost:8080/api/work | jq .
```
**GATE:** All must pass. If any fail, DO NOT close; fix first.

### Step 2: Verify Commit Quality
```bash
git show -s --format=%B HEAD
```
Check:
- [ ] Has conventional commit prefix (or is plain format)
- [ ] Title ≤72 chars (plain) or ≤80 chars (with prefix)
- [ ] No `Co-authored-by:` line
- [ ] Includes issue reference

### Step 3: Verify Artifacts (if applicable)
For scenario tasks (T09A/B/C):
```bash
ls tmp/artifacts/<scenario>/<mode>/ | wc -l
# Expected: 8 files (1 fortio + 2 A prom + 2 A log + 3 B metrics)
```

For script tasks:
```bash
ls scripts/*.sh | wc -l
```

### Step 4: Close Issue
```bash
gh issue close <N> --comment "$(cat <<'EOF'
✓ Task complete

All DoD proof commands verified.
Commit: <commit-hash>

Implementation aligns with:
- docs/plan.md
- skills/<skill>.md
- .clinerules/00_global_invariants.md

Ready for next phase.
EOF
)"
```

### Step 5: Verify Closure
```bash
gh issue view <N> --json state
# Expected: "state": "CLOSED"
```
```

---

### File 5: `.clinerules/30_skill_index.md`

**Purpose:** Quick reference to skills/*.md (avoids duplication).

**Key bullets:**
- Points to canonical skill docs in `skills/`
- Lists which skill applies to which task type
- No content duplication (skills/*.md is SSOT)

**Content outline:**
```markdown
# Skill Index — Pointers to skills/

## Purpose
This file maps task types to relevant skills. All skill content lives in `skills/*.md` (SSOT).

## Skill Directory
All skills are in: `../skills/*.md`

## Skill to Task Mapping

### Infrastructure & Tooling
- **kind cluster:** `skills/kind_cluster_skill.md`
  - Applies to: local K8S setup, any task needing `kubectl`
- **Image build/load:** `skills/image_build_load_skill.md`
  - Applies to: T02, T06 (app builds)
- **Helm packaging:** `skills/helm_packaging_skill.md`
  - Applies to: T07, T08 (chart + values)

### Application Code
- **Proto codegen:** `skills/proto_codegen_skill.md`
  - Applies to: T02, T06 (gRPC contract)
- **Resilience patterns:** `skills/resilience_patterns_skill.md`
  - Applies to: T03, T04, T05 (metrics + P0/P1 resilience)

### Testing & Scenarios
- **Scenario artifact:** `skills/scenario_artifact_skill.md`
  - Applies to: T09A, T09B, T09C (run_scenario.sh)

### Project Management
- **Issue ops:** `skills/issue_ops_skill.md`
  - Applies to: T01 (bootstrap), T12 (docs)
- **Git commit:** `skills/git_commit_skill.md`
  - Applies to: ALL tasks (every commit)

## How to Use
1. Check issue "Skill references" section
2. Open relevant skill from `skills/` directory
3. Follow skill's DoD and steps exactly
```

---

## Section 4: Developer Agent Operating Loop

### Operating Loop (5-8 Steps)

**Cycle: Pick task → Implement → Verify → Close → Repeat**

```
┌─────────────────────────────────────────┐
│ START: Developer Agent Initialization   │
└───────────────┬─────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────┐
│ STEP 1: Pick Next Task                                    │
│ - gh issue list --state open --milestone "<current>"      │
│ - Sort by ID (lowest first = P0 critical path)            │
│ - Check "Depends on" — skip if dependencies open          │
│ - Select lowest-ID unblocked task                         │
└───────────────┬───────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────┐
│ STEP 2: Read Issue + Identify Skill                       │
│ - gh issue view <N> --json body                           │
│ - Extract: Scope, DoD, Skill references, Depends on       │
│ - Read relevant skill from skills/*.md                    │
│ - Review .clinerules/00_global_invariants.md              │
└───────────────┬───────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────┐
│ STEP 3: Implement Following Skill                         │
│ - Follow skill steps exactly                              │
│ - Check constants against 00_global_invariants.md         │
│ - If resilience code: satisfy 40_resilience_patterns.md   │
│ - Write code, config, or scripts as required              │
└───────────────┬───────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────┐
│ STEP 4: Run All DoD Proof Commands                        │
│ - Copy proofs from issue DoD                              │
│ - Execute each sequentially                               │
│ - Save outputs to ./tmp/proof_<N>.txt                        │
│ - GATE: If any fail, return to STEP 3 (fix & retry)       │
└───────────────┬───────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────┐
│ STEP 5: Commit with Conventional Format                   │
│ - Follow skills/git_commit_skill.md                       │
│ - Format: <type>: <description>                           │
│ - Include: Relates to #<N> in body                        │
│ - GATE: No Co-authored-by (grep check)                    │
│ - GATE: Title ≤80 chars with prefix                       │
└───────────────┬───────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────┐
│ STEP 6: Comment on Issue with Proof Outputs               │
│ - gh issue comment <N> --body "<proof outputs>"           │
│ - Include: commit hash, proof commands, actual outputs    │
└───────────────┬───────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────┐
│ STEP 7: Final Verification (Re-run Proofs)                │
│ - Follow .clinerules/20_workflow_close_issue.md           │
│ - Re-execute all proof commands                           │
│ - Verify commit message quality                           │
│ - Check artifacts exist (if applicable)                   │
│ - GATE: All checks pass                                   │
└───────────────┬───────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────┐
│ STEP 8: Close Issue                                       │
│ - gh issue close <N> --comment "✓ Task complete..."       │
│ - Verify closure: gh issue view <N> --json state          │
└───────────────┬───────────────────────────────────────────┘
                │
                ▼
         ┌──────┴──────┐
         │ More tasks? │
         └──────┬──────┘
                │
         ┌──────┴──────────┐
         │ YES             │ NO
         ▼                 ▼
    Return to         ┌────────────┐
    STEP 1            │ DONE       │
                      │ Milestone  │
                      │ Complete   │
                      └────────────┘
```

### Key Loop Invariants
1. **Never skip dependencies:** If "Depends on #X" and #X is open, skip current task
2. **Always run proofs twice:** Once before commit, once before close
3. **Always comment before close:** Issue must have implementation proof artifacts
4. **Follow execution_order.md:** Prefer tasks in order (docs/execution_order.md)
5. **Check global invariants:** Every implementation must align with 00_global_invariants.md

---

**End of .clinerules proposal**
