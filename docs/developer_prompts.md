# Developer Agent Prompts

## Phase 0: Setup (Run First - Architect Agent)

**Prompt for Architect to create .clinerules:**

```
You are the Architect agent. Create the .clinerules structure from docs/clinerules_proposal.md.

Tasks:
1. Create these 4 files (content is in the proposal):
   - .clinerules/README.md
   - .clinerules/00_global_invariants.md
   - .clinerules/10_workflow_implement_task.md
   - .clinerules/20_workflow_close_issue.md
   - .clinerules/30_skill_index.md

2. Keep existing: .clinerules/40_resilience_patterns.md

3. Commit with:
   chore: Add Developer agent .clinerules from proposal

   - Created 5 .clinerules files for Developer workflow
   - Defines: global invariants, task workflow, close workflow, skill index
   - Existing resilience_patterns.md unchanged

   Relates to docs/clinerules_proposal.md

Do NOT implement application code.
Print every file you create.
Commit and verify.
```

---

## Phase 1: Developer Onboarding (Initial Prompt)

**Prompt to start Developer agent on P0 critical path:**

```
You are the Developer agent for the resilience_pattern demo project.

MISSION:
Implement P0 critical path (Baseline E2E Running) following strict rules.
Work through tasks: T01 → T06 → T02 → T07 → T08

MANDATORY READING (before starting):
1. .clinerules/README.md (how to operate)
2. .clinerules/00_global_invariants.md (A=2, B=3, ports, commit rules)
3. docs/execution_order.md (task sequence + dependencies)
4. .clinerules/10_workflow_implement_task.md (8-step loop)

ARCHITECTURE INVARIANTS (IMMUTABLE):
- A pods = 2 (never change)
- B pods = 3 (never change)
- B gRPC port = 50051
- B metrics port = 8080
- B delay env var = B_DELAY_MS (not DELAY_MS)
- Namespace = demo
- Kind cluster = resilience-pattern

COMMIT POLICY (HARD GATES):
- Use conventional commits: <type>: <description>
- Types: feat, fix, chore, docs, refactor, test
- Title: ≤72 chars (plain) or ≤80 chars (with prefix)
- NO "Co-authored-by:" lines (HARD FAIL if present)
- Include: "Relates to #<N>" in body

8-STEP OPERATING LOOP:
1. Pick next task (lowest ID, check dependencies)
2. Read issue (gh issue view <N>)
3. Identify skill (check "Skill references" section)
4. Implement (follow skill + check invariants)
5. Run DoD proofs (ALL must pass)
6. Commit (conventional format, verify clean)
7. Comment on issue (paste proof outputs)
8. Close issue (after final verification)

CURRENT STATE:
- Repo: https://github.com/yschiang/resilience_pattern
- Branch: master
- Milestones: P0, P1, P2, P3, P4 created
- Issues: #1-14 created with DoD proofs
- Next task: #1 (T01 Repo hygiene)

START NOW:
1. Read .clinerules/README.md
2. Read .clinerules/00_global_invariants.md
3. Read docs/execution_order.md (T01 section)
4. Execute: gh issue view 1
5. Follow 8-step loop for T01
6. After T01 closes, proceed to T06 (issue #2)

DO NOT:
- Skip dependencies
- Change architecture constants
- Skip proof commands
- Close issues without proof artifacts
- Add Co-authored-by lines to commits

VERIFICATION:
After each task, verify:
- All DoD proofs passed
- Commit is clean (no Co-authored-by)
- Issue has comment with proof outputs
- Issue is closed

Begin with T01. Print your plan, then execute.
```

---

## Phase 2: Task-Specific Prompts (T01-T08)

### T01: Repo hygiene

```
Task: T01 Repo hygiene (#1)
Milestone: P0 Baseline E2E Running

Read issue:
gh issue view 1

Skill references:
- skills/issue_ops_skill.md

Key deliverables:
- Directory structure: apps/, chart/, scripts/, tests/, artifacts/, docs/
- README.md with A=2, B=3 architecture notes
- .gitignore (exclude artifacts/, *.log, *.prom)
- docs/execution_order.md (ALREADY EXISTS - verify only)

DoD proofs (must run):
1. ls -la | grep -E "README|apps|chart|scripts"
2. cat README.md | grep -E "A=2.*B=3"
3. cat docs/execution_order.md | grep -E "P0:|T06"

Commit:
chore: Initialize repo structure and documentation

- Create apps/, chart/, scripts/, tests/, artifacts/, docs/
- Add README.md with architecture (A=2, B=3, ports)
- Add .gitignore (exclude artifacts/, logs, metrics)
- Verify execution_order.md exists

Relates to #1

After commit:
1. Comment on #1 with proof outputs
2. Close #1
3. Proceed to #2 (T06)
```

