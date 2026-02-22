# Issue #34 Plan: Refactor RATE_LIMITED → BACKEND_ERROR

## Problem

`RATE_LIMITED` implies actual rate limiting (token buckets, quotas, request counting), but app-b actually does **random failure injection** (30% random failure via `FAIL_RATE`). This confuses learners.

## Solution

Rename to `BACKEND_ERROR` — emphasizes the **source** (backend returned an error) rather than implying a specific mechanism.

## Scope

**32 occurrences** in active files:
- `ErrorCode.java`: enum value definition
- `README.md`: ~12 references (Error Taxonomy, Per-Scenario Detail, Failure Injection table)
- `docs/plan2.md`: ~8 references (Pattern Inventory, Failure Injection, Verification)
- `docs/runbook.md`: ~3 references (Scenario Reference, Verify Commands)
- `tests/verify_scenario2.sh`: ~8 references (assertion checks, grep patterns)

**Historical files** (optional to update):
- `work-history/**/*.md`
- `docs/plan.md`
- `.clinerules/40_resilience_patterns.md`

---

## Implementation Steps

### Step 1: Update ErrorCode.java

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

    public static ErrorCode fromGrpcStatus(io.grpc.Status.Code code) {
        switch (code) {
            case OK:
                return SUCCESS;
            case DEADLINE_EXCEEDED:
                return DEADLINE_EXCEEDED;
            case RESOURCE_EXHAUSTED:
                return BACKEND_ERROR;  // was: return RATE_LIMITED;
            case UNAVAILABLE:
                return UNAVAILABLE;
            default:
                return UNKNOWN;
        }
    }
}
```

### Step 2: Build and verify

```bash
docker build -t app-a:dev -f apps/app-a/Dockerfile .
```

**Expected:** Build succeeds, no compilation errors.

### Step 3: Update README.md

**Search and replace:**
- `RATE_LIMITED` → `BACKEND_ERROR` (all occurrences)

**Specific sections to check:**
- Line ~92: Failure Injection → Expected Results table
- Line ~126: Scenario 2 — What you observe
- Line ~164: Error Taxonomy table
- Line ~228: Scenario 3 — What you observe
- Per-Scenario Detail prose references

**Count before:** `grep -c "RATE_LIMITED" README.md` → ~12

**Count after:** `grep -c "RATE_LIMITED" README.md` → 0

### Step 4: Update docs/plan2.md

**Search and replace:**
- `RATE_LIMITED` → `BACKEND_ERROR`

**Specific sections:**
- Line ~90: FAIL_RATE description
- Line ~108: verify_scenario2.sh description (C08, C09)
- Pattern Inventory table references

**Count before:** `grep -c "RATE_LIMITED" docs/plan2.md` → ~8

**Count after:** `grep -c "RATE_LIMITED" docs/plan2.md` → 0

### Step 5: Update docs/runbook.md

**Search and replace:**
- `RATE_LIMITED` → `BACKEND_ERROR`

**Specific sections:**
- Line ~89: S1 manual spot-check command
- Line ~92: S2 manual spot-check command
- Scenario Reference tables

**Count before:** `grep -c "RATE_LIMITED" docs/runbook.md` → ~3

**Count after:** `grep -c "RATE_LIMITED" docs/runbook.md` → 0

### Step 6: Update tests/verify_scenario2.sh

**Search and replace:**
- `RATE_LIMITED` → `BACKEND_ERROR`

**Specific lines:**
- Line ~25: sum_error_code function grep pattern
- Line ~33: S1_RATE_LIMITED variable name → S1_BACKEND_ERROR
- Line ~34: sum_error_code call
- Line ~38: S2_RATE_LIMITED variable name → S2_BACKEND_ERROR
- Line ~39: sum_error_code call
- Line ~42-50: Assertion messages (C08, C09, C10)

**Example changes:**
```bash
# Before:
S1_RATE_LIMITED=$(sum_error_code "$S1_DIR" "RATE_LIMITED")
S2_RATE_LIMITED=$(sum_error_code "$S2_DIR" "RATE_LIMITED")

# After:
S1_BACKEND_ERROR=$(sum_error_code "$S1_DIR" "BACKEND_ERROR")
S2_BACKEND_ERROR=$(sum_error_code "$S2_DIR" "BACKEND_ERROR")
```

### Step 7: Consider app-b error message (optional)

**File:** `apps/app-b/main.go:55`

**Current:**
```go
return nil, status.Errorf(codes.ResourceExhausted, "rate limited")
```

**Options:**
1. **Keep as-is** — message doesn't have to match ErrorCode enum name
2. **Change to:** `"backend error"` or `"temporary failure"` or `"resource exhausted"`

**Recommendation:** Keep as-is. The message is internal, and changing it doesn't add value. The important part is the gRPC status code (`codes.ResourceExhausted`), not the text.

---

## DoD Proof Commands

### Proof 1: App-a builds successfully

```bash
docker build -t app-a:dev -f apps/app-a/Dockerfile .
```

**Expected output:**
```
Successfully built <hash>
Successfully tagged app-a:dev
```

### Proof 2: No "RATE_LIMITED" in active files

```bash
grep -r "RATE_LIMITED" README.md docs/plan2.md docs/runbook.md tests/verify_scenario2.sh apps/app-a/src/main/java/com/demo/appa/ErrorCode.java
```

**Expected output:**
```
(empty — no matches)
```

### Proof 3: Verify ErrorCode enum

```bash
grep -A3 "public enum ErrorCode" apps/app-a/src/main/java/com/demo/appa/ErrorCode.java
```

**Expected output:**
```java
public enum ErrorCode {
    SUCCESS,
    DEADLINE_EXCEEDED,
    QUEUE_FULL,
    CIRCUIT_OPEN,
    BACKEND_ERROR,    // or similar — no RATE_LIMITED
```

### Proof 4: Verify scenario2 script runs

```bash
# After running scenario 1 and 2
./scripts/run_scenario.sh 1
./scripts/run_scenario.sh 2
./tests/verify_scenario2.sh
```

**Expected output:**
```
PASS C08: S1 BACKEND_ERROR=... (> 1000)
PASS C09: S2 BACKEND_ERROR=... (< 100)
PASS C10: S1 BACKEND_ERROR > S2 BACKEND_ERROR
...
Results: PASS=3 FAIL=0
```

---

## Commit Message

```
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
```

---

## Risk Assessment

**Very low risk:**
- Pure refactoring (no behavior change)
- Compiler catches missed enum references
- Verification script will fail if metrics code doesn't match
- Can smoke-test with scenario runs after rebuild

**No runtime impact:**
- Enum values are compile-time constants
- Metrics labels change: `code="RATE_LIMITED"` → `code="BACKEND_ERROR"`
- Verification scripts updated to match new label

---

## Notes

- Historical files in `work-history/` can stay as-is (they're historical records)
- `.clinerules/40_resilience_patterns.md` is a template — could update for consistency but not required
- `docs/plan.md` (P0-P4 history) — could update but not required
- After merge, rebuild + reload:
  ```bash
  ./scripts/build-images.sh
  ./scripts/load-images-kind.sh
  ```
