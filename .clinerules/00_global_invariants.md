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
- Artifacts: `tmp/artifacts/` (gitignored under tmp/)
- Proto: `proto/`

## Working Artifact Convention (tmp/ → work-history/)

Every working artifact MUST be saved to `tmp/<TASK>/` as it is created:

| Artifact | Filename |
|---|---|
| Architect plan / investigation | `tmp/T<N>/plan.md` |
| Developer prompt | `tmp/T<N>/prompt.md` |
| Commit message draft | `tmp/T<N>/commit_msg.txt` |
| DoD proof output | `tmp/T<N>/proof.txt` |
| Explanation / analysis | `tmp/T<N>/<topic>.md` |

**Rules:**
- Create the file at the same time as writing the content — not after
- `tmp/` holds active tasks only (issue still open)
- When a task closes, move `tmp/T<N>/` → `work-history/T<N>/`
- `tmp/artifacts/` is separate (scenario outputs, never moved)

## Proto Contract
- Package: `demo.v1`
- Service: `DemoService` (or similar)
- Method: `Work(WorkRequest) returns (WorkReply)`
- WorkReply fields: `bool ok`, `string code`, `int64 latency_ms`

## Verification Commands
Before ANY commit, verify:
```bash
# Check A pods count in Helm
grep "replicas:" chart/values-common.yaml | head -1
# Expected: replicas: 2

# Check B pods count in Helm
grep "replicas:" chart/values-common.yaml | tail -1
# Expected: replicas: 3

# Check B gRPC port
grep "grpcPort:" chart/values-common.yaml
# Expected: grpcPort: 50051

# Check B metrics port
grep "metricsPort:" chart/values-common.yaml
# Expected: metricsPort: 8080

# Check B env var name
grep "B_DELAY_MS" -r apps/app-b/ chart/
# Expected: Multiple matches (NOT DELAY_MS)

# Check commit message
git show -s --format=%B HEAD | grep -E "Co-authored-by|Signed-off-by"
# Expected: Empty output (no matches)
```

## Why These Constants Are Immutable
- **A=2, B=3:** Demo showcases load balancing and pod-level metrics
- **Ports 50051, 8080:** Standardized across all scenarios
- **B_DELAY_MS:** Semantic clarity (delay is on B side, not A side)
- **Namespace demo:** Isolation from other K8S workloads
- **Commit policy:** Ensures clean, professional commit history

**If you need to change any constant, STOP and consult Architect first.**
