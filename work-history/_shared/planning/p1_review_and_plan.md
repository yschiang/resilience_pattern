# P1 Milestone: Review and Plan

**Generated**: 2026-02-16 08:57
**Current Branch**: master
**Current Commit**: 6524205 (chore: Add .clinerules for Developer agent guidance)
**Status**: Ready to start P1 tasks

---

## PART 1: CURRENT STATE REVIEW

### ✅ P0 Milestone (COMPLETE)

**Merge Commit**: a04a67a (squashed from PR #15)
**Tasks Completed**: T01, T06, T02, T07, T08
**Issues Closed**: #1, #2, #3, #4, #5

**Deliverables Verified**:
- ✅ **apps/app-b/**: Go gRPC service (3 pods, single-thread, B_DELAY_MS)
- ✅ **apps/app-a/**: Spring Boot REST API (2 pods, gRPC client)
- ✅ **chart/**: Helm chart with templates
- ✅ **chart/values-*.yaml**: common, baseline, s1, s4 overlays
- ✅ **proto/demo.proto**: gRPC contract
- ✅ **README.md**: Correct documentation (Go, not C++)

**Architecture Constants Verified**:
```bash
$ grep "replicas:" chart/values-common.yaml
  replicas: 2  # A pods
  replicas: 3  # B pods

$ grep "grpcPort:\|metricsPort:" chart/values-common.yaml
    grpcPort: 50051
    metricsPort: 8080

$ grep "B_DELAY_MS" apps/app-b/main.go chart/values-baseline.yaml
# Multiple matches confirmed - NOT DELAY_MS
```

---

### ✅ .clinerules/ Structure (COMPLETE)

**Commit**: 6524205
**Files Created**: 6 files, 636 lines

```
.clinerules/
├── README.md                      # Entry point (36 lines)
├── 00_global_invariants.md        # A=2, B=3, commit policy (80 lines)
├── 10_workflow_implement_task.md  # 8-step loop (121 lines)
├── 20_workflow_close_issue.md     # Final verification (153 lines)
├── 30_skill_index.md              # Task-to-skill mapping (166 lines)
└── 40_resilience_patterns.md      # Resilience rules P0→P3 (80 lines)
```

**Key Patterns Codified**:
1. **Immutable Architecture**: A=2, B=3, ports 50051/8080, B_DELAY_MS
2. **Commit Policy**: Conventional commits, NO Co-authored-by, ≤80 chars
3. **DoD Bar**: ≥2 runnable proof commands per issue
4. **8-Step Workflow**: Read → Verify → Implement → Proof → Commit → Comment → Verify → Close

---

## PART 2: P1 MILESTONE PLAN

### 🎯 Objective

**P1: Baseline Evidence Ready**

Make the baseline system observable and collectible. After P1, we can:
1. Run S1 scenario and collect evidence
2. Measure baseline behavior (no resilience patterns)
3. Compare against future resilient runs

---

### 📋 P1 Tasks (Sequential)

#### Task 1: T03 - Metrics & Semantic Error Taxonomy (#6)

**Status**: OPEN
**Dependencies**: #3 (CLOSED ✅)
**Milestone**: P1 - Baseline Evidence Ready
**Skill**: resilience_patterns_skill.md

**What to Build**:
1. **Semantic error codes enum** (com.demo.appa.ErrorCode):
   - SUCCESS
   - DEADLINE_EXCEEDED
   - UNAVAILABLE
   - QUEUE_FULL (placeholder for T04)
   - CIRCUIT_OPEN (placeholder for T04)
   - UNKNOWN

2. **Micrometer metrics** (via Spring Boot Actuator):
   - `a_downstream_latency_ms{downstream,method,quantile}` (p95, p99)
   - `a_downstream_errors_total{downstream,method,code}`
   - `a_downstream_inflight{downstream}` (gauge)
   - `a_breaker_state{downstream}` (gauge: 0=closed)

3. **Update BClient** to:
   - Map gRPC StatusRuntimeException → semantic error codes
   - Return error codes in WorkResult
   - Record metrics for each call

4. **Update WorkController** to:
   - Pass error code to response JSON

**Files to Modify**:
- Create: `apps/app-a/src/main/java/com/demo/appa/ErrorCode.java`
- Create: `apps/app-a/src/main/java/com/demo/appa/MetricsService.java`
- Modify: `apps/app-a/src/main/java/com/demo/appa/BClient.java`
- Modify: `apps/app-a/src/main/java/com/demo/appa/WorkController.java`

**DoD Proofs** (from issue #6):
```bash
# Proof 1: Check latency quantiles
curl -s http://localhost:8080/actuator/prometheus | grep "a_downstream_latency_ms.*quantile"
# Expected: p95 and p99 quantiles present

# Proof 2: Check error, inflight, breaker metrics
curl -s http://localhost:8080/actuator/prometheus | grep -E "a_downstream_errors_total|a_downstream_inflight|a_breaker_state"
# Expected: all three metric families present
```

**Implementation Strategy**:
1. Add ErrorCode enum with all codes
2. Create MetricsService with Micrometer Timer/Counter/Gauge
3. Update BClient.callWork() to:
   - Start timer
   - Increment inflight gauge
   - Map StatusRuntimeException.getStatus().getCode() → ErrorCode
   - Record metrics
   - Decrement inflight gauge
4. Update pom.xml if needed (Micrometer already included)
5. Build, test locally, run DoD proofs
6. Commit, comment, close

**Estimated Time**: 1-2 hours

---

#### Task 2: T09A - Scenario Runner S1 Baseline (#7)

**Status**: OPEN
**Dependencies**: #5 (CLOSED ✅), #6 (will be closed first)
**Milestone**: P1 - Baseline Evidence Ready
**Skill**: scenario_artifact_skill.md

**What to Build**:
1. **Script**: `scripts/run_scenario.sh`
   - Args: `<scenario> <mode>` (e.g., `S1 baseline`)
   - Deploy appropriate values overlay
   - Wait for pods to be Ready
   - Run fortio load
   - Collect artifacts
   - Save to `artifacts/<scenario>/<mode>/`

2. **Artifact collection** (for S1 baseline):
   - 1x fortio.txt (load test output)
   - 2x a-<pod>.prom (A prometheus snapshots, A=2 pods)
   - 2x a-<pod>.log (A logs, A=2 pods)
   - 3x b-<pod>.metrics (B metrics, B=3 pods)
   - **Total: 8 files** (NOT 7!)

3. **Load parameters** (from issue):
   - Tool: fortio
   - QPS: 200
   - Concurrency: 80
   - Duration: 60s
   - Warmup: 10s
   - Target: http://a-service:8080/api/work

**Files to Create**:
- `scripts/run_scenario.sh`
- `scripts/collect_artifacts.sh` (optional helper)

**DoD Proofs** (from issue #7):
```bash
# Proof 1: Run scenario
./scripts/run_scenario.sh S1 baseline
# Expected: script completes, prints "Artifacts saved to artifacts/S1/baseline/"

# Proof 2: Count artifacts
ls artifacts/S1/baseline/ | wc -l
# Expected: exactly 8 files (1 fortio + 2 A prom + 2 A log + 3 B metrics)

# Proof 3: Verify pod counts match architecture
ls artifacts/S1/baseline/a-*.prom | wc -l
# Expected: 2 (A=2 pods)

ls artifacts/S1/baseline/b-*.metrics | wc -l
# Expected: 3 (B=3 pods)
```

**Implementation Strategy**:
1. Create scripts/run_scenario.sh with bash script
2. Parse args (scenario, mode)
3. Deploy: `helm upgrade --install demo ./chart -f chart/values-common.yaml -f chart/values-baseline.yaml -f chart/values-s1.yaml`
4. Wait for pods: `kubectl wait --for=condition=Ready pod -l app=app-a -l app=app-b`
5. Run fortio from pod or local:
   ```bash
   kubectl run fortio --rm -it --image=fortio/fortio -- \
     load -qps 200 -c 80 -t 60s http://a-service:8080/api/work
   ```
6. Collect artifacts:
   ```bash
   for pod in $(kubectl get pods -l app=app-a -o name); do
     kubectl exec $pod -- curl -s localhost:8080/actuator/prometheus > artifacts/S1/baseline/${pod}.prom
     kubectl logs $pod > artifacts/S1/baseline/${pod}.log
   done

   for pod in $(kubectl get pods -l app=app-b -o name); do
     kubectl exec $pod -- curl -s localhost:8080/metrics > artifacts/S1/baseline/${pod}.metrics
   done
   ```
7. Verify 8 files exist
8. Run DoD proofs, commit, comment, close

**Prerequisites**:
- kind cluster running
- Images loaded into kind
- Helm chart deployable

**Estimated Time**: 2-3 hours

---

## PART 3: CRITICAL DEPENDENCIES

### Must Read Before Starting

1. **`.clinerules/00_global_invariants.md`** - Architecture constants, commit policy
2. **`.clinerules/10_workflow_implement_task.md`** - 8-step implementation loop
3. **`.clinerules/20_workflow_close_issue.md`** - Final verification workflow
4. **`docs/p1_handoff_summary.md`** - P1 transition context
5. **`skills/resilience_patterns_skill.md`** - T03 guidance
6. **`skills/scenario_artifact_skill.md`** - T09A guidance

### Architecture Constants (IMMUTABLE)

```bash
# Verify before ANY commit:
grep "replicas:" chart/values-common.yaml
# Expected: 2, 3

grep "grpcPort:\|metricsPort:" chart/values-common.yaml
# Expected: 50051, 8080

grep "B_DELAY_MS" -r apps/app-b/ chart/
# Expected: Multiple matches (NOT DELAY_MS)

git show -s --format=%B HEAD | grep -E "Co-authored-by|Signed-off-by"
# Expected: Empty output
```

### Git Workflow for Each Task

```bash
# Before commit:
1. Run ALL DoD proofs
2. Save outputs to /tmp/proof_<N>.txt
3. Verify architecture constants unchanged
4. Create commit with conventional format
5. Gate: verify no Co-authored-by lines
6. Push to feature branch

# After commit:
7. Comment on issue with proof outputs
8. Re-run all proofs as final gate
9. Close issue
10. Proceed to next task
```

---

## PART 4: SUCCESS CRITERIA

### P1 Complete When:

- [ ] T03 (#6) closed with proofs
- [ ] T09A (#7) closed with proofs
- [ ] All commits follow conventional format
- [ ] NO Co-authored-by lines in any commit
- [ ] Architecture constants unchanged (A=2, B=3, ports)
- [ ] Metrics visible in /actuator/prometheus
- [ ] Scenario runner produces exactly 8 artifacts
- [ ] artifacts/ matches architecture (2 A pods, 3 B pods)

### PR Checklist

Before opening PR for P1:
- [ ] Both T03 and T09A closed
- [ ] All DoD proofs passed (documented in issue comments)
- [ ] Metrics endpoint shows all required metrics
- [ ] Scenario runner tested end-to-end
- [ ] Artifact count verified: 8 files (1+2+2+3=8)
- [ ] Clean commit history (git log --oneline)
- [ ] No Co-authored-by lines (verified)
- [ ] Architecture constants unchanged (verified)

---

## PART 5: RISK MITIGATION

### Common Pitfalls

1. **❌ Wrong artifact count**: Issue #7 originally said 7, corrected to 8
   - Verify: 1 fortio + 2 A prom + 2 A log + 3 B metrics = **8 files**

2. **❌ Wrong env var**: B_DELAY_MS (not DELAY_MS)
   - Verify in all code/config changes

3. **❌ Wrong pod counts**: Must remain A=2, B=3
   - Run verification commands before commit

4. **❌ Co-authored-by lines**: Auto-added by some tools
   - Use git commit skill gates to prevent

5. **❌ Missing metrics**: All 4 metric families required
   - latency_ms, errors_total, inflight, breaker_state

6. **❌ Premature close**: Must re-run proofs before closing
   - Follow 20_workflow_close_issue.md exactly

### Hard Gates

**Before ANY commit**:
```bash
# Gate 1: Architecture constants
bash -c '
  grep -q "replicas: 2" chart/values-common.yaml && \
  grep -q "replicas: 3" chart/values-common.yaml && \
  grep -q "grpcPort: 50051" chart/values-common.yaml && \
  echo "✓ Architecture constants OK" || \
  (echo "✗ Architecture constants CHANGED"; exit 1)
'

# Gate 2: Commit message
if git show -s --format=%B HEAD | grep -qE "Co-authored-by|Signed-off-by"; then
  echo "✗ Forbidden commit footer detected"
  exit 1
else
  echo "✓ Commit message clean"
fi
```

---

## PART 6: IMPLEMENTATION ORDER

### T03: Metrics & Error Taxonomy

**Step 1**: Create ErrorCode enum
**Step 2**: Create MetricsService (Micrometer)
**Step 3**: Update BClient to record metrics
**Step 4**: Update WorkController to expose error codes
**Step 5**: Rebuild app-a Docker image
**Step 6**: Test locally with app-b
**Step 7**: Run DoD proofs
**Step 8**: Commit → Comment → Close

### T09A: Scenario Runner

**Step 1**: Create scripts/run_scenario.sh structure
**Step 2**: Implement scenario+mode logic
**Step 3**: Implement helm deploy with correct overlays
**Step 4**: Implement fortio load execution
**Step 5**: Implement artifact collection (8 files)
**Step 6**: Test end-to-end with kind cluster
**Step 7**: Run DoD proofs (verify 8 files)
**Step 8**: Commit → Comment → Close

### After Both Complete

**Step 9**: Review all changes
**Step 10**: Create feature branch for P1
**Step 11**: Push to remote
**Step 12**: Open PR with P1 milestone description
**Step 13**: Wait for architect review

---

## PART 7: EXPECTED OUTCOMES

### After T03

```bash
$ docker run -d -p 8080:8080 -e B_SERVICE_URL=localhost:50051 app-a:dev
$ curl -s http://localhost:8080/actuator/prometheus | grep "a_downstream"

a_downstream_latency_ms{downstream="B",method="Work",quantile="0.95"} 15.3
a_downstream_latency_ms{downstream="B",method="Work",quantile="0.99"} 23.7
a_downstream_errors_total{downstream="B",method="Work",code="SUCCESS"} 142
a_downstream_errors_total{downstream="B",method="Work",code="UNAVAILABLE"} 3
a_downstream_inflight{downstream="B"} 0
a_breaker_state{downstream="B"} 0
```

### After T09A

```bash
$ ./scripts/run_scenario.sh S1 baseline
Deploying S1 baseline configuration...
Waiting for pods to be ready...
Running fortio load (qps=200, concurrency=80, duration=60s)...
Collecting artifacts...
Artifacts saved to artifacts/S1/baseline/

$ ls artifacts/S1/baseline/
fortio.txt
a-app-a-5c7d9f8b6d-abc12.prom
a-app-a-5c7d9f8b6d-def34.prom
a-app-a-5c7d9f8b6d-abc12.log
a-app-a-5c7d9f8b6d-def34.log
b-app-b-6d8e9f7c5b-ghi56.metrics
b-app-b-6d8e9f7c5b-jkl78.metrics
b-app-b-6d8e9f7c5b-mno90.metrics

$ wc -l artifacts/S1/baseline/*
# 8 files total (1+2+2+3=8)
```

---

## PART 8: NEXT STEPS

1. **Start T03**: Read issue #6, implement metrics
2. **Complete T03**: Run proofs, commit, close
3. **Start T09A**: Read issue #7, implement scenario runner
4. **Complete T09A**: Run proofs, commit, close
5. **Open PR**: Feature branch → master with P1 milestone
6. **Review**: Wait for architect approval
7. **Merge**: Squash merge to master
8. **P2**: Begin resilient patterns implementation

---

## SUMMARY

**Current State**: P0 complete, .clinerules/ in place, ready for P1
**P1 Objective**: Observability + evidence collection
**P1 Tasks**: T03 (metrics) → T09A (scenario runner)
**Success Criteria**: 4 metric families + 8 artifacts per run
**Hard Gates**: Architecture constants + commit policy
**Estimated Time**: 3-5 hours total

**Ready to begin!** 🚀