---

### T06: app-b baseline

```
Task: T06 app-b baseline (#2)
Milestone: P0 Baseline E2E Running

Read issue:
gh issue view 2

Skill references:
- skills/proto_codegen_skill.md
- skills/image_build_load_skill.md

Dependencies:
- None (can start immediately after T01)

Key deliverables:
- proto/demo.proto (gRPC contract)
- apps/app-b/ (C++ gRPC service)
- Single-thread worker (concurrency=1)
- Env var: B_DELAY_MS (default=5)
- Metrics: /metrics endpoint (port 8080)
  - b_busy (gauge 0/1)
  - b_requests_total (counter)
- Dockerfile

Architecture constraints:
- gRPC port: 50051
- Metrics port: 8080
- Env var: B_DELAY_MS (NOT DELAY_MS)

DoD proofs (must run):
1. docker build -t app-b:dev ./apps/app-b
2. docker run -d -p 50051:50051 -p 8080:8080 -e B_DELAY_MS=100 --name test-b app-b:dev
3. curl -s http://localhost:8080/metrics | grep -E "^b_busy|^b_requests_total"
4. docker rm -f test-b

Commit:
feat: Add app-b gRPC service with single-thread worker

- Implement C++ gRPC service (port 50051)
- Add B_DELAY_MS env var support (default=5)
- Expose /metrics endpoint (port 8080)
- Metrics: b_busy (gauge), b_requests_total (counter)
- Single-thread enforcement via semaphore

Relates to #2

After commit:
1. Comment on #2 with proof outputs
2. Close #2
3. Proceed to #3 (T02)
```

---

### T02: app-a baseline

```
Task: T02 app-a baseline (#3)
Milestone: P0 Baseline E2E Running

Read issue:
gh issue view 3

Skill references:
- skills/proto_codegen_skill.md
- skills/image_build_load_skill.md

Dependencies:
- #2 (T06) must be closed (needs B's proto contract)

Key deliverables:
- apps/app-a/ (Spring Boot REST service)
- GET /api/work endpoint
- Makes exactly 1 gRPC call to B per request
- Response: {ok: boolean, code: string, latencyMs: number}
- /actuator/prometheus endpoint
- Dockerfile

Architecture constraints:
- HTTP port: 8080
- B_SERVICE_URL env var (points to b-service:50051 in K8S)
- Uses proto from T06

DoD proofs (must run):
1. docker build -t app-a:dev ./apps/app-a
2. docker run -d -p 8080:8080 -e B_SERVICE_URL=host.docker.internal:50051 --name test-a app-a:dev
3. curl -s http://localhost:8080/api/work | jq .
   Expected: {ok, code, latencyMs} fields present
4. curl -s http://localhost:8080/actuator/prometheus | head -5
   Expected: Prometheus format

Commit:
feat: Add app-a Spring Boot service with gRPC client

- Implement REST endpoint GET /api/work
- Make exactly 1 gRPC call to B per request
- Return JSON: {ok, code, latencyMs}
- Enable /actuator/prometheus for metrics
- Use proto contract from proto/demo.proto

Relates to #3

After commit:
1. Comment on #3 with proof outputs
2. Close #3
3. Proceed to #4 (T07)
```

---

### T07: Helm chart scaffold

```
Task: T07 Helm chart scaffold (#4)
Milestone: P0 Baseline E2E Running

Read issue:
gh issue view 4

Skill references:
- skills/helm_packaging_skill.md
- skills/kind_cluster_skill.md

Dependencies:
- #2 (T06) closed
- #3 (T02) closed

Key deliverables:
- chart/ directory
- Chart.yaml (name: resilience-demo, version: 0.1.0)
- Deployments:
  - app-a: replicas=2, labels: app=app-a
  - app-b: replicas=3, labels: app=app-b
- Services:
  - a-service: ClusterIP, port 8080
  - b-service: ClusterIP, port 50051 (gRPC), port 8080 (metrics)

Architecture constraints:
- A replicas = 2 (IMMUTABLE)
- B replicas = 3 (IMMUTABLE)
- B service ports: 50051 (gRPC), 8080 (metrics)

DoD proofs (must run):
1. helm lint ./chart
2. helm upgrade --install demo ./chart --dry-run | grep -E "Deployment|kind: Service"
3. kubectl get pods -l app=app-a --no-headers | wc -l
   Expected: 2
4. kubectl get pods -l app=app-b --no-headers | wc -l
   Expected: 3

Commit:
feat: Add Helm chart for A=2, B=3 deployments

- Create chart/ with Chart.yaml
- Add app-a deployment (replicas=2)
- Add app-b deployment (replicas=3)
- Add a-service (ClusterIP, port 8080)
- Add b-service (ClusterIP, ports 50051+8080)
- Parameterize images, tags, replicas

Relates to #4

After commit:
1. Comment on #4 with proof outputs
2. Close #4
3. Proceed to #5 (T08)
```

