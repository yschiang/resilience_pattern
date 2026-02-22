# Developer Prompt: Issue #32

## Task

Refactor `BClient` → `AppA` to clarify app-a's dual role (both HTTP server and gRPC client).

## Context

The current naming is ambiguous. "BClient" sounds like "B is a client" but actually means "the client [in app-a] that calls B." Since app-a acts as both server (receiving HTTP) and client (calling app-b via gRPC), the neutral name "AppA" better represents its behavior.

## Your Mission

Rename 4 Java files and update all references in code + docs.

## Step-by-Step Instructions

### 1. Read the plan

```bash
cat tmp/issue-32/plan.md
```

Understand the scope: 4 file renames, WorkController update, doc updates.

### 2. Create feature branch

```bash
git checkout -b 32-refactor-bclient-appa
```

### 3. Rename Java files (use git mv)

```bash
cd apps/app-a/src/main/java/com/demo/appa/

# Interface first (dependency)
git mv BClientPort.java AppAPort.java

# Implementations
git mv BClient.java AppA.java
git mv RetryBClient.java RetryAppA.java
git mv ResilientBClient.java ResilientAppA.java

cd /Users/johnson.chiang/workspace/resilience_pattern
```

### 4. Update class names

**Edit AppAPort.java:**
- Change `public interface BClientPort` → `public interface AppAPort`

**Edit AppA.java:**
- Change `public class BClient implements BClientPort` → `public class AppA implements AppAPort`

**Edit RetryAppA.java:**
- Change `public class RetryBClient implements BClientPort` → `public class RetryAppA implements AppAPort`

**Edit ResilientAppA.java:**
- Change `public class ResilientBClient implements BClientPort` → `public class ResilientAppA implements AppAPort`

### 5. Update WorkController.java

```java
@Autowired
private AppAPort appA;  // was: BClientPort bClient

// In work() method:
WorkResult result = appA.callWork(requestId);  // was: bClient.callWork
```

### 6. Build app-a

```bash
docker build -t app-a:dev -f apps/app-a/Dockerfile .
```

**Expected:** Build succeeds with no errors.

If build fails:
- Check for missed class name updates
- Check for missed interface references
- Grep for "BClient" in apps/app-a/

### 7. Update documentation

**README.md:**
- Search for `` `BClient` `` and replace with `` `AppA` ``
- Search for `` `RetryBClient` `` and replace with `` `RetryAppA` ``
- Search for `` `ResilientBClient` `` and replace with `` `ResilientAppA` ``
- Review each occurrence to ensure context is correct

**docs/plan2.md:**
- Update Pattern Inventory table (line ~61): file names
- Update Client Activation Matrix (line ~73): client names

**Verify no occurrences left:**
```bash
grep -r "BClient" README.md docs/plan2.md docs/runbook.md CONTRIBUTING.md
```

Expected: 0 results (all updated)

### 8. Run DoD proofs

```bash
# Proof 1: Build succeeds
docker build -t app-a:dev -f apps/app-a/Dockerfile .

# Proof 2: No compilation errors
docker build -t app-a:dev -f apps/app-a/Dockerfile . 2>&1 | grep -iE "error|cannot find symbol"
# Expected: empty output

# Proof 3: Verify file names changed
ls apps/app-a/src/main/java/com/demo/appa/*.java | sort
# Expected: AppA.java, AppAPort.java, ResilientAppA.java, RetryAppA.java (no BClient*)

# Proof 4: No "BClient" in docs
grep -r "BClient" README.md docs/plan2.md docs/runbook.md CONTRIBUTING.md | wc -l
# Expected: 0
```

Save outputs to `tmp/issue-32/proof.txt`.

### 9. Commit

```bash
git add .
git commit -m "$(cat <<'EOF'
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
EOF
)"
```

### 10. Push and create PR

```bash
git push origin 32-refactor-bclient-appa
gh pr create --title "Refactor: Rename BClient → AppA to clarify dual role" --body "Fixes #32"
```

### 11. Post DoD proofs to PR

```bash
gh pr comment <PR-number> --body "$(cat tmp/issue-32/proof.txt)"
```

## Checklist

- [ ] Feature branch created
- [ ] All 4 Java files renamed (git mv)
- [ ] Class names updated in all 4 files
- [ ] WorkController updated (field name + reference)
- [ ] App-a builds successfully
- [ ] README.md updated (all BClient references)
- [ ] docs/plan2.md updated (Pattern Inventory, Client Activation)
- [ ] All DoD proofs passed
- [ ] Commit message follows conventional format
- [ ] PR created with issue reference
- [ ] DoD proofs posted to PR

## Common Pitfalls

- ❌ Forgetting to update `implements BClientPort` → `implements AppAPort`
- ❌ Missing WorkController field rename (`bClient` → `appA`)
- ❌ Missing WorkController method call (`bClient.callWork` → `appA.callWork`)
- ❌ Leaving "BClient" in documentation
- ❌ Not using `git mv` (loses file history)

## Questions?

- Check `tmp/issue-32/plan.md` for detailed scope
- Grep for "BClient" to find missed references: `grep -r "BClient" apps/app-a/`
- If build fails, read Docker output carefully for class not found errors
