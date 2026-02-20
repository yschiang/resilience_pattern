# Contributing to Resilience Pattern Demo

This document describes the Git workflow and contribution process for this project.

---

## Standard Git Workflow

```
1 issue
  ↓
1 feature branch
  ↓
Multiple commits during development (optional)
  ↓
1 pull request
  ↓
Squash merge → 1 commit on master
```

### Key principles:
- **One issue = one branch = one PR**
- **Multiple commits allowed** on feature branch during development
- **Squash merge** collapses all commits into one on master
- **Maintainer merges**, not the developer (see PR Policy below)

---

## Concrete Example: T13-T18 (P5 Roadmap)

Each task in the P5 milestone followed this workflow:

### T13: App-B FAIL_RATE + idempotency ([#21](https://github.com/yschiang/resilience_pattern/issues/21))

```bash
# 1. Create issue via GitHub
gh issue create --title "T13: App-B FAIL_RATE + idempotency" --milestone "P5"

# 2. Create feature branch
git checkout -b 21-app-b-failrate-idempotency

# 3. Implement (multiple commits during development)
# ... make changes to apps/app-b/main.go
git add apps/app-b/main.go
git commit -m "feat: add FAIL_RATE env var injection"

# ... add idempotency logic
git add apps/app-b/main.go
git commit -m "feat: add idempotency dedup with sync.Map"

# ... add tests
git add apps/app-b/main_test.go
git commit -m "test: add idempotency cache tests"

# 4. Run DoD proofs, post to issue
gh issue comment 21 --body "DoD proofs passed: ..."

# 5. Push and create PR
git push origin 21-app-b-failrate-idempotency
gh pr create --title "T13: App-B FAIL_RATE + idempotency" --body "Fixes #21"

# 6. Architect reviews, approves
# 7. Maintainer merges with squash (3 commits → 1 on master)
gh pr merge 27 --squash --delete-branch
```

**Result:** PR [#27](https://github.com/yschiang/resilience_pattern/pull/27) with 3 commits squashed into 1 commit on master.

---

## Branch Naming Convention

Format: `<issue-number>-<short-description>`

**Examples:**
- `21-app-b-failrate-idempotency`
- `32-refactor-bclient-appa`
- `26-docs-plan2-readme-rewrite`

**Rules:**
- Use issue number as prefix
- Lowercase, hyphen-separated
- Keep description short (3-5 words max)

---

## Commit Message Convention

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>: <description>

[optional body]

Fixes #<issue-number>
```

**Types:**
- `feat:` — new feature
- `fix:` — bug fix
- `refactor:` — code restructuring (no behavior change)
- `docs:` — documentation only
- `test:` — adding or fixing tests
- `chore:` — tooling, dependencies, build

**Limits:**
- Title: ≤72 chars (plain) or ≤80 chars (with type prefix)
- No `Co-authored-by:` or `Signed-off-by:` lines (hard gate)

**Examples:**
```bash
feat: add FAIL_RATE env var and idempotency dedup

Fixes #21
```

```bash
refactor: rename BClient → AppA to clarify dual role

Fixes #32
```

---

## Pull Request Process

### 1. Create PR

```bash
# Push branch
git push origin <branch-name>

# Create PR with issue reference
gh pr create --title "<PR title>" --body "Fixes #<N>"
```

### 2. Post DoD Proofs

Comment on PR with all DoD proof command outputs:

```bash
gh pr comment <PR-number> --body "$(cat <<'EOF'
## DoD Verification

### Proof 1: Build app-b
```bash
docker build -t app-b:dev -f apps/app-b/Dockerfile .
```
Output:
```
Successfully built abc123
Successfully tagged app-b:dev
```

### Proof 2: Run tests
```bash
go test ./apps/app-b/...
```
Output:
```
ok      app-b   0.123s
```

All DoD requirements satisfied.
EOF
)"
```

### 3. Wait for Review

- **Architect** reviews and approves
- **Maintainer** merges (or delegates to architect)

### 4. PR Merge Policy

**HARD RULE:** Developer does NOT merge their own PR.

**Who can merge:**
| Role | Can Merge? | Conditions |
|------|-----------|------------|
| Developer | ❌ Never | Hard rule — cannot merge own PR |
| Architect | ✅ If delegated | Only when maintainer grants authority |
| Maintainer | ✅ Always | Default merge authority |

**Merge strategy:** Squash (default for this project)

```bash
# Maintainer or delegated architect merges:
gh pr merge <PR> --squash --delete-branch
```

### 5. After Merge

```bash
# Update local master
git checkout master
git pull origin master

# Verify your commit is on master
git log --oneline -5

# Delete local feature branch
git branch -d <branch-name>
```

---

## When to Create PR vs Direct Commit

**Create PR for review:**
- ✅ New features (app code, scripts)
- ✅ Infrastructure changes (Helm charts, config)
- ✅ Multi-file changes (>3 files)
- ✅ Anything requiring review

**Direct commit to master:**
- ✅ Documentation updates (README, docs/)
- ✅ Minor fixes (<10 lines changed)
- ✅ Typos, formatting
- ✅ No review needed

---

## Workflow Rules Reference

For detailed step-by-step instructions, see:

- **Implement task:** `.clinerules/10_workflow_implement_task.md`
- **Close issue / PR workflow:** `.clinerules/20_workflow_close_issue.md`
- **Global invariants:** `.clinerules/00_global_invariants.md`

---

## Questions?

- Check `.clinerules/` for detailed workflows
- Check `docs/runbook.md` for demo operations
- Open an issue with label `question`
