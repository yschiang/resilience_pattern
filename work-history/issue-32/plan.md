# Issue #32 Plan: Refactor BClient → AppA

## Problem

App-a acts as both HTTP server (receiving from Fortio) and gRPC client (calling app-b). The current naming `BClient` is ambiguous — it sounds like "B is a client" but means "the client [in app-a] that calls B."

## Proposed Solution

Rename classes to `AppA` — neutral naming that represents app-a's behavior without specifying client/server role.

## Scope

### Files to Rename

| Current | New | Lines |
|---------|-----|-------|
| `BClient.java` | `AppA.java` | ~80 |
| `RetryBClient.java` | `RetryAppA.java` | ~70 |
| `ResilientBClient.java` | `ResilientAppA.java` | ~180 |
| `BClientPort.java` | `AppAPort.java` | ~5 |

### Files to Update (imports/references)

**Java code:**
- `WorkController.java` — field name `bClient` → `appA`, type `BClientPort` → `AppAPort`
- `BClient.java` → `AppA.java` — class name, implements `AppAPort`
- `RetryBClient.java` → `RetryAppA.java` — class name, implements `AppAPort`
- `ResilientBClient.java` → `ResilientAppA.java` — class name, implements `AppAPort`

**Documentation (14 files with "BClient" references):**
- `README.md` — update client class references (Architecture, Per-Scenario Detail, etc.)
- `docs/plan2.md` — update Pattern Inventory table, Client Activation Matrix
- `docs/runbook.md` — if any references exist
- `CONTRIBUTING.md` — recently added, may have example references
- `work-history/**/*.md` — historical records (optional to update, as they're historical)
- `skills/*.md` — if any references exist

---

## Implementation Steps

### Step 1: Rename Java Files (use git mv for history preservation)

```bash
cd apps/app-a/src/main/java/com/demo/appa/

# Rename interface first (dependencies)
git mv BClientPort.java AppAPort.java

# Rename implementations
git mv BClient.java AppA.java
git mv RetryBClient.java RetryAppA.java
git mv ResilientBClient.java ResilientAppA.java
```

### Step 2: Update Class Names and Implements Clause

**AppAPort.java:**
```java
public interface AppAPort {  // was: BClientPort
    WorkResult callWork(String requestId);
}
```

**AppA.java:**
```java
@Component
@ConditionalOnExpression("'${resilience.enabled:false}' == 'false' && '${retry.enabled:false}' == 'false'")
public class AppA implements AppAPort {  // was: BClient implements BClientPort
    // ... rest unchanged
}
```

**RetryAppA.java:**
```java
@Component
@ConditionalOnExpression("'${retry.enabled:false}' == 'true' && '${resilience.enabled:false}' == 'false'")
public class RetryAppA implements AppAPort {  // was: RetryBClient implements BClientPort
    // ... rest unchanged
}
```

**ResilientAppA.java:**
```java
@Component
@ConditionalOnProperty(name = "resilience.enabled", havingValue = "true")
public class ResilientAppA implements AppAPort {  // was: ResilientBClient implements BClientPort
    // ... rest unchanged
}
```

### Step 3: Update WorkController.java

```java
@RestController
@RequestMapping("/api")
public class WorkController {
    private static final Logger logger = LoggerFactory.getLogger(WorkController.class);

    @Autowired
    private AppAPort appA;  // was: BClientPort bClient

    @GetMapping("/work")
    public WorkResponse work() {
        String requestId = UUID.randomUUID().toString();
        logger.info("Handling /api/work request: {}", requestId);

        WorkResult result = appA.callWork(requestId);  // was: bClient.callWork

        return new WorkResponse(
                result.isOk(),
                result.getCode(),
                result.getLatencyMs()
        );
    }
    // ... rest unchanged
}
```

### Step 4: Build and Verify

```bash
# Build app-a
cd /Users/johnson.chiang/workspace/resilience_pattern
docker build -t app-a:dev -f apps/app-a/Dockerfile .

# Expected: Build succeeds, no compilation errors
```

### Step 5: Update Documentation

**README.md — search and replace:**
- `` `BClient` `` → `` `AppA` ``
- `` `RetryBClient` `` → `` `RetryAppA` ``
- `` `ResilientBClient` `` → `` `ResilientAppA` ``
- "BClient" (prose) → "AppA" (where appropriate)

**Specific locations in README:**
- Line ~103: "No patterns. `BClient` makes a plain gRPC call" → "`AppA` makes..."
- Line ~112: "`RetryBClient`" → "`RetryAppA`"
- Line ~132: "`ResilientBClient` activates" → "`ResilientAppA` activates"
- Line ~153: "`ResilientBClient` with `CHANNEL_POOL_SIZE=4`" → "`ResilientAppA`..."
- Line ~315: Error Taxonomy table — update file references

**docs/plan2.md:**
- Line 61: Pattern Inventory table — update `RetryBClient.java`, `ResilientBClient.java` → `RetryAppA.java`, `ResilientAppA.java`
- Line 73-77: Client Activation Matrix — update client names

**CONTRIBUTING.md:**
- Check for any example references (likely none, but verify)

**work-history/ and skills/:**
- Optional: these are historical records, can be left as-is or updated for consistency

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

### Proof 2: No compilation errors (grep for common issues)

```bash
docker build -t app-a:dev -f apps/app-a/Dockerfile . 2>&1 | grep -iE "error|cannot find symbol"
```

**Expected output:**
```
(empty — no errors)
```

### Proof 3: Verify class names changed

```bash
ls apps/app-a/src/main/java/com/demo/appa/*.java | sort
```

**Expected output:**
```
apps/app-a/src/main/java/com/demo/appa/AppA.java
apps/app-a/src/main/java/com/demo/appa/AppAPort.java
apps/app-a/src/main/java/com/demo/appa/ErrorCode.java
apps/app-a/src/main/java/com/demo/appa/MetricsService.java
apps/app-a/src/main/java/com/demo/appa/ResilientAppA.java
apps/app-a/src/main/java/com/demo/appa/RetryAppA.java
apps/app-a/src/main/java/com/demo/appa/WorkController.java
apps/app-a/src/main/java/com/demo/appa/WorkResult.java
```

(No BClient.java, BClientPort.java files present)

### Proof 4: Verify documentation updated

```bash
grep -r "BClient" README.md docs/plan2.md docs/runbook.md CONTRIBUTING.md | wc -l
```

**Expected output:**
```
0
```

(No occurrences of "BClient" in active documentation)

---

## Commit Message

```
refactor: rename BClient → AppA to clarify dual role

App-a acts as both HTTP server and gRPC client. The old naming
"BClient" was ambiguous (sounds like "B is a client"). New naming
"AppA" is neutral and represents app-a's behavior regardless of role.

Renames:
- BClientPort → AppAPort
- BClient → AppA
- RetryBClient → RetryAppA
- ResilientBClient → ResilientAppA

Updated WorkController field: bClient → appA
Updated documentation: README, plan2.md

Fixes #32
```

---

## Risk Assessment

**Low risk:**
- Pure refactoring (no behavior change)
- All changes in app-a only (app-b unaffected)
- Type-safe (compiler will catch missed references)
- Spring's @Component + @ConditionalOn* ensure only one bean active

**Verification:**
- Docker build catches compilation errors
- No runtime changes (just names)
- Can smoke-test with: `./scripts/run_scenario.sh 1` after rebuild + reload

---

## Notes

- Use `git mv` for file renames to preserve history
- Consider updating `work-history/` and `skills/` for consistency (optional)
- After merge, will need to rebuild + reload images:
  ```bash
  ./scripts/build-images.sh
  ./scripts/load-images-kind.sh
  ```
