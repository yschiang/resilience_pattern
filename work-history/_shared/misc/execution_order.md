# Execution Order — Day-0 Baseline to Full Demo

> **SSOT for task sequencing**
> Architecture invariants: A=2 pods, B=3 pods, B gRPC port=50051, B metrics port=8080, env var=B_DELAY_MS

---

## Section 1: Ordered Task List with Justifications

### P0: Baseline E2E Running

#### T01 — Repo hygiene
**Why here:** Foundation. Must exist before any code. Creates directory structure and documents architecture decisions.
**First proof:**
```bash
ls -la | grep -E "README|apps|chart|scripts"
```
**Unlocks:** All other tasks (provides structure)

---

#### T06 — app-b baseline (C++ gRPC single-thread + delay + /metrics)
**Why here:** B is the dependency. A calls B, so B must exist first. Can be built in parallel with T01.
**First proof:**
```bash
docker build -t app-b:dev ./apps/app-b
```
**Unlocks:** T02 (A needs B service definition)

---

#### T02 — app-a baseline (Spring Boot REST -> 1 gRPC call)
**Why here:** After B exists. A is the entry point but depends on B's gRPC contract.
**First proof:**
```bash
docker build -t app-a:dev ./apps/app-a
```
**Unlocks:** T07 (Helm needs both app images)

---

#### T07 — Helm chart scaffold (A=2, B=3)
**Why here:** After both apps exist. Packages A and B for K8S deployment.
**First proof:**
```bash
helm lint ./chart
```
**Unlocks:** T08 (values overlays need chart structure)

---

#### T08 — Values overlays (common + baseline + placeholders s1/s4)
**Why here:** After chart exists. Enables configuration switching without code changes.
**First proof:**
```bash
ls chart/values-*.yaml | wc -l
# Expected: >= 4
```
**Unlocks:** T03 (metrics collection needs deployed system), T09A (scenario runner needs deployable chart)

---

### P1: Baseline Evidence Ready

#### T03 — Metrics & semantic error taxonomy (A side)
**Why here:** After baseline E2E works. Adds observability to existing A service.
**First proof:**
```bash
kubectl -n demo run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl -s http://a-service:8080/actuator/prometheus | grep "a_downstream_latency_ms"
```
**Unlocks:** T09A (scenario runner needs metrics to collect), T04 (resilience needs taxonomy)

---

#### T09A — Scenario runner: S1 baseline + artifacts
**Why here:** After metrics exist and chart is deployable. First evidence collection.
**First proof:**
```bash
./scripts/run_scenario.sh S1 baseline
```
**Unlocks:** T09B (resilient mode needs baseline for comparison), T11 (verification needs artifacts)

---

### P2: Resilient P0 (Stop the bleeding)

#### T04 — Resilient P0 wrapper (deadline + inflight cap + breaker + flags)
**Why here:** After taxonomy exists. Implements fail-fast patterns on top of baseline A.
**First proof:**
```bash
kubectl -n demo run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl -s http://a-service:8080/actuator/prometheus | grep "QUEUE_FULL"
```
**Unlocks:** T09B (resilient scenario needs resilient code), T05 (P1 builds on P0)

---

#### T09B — Scenario runner: S1 resilient + artifacts
**Why here:** After P0 resilience exists. Proves fail-fast behavior under S1 overload.
**First proof:**
```bash
./scripts/run_scenario.sh S1 resilient
```
**Unlocks:** T11 (verification compares baseline vs resilient)

---

### P3: Resilient P1 (Self-heal + Correlation)

#### T05 — Resilient P1 (self-heal + optional channel pool)
**Why here:** After P0 works. Adds connection recovery and correlation reduction.
**First proof:**
```bash
kubectl -n demo logs deployment/app-a | grep -i keepalive
```
**Unlocks:** T09C (S4 resilient needs P1 self-heal)

---

#### T10 — Fault injection S4 (iptables reset + tc fallback, single A pod)
**Why here:** After Helm chart exists (needs deployed pods). Independent of resilience logic.

