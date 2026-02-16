# work-history/ Directory Organization

This directory contains historical working files, drafts, and artifacts from the P0-P1 implementation phases. It serves as a learning resource for the devops team to understand the development process and evolution of the project.

**Last Reorganized:** 2026-02-16
**Purpose:** Educational archive for devops team learning

---

## Directory Structure

```
work-history/
├── 01-commits/           # Commit message drafts
├── 02-pr/                # Pull request bodies and reviews
├── 03-issues/            # GitHub issue bodies organized by milestone
│   ├── P0/              # Issues #1-5 (Baseline E2E)
│   ├── P1/              # Issues #6-7 (Baseline Evidence)
│   ├── P2/              # Issues #8-9 (Resilient P0)
│   ├── P3/              # Issues #10-12 (Resilient P1)
│   └── P4/              # Issues #13-14 (Verification + Docs)
├── 04-proofs/            # DoD proof command outputs
├── 05-prompts/           # Agent prompts for Claude Code
│   ├── architect/       # Architect agent prompts
│   └── developer/       # Developer agent prompts
├── 06-planning/          # Planning and handoff documents
├── 07-scripts/           # Utility bash scripts
├── 08-patches/           # Git patches for skills/*.md
└── 09-misc/              # Miscellaneous outputs
```

---

## 01-commits/

Commit message drafts used during P0 implementation.

| File | Description |
|------|-------------|
| `p0_readme_fix.txt` | README documentation fix (C++ → Go) |
| `p0_bootstrap.txt` | Bootstrap GitHub issues and skills |
| `p0_docs_refine.txt` | Execution order and clinerules refinements |

---

## 02-pr/

Pull request artifacts for PR #15 (P0 Milestone).

| File | Description |
|------|-------------|
| `pr15_body.md` | PR #15 description body |
| `pr15_comment_readme_fix.md` | Comment after README fix |
| `pr15_review_architect.md` | Comprehensive Architect review (5.9K lines) |

**Key Review Finding:** README stated "C++ gRPC service" but implementation was Go.

---

## 03-issues/

GitHub issue bodies organized by milestone (P0-P4).

### P0/ - Baseline E2E Running (Issues #1-5)

| File | Issue | Task | Description |
|------|-------|------|-------------|
| `issue01_t01_repo_hygiene.txt` | #1 | T01 | Repo structure, README, .gitignore |
| `issue02_t06_app_b_grpc*.txt` | #2 | T06 | Go gRPC service B (3 pods) |
| `issue03_t02_app_a_rest*.txt` | #3 | T02 | Spring Boot REST API A (2 pods) |
| `issue04_t07_helm_chart*.txt` | #4 | T07 | Helm chart scaffold |
| `issue05_t08_values_overlays*.txt` | #5 | T08 | Values overlays (common, baseline, s1, s4) |

**Note:** `*_v2.txt` files are updated versions with refined DoD proofs.

### P1/ - Baseline Evidence Ready (Issues #6-7)

| File | Issue | Task | Description |
|------|-------|------|-------------|
| `issue06_t03_metrics_taxonomy*.txt` | #6 | T03 | Semantic error codes + metrics export |
| `issue07_t09a_scenario_s1_baseline*.txt` | #7 | T09A | Scenario runner S1 baseline + artifacts |

**Note:** `*_fixed.txt` corrects artifact count from 7→8 files.

### P2/ - Resilient P0 (Issues #8-9)

| File | Issue | Task | Description |
|------|-------|------|-------------|
| `issue08_t04_resilient_p0_wrapper*.txt` | #8 | T04 | Deadline, bulkhead, circuit breaker |
| `issue09_t09b_scenario_s1_resilient.txt` | #9 | T09B | Scenario runner S1 resilient |

### P3/ - Resilient P1 (Issues #10-12)

| File | Issue | Task | Description |
|------|-------|------|-------------|
| `issue10_t05_resilient_p1_selfheal.txt` | #10 | T05 | Keepalive, reconnect, channel pool |
| `issue11_t10_fault_injection_s4.txt` | #11 | T10 | iptables TCP reset injection |
| `issue12_t09c_scenario_s4_both.txt` | #12 | T09C | Scenario runner S4 baseline + resilient |

### P4/ - Verification + Docs (Issues #13-14)

| File | Issue | Task | Description |
|------|-------|------|-------------|
| `issue13_t11_verification_scripts.txt` | #13 | T11 | verify_s1.sh, verify_s4.sh |
| `issue14_t12_docs_packaging.txt` | #14 | T12 | PLAN alignment + runbook |

---

## 04-proofs/

DoD proof command outputs from P0 implementation.

| File | Task | Description |
|------|------|-------------|
| `p0_t01_repo_hygiene_proofs.txt` | T01 | Directory structure verification |
| `p0_t02_app_a_proofs.md` | T02 | App-a Docker build + runtime curl |
| `p0_t06_app_b_proofs.md` | T06 | App-b Docker build + metrics endpoint |
| `p0_t07_helm_chart_proofs.md` | T07 | Helm lint + template rendering |
| `p0_t08_values_overlays_proofs.md` | T08 | Values file count verification |

