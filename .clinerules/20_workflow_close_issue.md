# Workflow: Close Issue

## Prerequisites
- Implementation complete (see `10_workflow_implement_task.md`)
- All proof commands passed at least once
- Commit created and verified
- Issue commented with proof outputs

## Steps (Execute in Order)

### Step 1: Final Verification — Re-run ALL Proofs
Re-run ALL proof commands from issue DoD to ensure nothing broke:

```bash
# Example for app build task
docker build -t app-a:dev ./apps/app-a
curl -s http://localhost:8080/api/work | jq .
```

```bash
# Example for Helm task
helm lint ./chart
kubectl get pods -n demo | grep -E "app-a|app-b" | wc -l
```

```bash
# Example for scenario task
./scripts/run_scenario.sh S1 baseline
ls tmp/artifacts/S1/baseline/ | wc -l
```

**GATE:** All proofs must pass. If ANY fail:
- DO NOT close issue
- Debug and fix the problem
- Re-commit if needed
- Start Step 1 again

### Step 2: Verify Commit Quality
```bash
git show -s --format=%B HEAD
```

Check ALL of these:
- [ ] Has conventional commit prefix (`feat:`, `fix:`, `chore:`, etc.) OR is plain format
- [ ] Title ≤72 chars (plain) or ≤80 chars (with prefix)
- [ ] No `Co-authored-by:` line
- [ ] No `Signed-off-by:` line
- [ ] Includes issue reference (`Relates to #<N>` or `Fixes #<N>`)

If any check fails:
```bash
git commit --amend
# Fix the message, then re-verify
```

### Step 3: Verify Architecture Constants (Critical Tasks Only)
For tasks that modify Helm charts, app code, or config:
```bash
# Check A=2 pods
grep "replicas:" chart/values-common.yaml | head -1
# Expected: replicas: 2

# Check B=3 pods
grep "replicas:" chart/values-common.yaml | tail -1
# Expected: replicas: 3

# Check B_DELAY_MS (not DELAY_MS)
grep -r "B_DELAY_MS" apps/ chart/
# Expected: Multiple matches

# Check ports
grep -E "grpcPort|metricsPort" chart/values-common.yaml
# Expected: grpcPort: 50051, metricsPort: 8080
```

### Step 4: Verify Artifacts (If Applicable)
For scenario tasks (T09A/B/C):
```bash
ls tmp/artifacts/<scenario>/<mode>/ | wc -l
# Expected: 8 files (1 fortio + 2 A prom + 2 A log + 3 B metrics)

ls tmp/artifacts/<scenario>/<mode>/a-*.prom | wc -l
# Expected: 2 (A=2 pods)

ls tmp/artifacts/<scenario>/<mode>/b-*.metrics | wc -l
# Expected: 3 (B=3 pods)
```

For script tasks:
```bash
ls scripts/*.sh
# Expected: run_scenario.sh, verify_s1.sh, verify_s4.sh (depending on task)
```

### Step 5: Close Issue with Summary
```bash
gh issue close <N> --comment "$(cat <<'EOF'
✓ Task complete

## Verification Summary
All DoD proof commands re-executed and verified:
- Proof 1: ✓ Passed
- Proof 2: ✓ Passed
- [Add more if applicable]

## Commit
- Hash: <commit-hash>
- Message: <commit-title>

## Alignment Verified
- [x] docs/plan.md
- [x] skills/<skill>.md
- [x] .clinerules/00_global_invariants.md
- [x] .clinerules/40_resilience_patterns.md (if resilience code)

Ready for next phase.
EOF
)"
```

### Step 6: Verify Closure
```bash
gh issue view <N> --json state
# Expected: "state": "CLOSED"
```

If state is still "OPEN":
- Check GitHub permissions
- Retry close command
- Verify issue number is correct

---

## Final Checklist Before Close
- [ ] All DoD proofs re-run and passed (Step 1)
- [ ] Commit message quality verified (Step 2)
- [ ] Architecture constants verified (Step 3, if applicable)
- [ ] Artifacts verified (Step 4, if applicable)
- [ ] Issue closed with summary (Step 5)
- [ ] Closure confirmed (Step 6)

## After Closing
```bash
# Pick next task
gh issue list --state open --milestone "<current-milestone>" | head -5

# Or check overall progress
gh issue list --state all --milestone "<current-milestone>"
```

---

**Why Final Verification Matters:**
- Prevents partial implementations from slipping through
- Catches regressions introduced after initial proof run
- Ensures commit message quality (no Co-authored-by violations)
- Validates artifacts actually exist (scenario tasks)
- Maintains demo-readiness standard

**If in doubt, re-run proofs. Better safe than sorry.**
