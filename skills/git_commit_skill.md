# Skill: Git Commit — Clean, Verified, No Automation Artifacts

## Goal
Enforce human-quality commit messages without automation artifacts (no Co-authored-by, no generated footers).

## Inputs
- **title**: Imperative, ≤72 chars (e.g., "Add circuit breaker to gRPC client")
- **body**: Optional bullets explaining why/what (wrap at 72 chars)
- **scope**: Optional affected area (e.g., "client/grpc", "docs")
- **issue**: Optional reference (e.g., "#42" or "Fixes #42")

---

## Steps

### Step 1: Review Changes
```bash
git status
git diff --cached  # if already staged
git diff           # if not staged
```

### Step 2: Stage Changes (Interactive)
```bash
# Prefer interactive staging for control
git add -p

# Or stage specific files
git add path/to/file1 path/to/file2

# Verify what's staged
git diff --cached --stat
```

### Step 3: Draft Commit Message
Create `/tmp/COMMIT_MSG` with:
```
<title — imperative, ≤72 chars>

<optional body — wrap at 72 chars>
- Bullet explaining why
- Bullet explaining what changed

<optional issue ref: Fixes #42>
```

**HARD GATE**: Message MUST NOT contain:
- `Co-authored-by:`
- `Signed-off-by:` (unless explicitly required by project)
- Any automation footers

### Step 4: Verify Message is Clean
```bash
# Gate: detect forbidden patterns
if grep -qE "(Co-authored-by:|Signed-off-by:)" /tmp/COMMIT_MSG; then
  echo "ERROR: Automation artifacts detected in commit message"
  exit 1
fi
```

### Step 5: Commit Using Message File
```bash
git commit -F /tmp/COMMIT_MSG
```

### Step 6: Verify Final Commit
```bash
# Show commit message
git show -s --format=%B HEAD

# Gate: verify HEAD commit has no artifacts
if git show -s --format=%B HEAD | grep -qE "(Co-authored-by:|Signed-off-by:)"; then
  echo "ERROR: Commit contains forbidden footers. Amending..."
  # Strip artifacts and re-commit
  git show -s --format=%B HEAD | grep -vE "(Co-authored-by:|Signed-off-by:)" > /tmp/CLEAN_MSG
  git commit --amend -F /tmp/CLEAN_MSG
  echo "Commit cleaned and amended."
fi
```

### Step 7: Verify Commit Quality
```bash
# Title length check
TITLE=$(git show -s --format=%s HEAD)
if [ ${#TITLE} -gt 72 ]; then
  echo "WARNING: Title exceeds 72 chars (${#TITLE})"
fi

# Body line length check (optional)
git show -s --format=%b HEAD | awk 'length > 72 {print "WARNING: Line " NR " exceeds 72 chars: " substr($0,1,50) "..."}'
```

---

## Definition of Done (DoD)
- [ ] `git status` shows clean working tree (or expected untracked files)
- [ ] `git show -s --format=%B HEAD` shows clean message (no Co-authored-by)
- [ ] Title is imperative and ≤72 chars
- [ ] Body (if present) wraps at 72 chars
- [ ] Commit represents one logical change
- [ ] No automation artifacts in message

---

## Anti-Patterns (Forbidden)
- ❌ Letting tools auto-append Co-authored-by
- ❌ Committing without reviewing `git diff --cached`
- ❌ Using `git commit -m` for complex changes (use `-F` instead)
- ❌ Vague titles like "fix bug" or "update code"
- ❌ Mixing unrelated changes in one commit

---

## Examples

### Good Commit
```
Add circuit breaker to gRPC downstream client

- Wrap BaselineBClient with Resilience4j CircuitBreaker
- Open after 50% error rate in 10-call window
- Fail fast with CIRCUIT_OPEN error when open

Fixes #23
```

### Bad Commit (Automation Artifacts)
```
Add circuit breaker

Co-authored-by: Claude Sonnet 4.5 <noreply@anthropic.com>
```
^ This will be REJECTED by gates in Step 4 and auto-cleaned in Step 6.