---

## 05-prompts/

Agent prompts for Claude Code sessions.

### architect/

| File | Description |
|------|-------------|
| `p0_bootstrap_github.md` | Architect prompt for creating milestones, labels, issues |

### developer/

| File | Phase | Description |
|------|-------|-------------|
| `p0_start_full.txt` | P0 | Full Developer prompt (T01→T06→T02→T07→T08) |
| `p0_start_simple.txt` | P0 | Simplified Developer prompt (rules + workflow) |
| `p1_start_full.txt` | P1 | Full Developer prompt (T03→T09A) with .clinerules |
| `p1_handoff_instructions.txt` | P1 | How to use P1 prompt |

**Key Features:**
- Architecture invariants (A=2, B=3, ports, B_DELAY_MS)
- 8-step workflow (read → verify → implement → proof → commit → comment → verify → close)
- Hard gates (conventional commits, no Co-authored-by, ≥2 DoD proofs)

---

## 06-planning/

Planning and handoff documentation.

| File | Description |
|------|-------------|
| `p1_review_and_plan.md` | P1 current state review and execution plan |
| `p1_handoff_summary.md` | Comprehensive P1 handoff document (5.4K lines) |
| `p1_handoff_summary_copy.txt` | Duplicate copy |

**Contents:**
- .clinerules/ structure rationale
- P1 issues verified (#6, #7)
- Execution order (T03 → T09A)
- Developer start commands

---

## 07-scripts/

Bash utility scripts.

| File | Description | Purpose |
|------|-------------|---------|
| `bootstrap_issues.sh` | GitHub issue creation | Creates 5 milestones, 9 labels, 14 issues via gh CLI |
| `audit_issues.sh` | Issue validation | Checks for required sections (Refs, DoD, ≥2 proofs) |

**Usage:**
```bash
# Bootstrap (idempotent)
./bootstrap_issues.sh

# Audit all issues
./audit_issues.sh | column -t
```

---

## 08-patches/

Git patches for skills/*.md files.

| File | Description |
|------|-------------|
| `git_commit_skill_conventional.patch` | Adds conventional commit support (feat:, fix:, chore:) |
| `skills_refinements.patch` | Fixes ports, paths, DoD bar alignment |

**Apply with:**
```bash
git apply 08-patches/<patch-file>
```

---

## 09-misc/

Miscellaneous outputs and system files.

| File | Description |
|------|-------------|
| `chart_template_output.yaml` | Helm template rendering output (verification) |
| `system_autologin.txt` | macOS autologin check output |
| `system_guestaccount.txt` | macOS guest account check output |
| `t03_empty_placeholder.txt` | Empty placeholder (can be deleted) |

---

## File Naming Conventions

### Issue Bodies
- Pattern: `issue##_t##_<description>.txt`
- Example: `issue06_t03_metrics_taxonomy.txt`
- Versions: `*_v2.txt` (updated), `*_fixed.txt` (corrections)

### Proofs
- Pattern: `p#_t##_<task>_proofs.{txt|md}`
- Example: `p0_t02_app_a_proofs.md`

### Prompts
- Pattern: `p#_<role>_<purpose>.txt`
- Example: `p1_start_full.txt`

### PR Files
- Pattern: `pr##_<type>_<description>.md`
- Example: `pr15_review_architect.md`

---

## Cleanup Recommendations

### Safe to Delete:
- `06-planning/p1_handoff_summary_copy.txt` (duplicate)
- `09-misc/t03_empty_placeholder.txt` (empty)
- `09-misc/system_*.txt` (not project-related)

### Keep for Reference:
- All `03-issues/` files (GitHub issue bodies)
- All `04-proofs/` files (DoD verification)
- All `05-prompts/` files (agent handoff)
- `02-pr/pr15_review_architect.md` (comprehensive review)

### Archive After P4:
- `01-commits/` (commit messages already in git log)
- `07-scripts/bootstrap_issues.sh` (issues already created)

---

## Usage Tips

### Finding Specific Content

```bash
# Find all P1 related files
find work-history/ -name "*p1*" -o -name "*issue0[67]*"

# Find all DoD proofs
find work-history/04-proofs/ -type f

# Find all Developer prompts
ls work-history/05-prompts/developer/

# Search for specific text across all files
grep -r "B_DELAY_MS" work-history/
```

### Comparing Versions

```bash
# Compare issue body versions
diff work-history/03-issues/P1/issue07_t09a_scenario_s1_baseline.txt \
     work-history/03-issues/P1/issue07_t09a_scenario_s1_baseline_fixed.txt
```

---

## Maintenance

**When to Update:**
- New milestone phase starts (P2, P3, P4)
- New prompts created for Developer/Architect
- New issue body versions created
- PR reviews completed

**Reorganization Command:**
```bash
# Re-run reorganization script (safe, idempotent)
bash /tmp/tmp_reorganize.sh
```

---

**Last Updated:** 2026-02-16
**Maintained By:** Architect Agent
**Status:** P0 Complete, P1 In Progress