**Fault injection runs:** Inside the target A pod container using `kubectl exec`. The pod must have `NET_ADMIN` capability (add to Helm chart securityContext for app-a). The injection script executes `iptables -A OUTPUT -p tcp --dport 50051 -j REJECT --reject-with tcp-reset` inside the pod, waits 15s, then removes the rule.

**First proof:**
```bash
./scripts/inject_s4.sh <pod-name>
kubectl -n demo logs <pod-name> | grep -i UNAVAILABLE
```
**Unlocks:** T09C (S4 scenario needs injection capability)

---

#### T09C — Scenario runner: S4 baseline/resilient + artifacts
**Why here:** After T05 (P1 resilience) and T10 (fault injection) exist.
**First proof:**
```bash
./scripts/run_scenario.sh S4 baseline
grep -i UNAVAILABLE tmp/artifacts/S4/baseline/a-*.log
```
**Unlocks:** T11 (verification needs S4 artifacts)

---

### P4: Verification + Docs

#### T11 — Verification scripts (verify_s1.sh / verify_s4.sh)
**Why here:** After all scenario artifacts exist. Automates pass/fail assessment.
**First proof:**
```bash
./tests/verify_s1.sh
echo $?
# Expected: 0 (pass)
```
**Unlocks:** T12 (docs reference verification commands)

---

#### T12 — Docs packaging (PLAN alignment + runbook)
**Why here:** Last. Ensures all commands/values match actual implementation.
**First proof:**
```bash
cat docs/plan.md | grep -E "helm upgrade.*values-s1"
```
**Unlocks:** Demo readiness

---

## Section 2: P0 Critical Path (Minimal Baseline E2E)

**Goal:** Get `curl http://a-service:8080/api/work` to return JSON via gRPC to B.

**Critical path (sequential dependencies):**
1. **T01** — Repo structure (can run in parallel with next)
2. **T06** — Build app-b (B service must exist first)
3. **T02** — Build app-a (A needs B's contract)
4. **T07** — Helm chart (needs both apps)
5. **T08** — Values overlays (enables deployment)

**P0 Exit proof:**
```bash
kind create cluster --name resilience-pattern
./scripts/build-images.sh
./scripts/load-images-kind.sh
helm upgrade --install demo ./chart -f chart/values-common.yaml -f chart/values-baseline.yaml
kubectl get pods  # expect: 2 A pods, 3 B pods Running
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl -s http://a-service:8080/api/work | jq .
# Expected: {ok: true, code: "SUCCESS", latencyMs: <number>}
```

**Time estimate:** 1-2 days for experienced developer.

---

## Section 3: Risks if Order is Changed

1. **Building A before B (T02 before T06):**
   Risk: A cannot compile without B's gRPC proto definition. Build fails immediately.

2. **Creating values overlays before Helm chart (T08 before T07):**
   Risk: Values files reference chart structure that doesn't exist. Helm lint fails.

3. **Running scenario before metrics exist (T09A before T03):**
   Risk: Artifact collection fails—no prometheus metrics to scrape. Evidence incomplete.

4. **Implementing resilience before taxonomy (T04 before T03):**
   Risk: P0 patterns (QUEUE_FULL, CIRCUIT_OPEN) have no semantic codes to return. Runtime errors.

5. **S4 scenario before fault injection (T09C before T10):**
   Risk: Script tries to inject faults but injection capability missing. S4 run produces no UNAVAILABLE evidence.

---

## Appendix: Parallel Opportunities

**Can run in parallel:**
- T01 (repo hygiene) + T06 (build B) — no dependencies between them
- T10 (fault injection script) can start after T07 (just needs pods), doesn't block T05

**Cannot parallelize (strict dependencies):**
- T06 → T02 (A needs B's proto)
- T07 → T08 (values need chart structure)
- T03 → T09A (artifacts need metrics)
- T04 → T09B (resilient scenario needs resilient code)

---

**End of execution_order.md**