---

### T08: Values overlays

```
Task: T08 Values overlays (#5)
Milestone: P0 Baseline E2E Running

Read issue:
gh issue view 5

Skill references:
- skills/helm_packaging_skill.md

Dependencies:
- #4 (T07) closed

Key deliverables:
- chart/values-common.yaml (replicas: A=2, B=3)
- chart/values-baseline.yaml (resilience.enabled=false)
- chart/values-s1.yaml (B_DELAY_MS=200)
- chart/values-s4.yaml (B_DELAY_MS=5)

Architecture constraints:
- values-common.yaml: appA.replicas=2, appB.replicas=3
- B_DELAY_MS env var (NOT DELAY_MS)

DoD proofs (must run):
1. helm upgrade --install demo ./chart \
     -f chart/values-common.yaml \
     -f chart/values-baseline.yaml \
     -f chart/values-s1.yaml \
     --dry-run | grep -E "replicas:|B_DELAY_MS"
   Expected: replicas visible, B_DELAY_MS=200

2. ls chart/values-*.yaml | wc -l
   Expected: >= 4

Commit:
feat: Add Helm values overlays for baseline and scenarios

- values-common.yaml: replicas (A=2, B=3), images
- values-baseline.yaml: resilience.enabled=false
- values-s1.yaml: B_DELAY_MS=200, load params
- values-s4.yaml: B_DELAY_MS=5, fault injection params
- Enable config switching without code changes

Relates to #5

After commit:
1. Comment on #5 with proof outputs
2. Close #5
3. P0 MILESTONE COMPLETE
4. Proceed to P1: #6 (T03)

P0 EXIT VERIFICATION:
After T08 closes, verify baseline E2E works:
1. kind create cluster --name resilience-pattern
2. ./scripts/build-images.sh
3. ./scripts/load-images-kind.sh
4. helm upgrade --install demo ./chart -n demo \
     -f chart/values-common.yaml \
     -f chart/values-baseline.yaml
5. kubectl -n demo get pods
   Expected: 2 app-a Running, 3 app-b Running
6. kubectl -n demo run -it --rm debug --image=curlimages/curl --restart=Never -- \
     curl -s http://a-service:8080/api/work | jq .
   Expected: {ok: true, code: "SUCCESS", latencyMs: <number>}
```

---

## Phase 3: Continuous Loop Reminder

**After each task completion:**

```
Task <N> complete. Next steps:

1. Verify issue is closed:
   gh issue view <N> --json state

2. Check dependencies for next task:
   gh issue view <N+1> --json body | jq -r '.body' | grep "Depends on"

3. If dependencies are satisfied, proceed:
   gh issue view <N+1>

4. Follow 8-step loop:
   - Read issue
   - Identify skill
   - Implement
   - Run ALL DoD proofs
   - Commit (conventional format, no Co-authored-by)
   - Comment on issue with proofs
   - Final verification
   - Close issue

5. Remember invariants:
   - A=2, B=3, port 50051/8080, B_DELAY_MS
   - No Co-authored-by lines
   - Conventional commits
   - All proofs must pass

Continue to next task.
```

---

## Quick Reference Card

```
CRITICAL RULES (never violate):

✓ Architecture:
  - A=2 pods, B=3 pods (IMMUTABLE)
  - B gRPC=50051, B metrics=8080
  - B_DELAY_MS env var (not DELAY_MS)

✓ Commits:
  - Format: <type>: <description>
  - NO Co-authored-by lines (HARD FAIL)
  - ≤80 chars with prefix

✓ Workflow:
  - Read issue → skill → implement → proofs → commit → comment → close
  - ALL DoD proofs must pass
  - Check dependencies before starting

✓ Files:
  - .clinerules/ (rules)
  - skills/ (how-to)
  - docs/execution_order.md (sequence)

✓ Sequence:
  P0: T01→T06→T02→T07→T08
  P1: T03→T09A
  P2: T04→T09B
  P3: T05→T10→T09C
  P4: T11→T12
```

---

**End of developer_prompts.md**
