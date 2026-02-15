# Workflow: Implement Task

## Steps (Execute in Order)

### Step 1: Read Issue
```bash
gh issue view <N> --json body,title,milestone
```
- Note: issue title, scope, DoD proof commands, dependencies
- Check "Skill references" section for relevant skills
- Verify milestone matches current phase (P0, P1, P2, P3, P4)

### Step 2: Verify Dependencies
- Check "Depends on" section in issue body
- If dependencies are not closed, STOP and work on dependencies first
- Verify with: `gh issue list --state open --milestone "<milestone>"`
- Example: If issue says "Depends on #5" and #5 is still open, skip this task

### Step 3: Identify Relevant Skill
- Check "Skill references" in issue body
- Read skill from `skills/<skill>.md`
- Follow skill's steps and DoD exactly
- Common skills:
  - `git_commit_skill.md` — ALL tasks use this for commits
  - `resilience_patterns_skill.md` — T03, T04, T05
  - `scenario_artifact_skill.md` — T09A, T09B, T09C
  - `helm_packaging_skill.md` — T07, T08

### Step 4: Implement
- Follow skill instructions step-by-step
- Verify against `00_global_invariants.md`:
  - Check pod counts (A=2, B=3)
  - Check ports (gRPC=50051, metrics=8080)
  - Check env var (B_DELAY_MS, not DELAY_MS)
- If implementing resilience code, satisfy `40_resilience_patterns.md`
- Write tests/verification as needed

### Step 5: Run All DoD Proof Commands
- Copy proof commands from issue DoD section
- Execute each one sequentially
- Save outputs to `/tmp/proof_output_<N>.txt` for reference
- **GATE:** All proofs must pass before proceeding
- If any proof fails:
  - Debug the issue
  - Fix implementation
  - Re-run ALL proofs from start
  - Do NOT proceed until all pass

### Step 6: Commit
- Follow `skills/git_commit_skill.md`
- Use conventional commit format: `<type>: <description>`
  - Examples: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`
- Title limits:
  - ≤72 chars for plain commits
  - ≤80 chars for commits with type prefix
- Include issue reference in commit body:
  - `Relates to #<N>` (if partial work)
  - `Fixes #<N>` (if completing the issue)
- **GATE:** Verify no Co-authored-by lines:
  ```bash
  git show -s --format=%B HEAD | grep -E "Co-authored-by|Signed-off-by"
  # Expected: Empty output
  ```

### Step 7: Comment on Issue with Proof Outputs
```bash
gh issue comment <N> --body "$(cat <<'EOF'
## Implementation Complete

### Proof Command 1
```bash
<paste proof command 1>
```
Output:
```
<paste actual output>
```

### Proof Command 2
```bash
<paste proof command 2>
```
Output:
```
<paste actual output>
```

### Commit
- Hash: <commit-hash>
- Message: <commit-title>

All DoD requirements satisfied.
EOF
)"
```

### Step 8: DO NOT Close Yet
- Proceed to `20_workflow_close_issue.md` for final verification
- Closing requires re-running all proofs as final gate
- This ensures implementation didn't break between commit and close

---

## Common Pitfalls to Avoid
- ❌ Skipping proof commands ("it probably works")
- ❌ Not checking dependencies before starting
- ❌ Changing architecture constants without approval
- ❌ Adding Co-authored-by lines to commits
- ❌ Closing issue without final verification (see Step 8)
- ❌ Not following skill instructions exactly

## Success Checklist
Before moving to Step 8:
- [ ] All dependencies closed
- [ ] Skill instructions followed exactly
- [ ] All DoD proofs passed
- [ ] Architecture constants verified
- [ ] Commit created with conventional format
- [ ] No Co-authored-by lines in commit
- [ ] Issue commented with proof outputs
