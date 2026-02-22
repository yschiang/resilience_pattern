# Developer Prompt: Issue #34

## Task

Rename `RATE_LIMITED` → `BACKEND_ERROR` to avoid confusion with actual rate limiting.

## Context

The current name implies rate limiting algorithms (token buckets, quotas), but app-b actually does random failure injection (30% chance of error). "BACKEND_ERROR" is clearer: it describes the source (backend returned an error) rather than the mechanism.

## Your Mission

Update 1 enum value + ~32 references across 5 files.

## Step-by-Step Instructions

### 1. Read the plan

```bash
cat tmp/issue-34/plan.md
```

Understand scope: 1 Java enum + 4 docs + 1 test script.

### 2. Create feature branch

```bash
git checkout -b 34-refactor-rate-limited-backend-error
```

### 3. Update ErrorCode.java

**File:** `apps/app-a/src/main/java/com/demo/appa/ErrorCode.java`

**Change enum value:**
```java
public enum ErrorCode {
    SUCCESS,
    DEADLINE_EXCEEDED,
    QUEUE_FULL,
    CIRCUIT_OPEN,
    BACKEND_ERROR,    // was: RATE_LIMITED
    UNAVAILABLE,
    UNKNOWN;
```

**Update fromGrpcStatus() mapping:**
```java
case RESOURCE_EXHAUSTED:
    return BACKEND_ERROR;  // was: return RATE_LIMITED;
```

### 4. Build app-a

```bash
docker build -t app-a:dev -f apps/app-a/Dockerfile .
```

**Expected:** Build succeeds.

If build fails: check for missed enum references.

### 5. Update README.md

**Search and replace ALL occurrences:**
- `RATE_LIMITED` → `BACKEND_ERROR`

**Use your editor's find-replace (case-sensitive).**

**Key sections to verify:**
- Error Taxonomy table (line ~164)
- Failure Injection → Expected Results table (line ~92)
- Per-Scenario Detail sections (S1, S2, S3)

**Verify:**
```bash
grep -c "RATE_LIMITED" README.md
```
Expected: 0

### 6. Update docs/plan2.md

**Search and replace:**
- `RATE_LIMITED` → `BACKEND_ERROR`

**Verify:**
```bash
grep -c "RATE_LIMITED" docs/plan2.md
```
Expected: 0

### 7. Update docs/runbook.md

**Search and replace:**
- `RATE_LIMITED` → `BACKEND_ERROR`

**Verify:**
```bash
grep -c "RATE_LIMITED" docs/runbook.md
```
Expected: 0

### 8. Update tests/verify_scenario2.sh

**Variable names:**
```bash
# Line ~33, 38
S1_RATE_LIMITED → S1_BACKEND_ERROR
S2_RATE_LIMITED → S2_BACKEND_ERROR
```

**sum_error_code calls:**
```bash
# Line ~34, 39
sum_error_code "$S1_DIR" "RATE_LIMITED" → "BACKEND_ERROR"
sum_error_code "$S2_DIR" "RATE_LIMITED" → "BACKEND_ERROR"
```

**Assertion messages (lines ~42-50):**
Update all occurrences in pass/fail messages.

**Verify:**
```bash
grep -c "RATE_LIMITED" tests/verify_scenario2.sh
```
Expected: 0

### 9. Final verification

**No RATE_LIMITED in active files:**
```bash
grep -r "RATE_LIMITED" README.md docs/plan2.md docs/runbook.md tests/verify_scenario2.sh apps/app-a/src/main/java/com/demo/appa/ErrorCode.java
```

**Expected:** Empty output (no matches).

If any matches remain, update those files.

### 10. Run DoD proofs

```bash
# Proof 1: Build succeeds
docker build -t app-a:dev -f apps/app-a/Dockerfile .

# Proof 2: No RATE_LIMITED in active files
grep -r "RATE_LIMITED" README.md docs/plan2.md docs/runbook.md tests/verify_scenario2.sh apps/app-a/src/main/java/com/demo/appa/ErrorCode.java
# Expected: empty

# Proof 3: Verify enum
grep -A8 "public enum ErrorCode" apps/app-a/src/main/java/com/demo/appa/ErrorCode.java | grep BACKEND_ERROR
# Expected: BACKEND_ERROR appears

# Proof 4: Scenario script still works (optional — requires running scenarios first)
# ./scripts/run_scenario.sh 1 && ./scripts/run_scenario.sh 2
# ./tests/verify_scenario2.sh
# Expected: PASS=3 FAIL=0
```

Save outputs to `tmp/issue-34/proof.txt`.

### 11. Commit

```bash
git add .
git commit -m "$(cat <<'EOF'
refactor: rename RATE_LIMITED → BACKEND_ERROR for clarity

The old name "RATE_LIMITED" implied actual rate limiting (token buckets,
quotas), but app-b does random failure injection (30% fail via FAIL_RATE).
New name "BACKEND_ERROR" emphasizes the source (backend returned error)
rather than the mechanism.

Changes:
- ErrorCode.java: RATE_LIMITED → BACKEND_ERROR enum value
- README.md: all references updated (~12 occurrences)
- docs/plan2.md: all references updated (~8 occurrences)
- docs/runbook.md: all references updated (~3 occurrences)
- tests/verify_scenario2.sh: variable names + grep patterns updated

app-b error message unchanged ("rate limited") — only enum name matters.

Fixes #34
EOF
)"
```

### 12. Push and create PR

```bash
git push origin 34-refactor-rate-limited-backend-error
gh pr create --title "refactor: rename RATE_LIMITED → BACKEND_ERROR for clarity" --body "Fixes #34"
```

### 13. Post DoD proofs to PR

```bash
gh pr comment <PR-number> --body "$(cat tmp/issue-34/proof.txt)"
```

## Checklist

- [ ] Feature branch created
- [ ] ErrorCode.java enum value updated
- [ ] ErrorCode.java fromGrpcStatus() mapping updated
- [ ] App-a builds successfully
- [ ] README.md: all RATE_LIMITED → BACKEND_ERROR
- [ ] docs/plan2.md: all RATE_LIMITED → BACKEND_ERROR
- [ ] docs/runbook.md: all RATE_LIMITED → BACKEND_ERROR
- [ ] tests/verify_scenario2.sh: variable names + grep patterns updated
- [ ] grep -r "RATE_LIMITED" active files → 0 results
- [ ] All DoD proofs passed
- [ ] Commit message follows conventional format
- [ ] PR created with issue reference
- [ ] DoD proofs posted to PR

## Common Pitfalls

- ❌ Missing the `fromGrpcStatus()` mapping (case RESOURCE_EXHAUSTED)
- ❌ Forgetting to update variable names in verify_scenario2.sh
- ❌ Missing occurrences in prose text (not just code blocks)
- ❌ Not verifying with grep after updates

## Questions?

- Check `tmp/issue-34/plan.md` for detailed scope
- Grep to find missed references: `grep -rn "RATE_LIMITED" README.md`
- If build fails, check ErrorCode.java for syntax errors
