#!/usr/bin/env bash
set -euo pipefail

echo "=== Creating Labels ==="
gh label create "task" --description "Task issue" --color "0052cc" --force
gh label create "area/app-a" --description "Application A (Spring Boot)" --color "d4c5f9" --force
gh label create "area/app-b" --description "Service B (C++ gRPC)" --color "d4c5f9" --force
gh label create "area/helm" --description "Helm chart / K8S deployment" --color "d4c5f9" --force
gh label create "area/scripts" --description "Scripts / runners" --color "d4c5f9" --force
gh label create "area/docs" --description "Documentation" --color "d4c5f9" --force
gh label create "prio/P0" --description "Priority P0" --color "b60205" --force
gh label create "prio/P1" --description "Priority P1" --color "d93f0b" --force
gh label create "prio/P2" --description "Priority P2" --color "fbca04" --force

echo ""
echo "=== Creating Milestones ==="
gh api repos/:owner/:repo/milestones -f title="P0 Baseline E2E Running" -f description="Outcome: kind/K8S runs end-to-end (curl A → gRPC → B) with baseline wiring." --silent || echo "Milestone 'P0 Baseline E2E Running' might already exist"
gh api repos/:owner/:repo/milestones -f title="P1 Baseline Evidence Ready" -f description="Outcome: baseline is measurable with evidence (p99/errors/inflight + B busy_ratio)." --silent || echo "Milestone 'P1 Baseline Evidence Ready' might already exist"
gh api repos/:owner/:repo/milestones -f title="P2 Resilient P0 (Stop the bleeding)" -f description="Outcome: S1 resilient becomes fail-fast (no traffic jam)." --silent || echo "Milestone 'P2 Resilient P0 (Stop the bleeding)' might already exist"
gh api repos/:owner/:repo/milestones -f title="P3 Resilient P1 (Self-heal + Correlation)" -f description="Outcome: S4 connection fault recovers faster; optional channel pool reduces peak." --silent || echo "Milestone 'P3 Resilient P1 (Self-heal + Correlation)' might already exist"
gh api repos/:owner/:repo/milestones -f title="P4 Verification + Docs" -f description="Outcome: one-command demo + pass/fail verification + complete docs." --silent || echo "Milestone 'P4 Verification + Docs' might already exist"

echo ""
echo "=== Creating Issues ==="

# T01 - Repo hygiene
gh issue create --title "T01 Repo hygiene" \
  --milestone "P0 Baseline E2E Running" \
  --label "task,area/docs,prio/P0" \
  --body '**Refs**
- docs/plan.md (SSOT)

**Scope**
- Initialize repo structure (README, .gitignore, basic directories)
- Add build/deploy notes in README
- Create directory structure: apps/, chart/, scripts/, tests/, artifacts/, docs/

**DoD**
Proof commands:
```bash
ls -la
```
Expected: `.gitignore`, `README.md`, `docs/`, `apps/`, `chart/`, `scripts/`, `tests/` exist

```bash
cat README.md | grep -E "A=2.*B=3"
```
Expected: mentions replica counts A=2, B=3

**Depends on**
None

**Out of scope**
- Application code implementation
- Helm chart values (handled in T08)' || echo "Issue T01 might already exist"

# T06 - app-b baseline
gh issue create --title "T06 app-b baseline (C++ gRPC single-thread + delay + /metrics)" \
  --milestone "P0 Baseline E2E Running" \
  --label "task,area/app-b,prio/P0" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 2.1, 2.3

**Scope**
- C++ gRPC service B with single-thread worker (async API but 1 concurrent request at a time)
- Support env var `B_DELAY_MS` (default=5) to inject artificial delay per request
- Expose `/metrics` endpoint with Prometheus text format
- Metrics MUST include: `b_busy` (gauge 0/1), `b_requests_total` (counter)
- Dockerfile for app-b

**DoD**
Proof commands:
```bash
docker build -t app-b:dev ./apps/app-b
docker run -d -p 50051:50051 -p 8080:8080 -e B_DELAY_MS=100 --name test-b app-b:dev
sleep 2
curl -s http://localhost:8080/metrics | grep -E "^b_busy|^b_requests_total"
```
Expected: `b_busy` and `b_requests_total` metrics present

```bash
docker rm -f test-b
```
Expected: cleanup successful

**Depends on**
None

**Out of scope**
- Resilience logic (P0+ features)
- Helm deployment (handled in T07)
- Connection pooling' || echo "Issue T06 might already exist"

# T02 - app-a baseline
gh issue create --title "T02 app-a baseline (Spring Boot REST -> 1 gRPC call)" \
  --milestone "P0 Baseline E2E Running" \
  --label "task,area/app-a,prio/P0" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 2.1

**Scope**
- Spring Boot application A with REST endpoint `GET /api/work`
- Each request makes exactly 1 gRPC call to service B
- Response JSON: `{ok: true/false, code: "string", latencyMs: number}`
- Enable `/actuator/prometheus` for metrics export
- Dockerfile for app-a

**DoD**
Proof commands:
```bash
docker build -t app-a:dev ./apps/app-a
docker run -d -p 8080:8080 -e B_SERVICE_URL=host.docker.internal:50051 --name test-a app-a:dev
sleep 3
curl -s http://localhost:8080/api/work | jq .
```
Expected: JSON with fields `ok`, `code`, `latencyMs`

```bash
curl -s http://localhost:8080/actuator/prometheus | head -5
```
Expected: Prometheus metrics format output

**Depends on**
#T06 (needs B service definition)

**Out of scope**
- Resilience patterns (deadline, bulkhead, breaker - handled in T04)
- Semantic error taxonomy beyond basic SUCCESS/ERROR (handled in T03)' || echo "Issue T02 might already exist"

# T07 - Helm chart scaffold
gh issue create --title "T07 Helm chart scaffold (A=2, B=3)" \
  --milestone "P0 Baseline E2E Running" \
  --label "task,area/helm,prio/P0" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 2.1, 3.1

**Scope**
- Create Helm chart in `chart/` directory
- Deployments for app-a (replicas=2) and app-b (replicas=3)
- Services for A and B
- Optional: loadgen pod for testing
- Chart.yaml and base templates

**DoD**
Proof commands:
```bash
helm upgrade --install demo ./chart --dry-run | grep -E "Deployment|kind: Service"
```
Expected: Shows Deployment and Service resources for A and B

```bash
helm upgrade --install demo ./chart --set image.tag=test
kubectl get pods -l app=app-a
kubectl get pods -l app=app-b
```
Expected: 2 app-a pods and 3 app-b pods in output (or Pending/ImagePullBackOff acceptable for this test)

**Depends on**
#T02, #T06 (needs app images defined)

**Out of scope**
- Values overlays (handled in T08)
- Resilience configuration
- Production-ready monitoring setup' || echo "Issue T07 might already exist"

# T08 - Values overlays
gh issue create --title "T08 Values overlays (common + baseline + placeholders s1/s4)" \
  --milestone "P0 Baseline E2E Running" \
  --label "task,area/helm,prio/P0" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 3

**Scope**
- Create `chart/values-common.yaml` (shared config: replicas A=2, B=3)
- Create `chart/values-baseline.yaml` (resilience.enabled=false)
- Create placeholder `chart/values-s1.yaml` (B_DELAY_MS=200, load params as comments)
- Create placeholder `chart/values-s4.yaml` (B_DELAY_MS=5, channelPoolSize placeholder)
- Verify helm install with overlay merging works

**DoD**
Proof commands:
```bash
helm upgrade --install demo ./chart \
  -f chart/values-common.yaml \
  -f chart/values-baseline.yaml \
  -f chart/values-s1.yaml \
  --dry-run | grep -E "replicas:|B_DELAY_MS"
```
Expected: replicas values visible, B_DELAY_MS=200

```bash
ls chart/values-*.yaml | wc -l
```
Expected: at least 4 (common, baseline, s1, s4)

**Depends on**
#T07 (needs Helm chart structure)

**Out of scope**
- Resilient values overlay (comes later with T04)
- Complete load parameters (refined in T09)' || echo "Issue T08 might already exist"

# T03 - Metrics & semantic error taxonomy
gh issue create --title "T03 Metrics & semantic error taxonomy (A side)" \
  --milestone "P1 Baseline Evidence Ready" \
  --label "task,area/app-a,prio/P1" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 2.2 (P3), 2.3

**Scope**
- Implement semantic error codes in A: DEADLINE_EXCEEDED, UNAVAILABLE, QUEUE_FULL, CIRCUIT_OPEN, UNKNOWN
- Export A metrics via `/actuator/prometheus`:
  - `a_downstream_latency_ms{downstream,method,quantile}` (p95, p99)
  - `a_downstream_errors_total{downstream,method,code}`
  - `a_downstream_inflight{downstream}`
  - `a_breaker_state{downstream}` (can be 0 even if breaker not active yet)
- Ensure taxonomy appears in response JSON `code` field

**DoD**
Proof commands:
```bash
curl -s http://localhost:8080/actuator/prometheus | grep "a_downstream_latency_ms.*quantile"
```
Expected: p95 and p99 quantiles present

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "a_downstream_errors_total|a_downstream_inflight|a_breaker_state"
```
Expected: all three metric families present

**Depends on**
#T02 (extends app-a with metrics)

**Out of scope**
- Actual breaker implementation (handled in T04)
- S1 scenario runner (handled in T09A)' || echo "Issue T03 might already exist"

# T09A - Scenario runner S1 baseline
gh issue create --title "T09A Scenario runner: S1 baseline + artifacts" \
  --milestone "P1 Baseline Evidence Ready" \
  --label "task,area/scripts,prio/P1" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 4.1, 6

**Scope**
- Create `scripts/run_scenario.sh` accepting args: scenario (S1/S4), mode (baseline/resilient)
- For S1 baseline: run fortio load (qps=200, concurrency=80, duration=60s, warmup=10s)
- Collect artifacts into `artifacts/S1/baseline/`:
  - fortio.txt
  - a-<pod>.prom (prometheus metrics snapshot)
  - a-<pod>.log
  - b-<pod>.metrics
- Use `kubectl exec` and `kubectl cp` to gather data

**DoD**
Proof commands:
```bash
./scripts/run_scenario.sh S1 baseline
```
Expected: script completes, prints "Artifacts saved to artifacts/S1/baseline/"

```bash
ls artifacts/S1/baseline/ | wc -l
```
Expected: at least 3 files (fortio.txt, a-*.prom, b-*.metrics)

**Depends on**
#T08 (needs deployable chart), #T03 (needs metrics)

**Out of scope**
- S1 resilient mode (handled in T09B)
- S4 scenarios (handled in T09C)
- Verification scripts (handled in T11)' || echo "Issue T09A might already exist"

# T04 - Resilient P0 wrapper
gh issue create --title "T04 Resilient P0 wrapper (deadline + inflight cap + breaker + flags)" \
  --milestone "P2 Resilient P0 (Stop the bleeding)" \
  --label "task,area/app-a,prio/P0" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 2.2 (P0)

**Scope**
- Implement deadline per gRPC call: `withDeadlineAfter(deadlineMs)` configurable via env
- Implement bounded inflight (bulkhead): `maxInflightPerPod` semaphore
- Return `QUEUE_FULL` when inflight limit exceeded
- Implement circuit breaker per downstream (fail-fast on repeated failures/slow calls)
- Return `CIRCUIT_OPEN` when breaker is open
- Feature flag: `RESILIENCE_ENABLED` (default=false for baseline compatibility)

**DoD**
Proof commands:
```bash
docker run -d -p 8080:8080 -e RESILIENCE_ENABLED=true -e DEADLINE_MS=500 -e MAX_INFLIGHT=10 --name test-a-r app-a:dev
curl -s http://localhost:8080/api/work | jq .code
```
Expected: code is one of SUCCESS, DEADLINE_EXCEEDED, QUEUE_FULL, or CIRCUIT_OPEN

```bash
curl -s http://localhost:8080/actuator/prometheus | grep "a_downstream_errors_total.*QUEUE_FULL"
```
Expected: QUEUE_FULL code appears in metrics (after triggering overload)

**Depends on**
#T03 (needs metrics infrastructure)

**Out of scope**
- Self-heal / connection pool (handled in T05)
- S1 resilient artifacts (handled in T09B)' || echo "Issue T04 might already exist"

# T09B - Scenario runner S1 resilient
gh issue create --title "T09B Scenario runner: S1 resilient + artifacts" \
  --milestone "P2 Resilient P0 (Stop the bleeding)" \
  --label "task,area/scripts,prio/P1" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 4.1, 6

**Scope**
- Extend `scripts/run_scenario.sh` to support S1 resilient mode
- Deploy with `values-resilient.yaml` (RESILIENCE_ENABLED=true, deadlineMs=800, etc.)
- Run same load profile as S1 baseline
- Collect artifacts into `artifacts/S1/resilient/`
- Compare p99 latency and error codes vs baseline

**DoD**
Proof commands:
```bash
./scripts/run_scenario.sh S1 resilient
```
Expected: script completes, prints "Artifacts saved to artifacts/S1/resilient/"

```bash
grep -E "QUEUE_FULL|CIRCUIT_OPEN" artifacts/S1/resilient/a-*.prom | wc -l
```
Expected: at least 1 occurrence of resilience error codes

**Depends on**
#T04 (needs resilient implementation), #T09A (needs runner script)

**Out of scope**
- S4 scenarios (handled in T09C)
- Automated verification (handled in T11)' || echo "Issue T09B might already exist"

# T05 - Resilient P1
gh issue create --title "T05 Resilient P1 (self-heal + optional channel pool)" \
  --milestone "P3 Resilient P1 (Self-heal + Correlation)" \
  --label "task,area/app-a,prio/P1" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 2.2 (P1)

**Scope**
- Implement gRPC keepalive settings (keepalive_time, keepalive_timeout)
- Add controlled reconnect with exponential backoff (avoid tight-loop reconnection)
- Optional: implement `CHANNEL_POOL_SIZE=N` to create N channels and round-robin
- Goal: reduce correlated failure blast radius and improve B pod load distribution

**DoD**
Proof commands:
```bash
docker run -d -p 8080:8080 -e RESILIENCE_ENABLED=true -e CHANNEL_POOL_SIZE=4 --name test-a-pool app-a:dev
curl -s http://localhost:8080/actuator/prometheus | grep "a_channel_pool_size"
```
Expected: metric or config confirms pool size=4 (or similar observable proof)

```bash
docker logs test-a-pool 2>&1 | grep -i "keepalive"
```
Expected: logs mention keepalive configuration (if logged)

**Depends on**
#T04 (builds on resilient P0)

**Out of scope**
- S4 fault injection (handled in T10)
- S4 scenario runner (handled in T09C)' || echo "Issue T05 might already exist"

# T10 - Fault injection S4
gh issue create --title "T10 Fault injection S4 (iptables reset + tc fallback, single A pod)" \
  --milestone "P3 Resilient P1 (Self-heal + Correlation)" \
  --label "task,area/scripts,prio/P2" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 4.2

**Scope**
- Create injection script for S4: target ONE A pod at t=15s for 15s duration
- Primary method: `iptables -A OUTPUT -p tcp --dport 50051 -j REJECT --reject-with tcp-reset`
- Fallback method: `tc qdisc add dev eth0 root netem loss 100%`
- Script should: inject, sleep 15s, remove injection
- Must be callable from run_scenario.sh or standalone

**DoD**
Proof commands:
```bash
./scripts/inject_s4.sh <pod-name>
```
Expected: script runs, prints "Injection started", sleeps 15s, prints "Injection removed"

```bash
kubectl logs <pod-name> | grep -i "UNAVAILABLE\|connection"
```
Expected: logs show connection errors during injection window

**Depends on**
#T07 (needs K8S deployment)

**Out of scope**
- Multi-pod injection (only 1 A pod per spec)
- S4 artifact collection (handled in T09C)' || echo "Issue T10 might already exist"

# T09C - Scenario runner S4 baseline/resilient
gh issue create --title "T09C Scenario runner: S4 baseline/resilient + artifacts" \
  --milestone "P3 Resilient P1 (Self-heal + Correlation)" \
  --label "task,area/scripts,prio/P1" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 4.2, 6

**Scope**
- Extend `scripts/run_scenario.sh` to support S4 baseline and S4 resilient modes
- S4 params: B_DELAY_MS=5, qps=200, concurrency=50, duration=60s
- Trigger fault injection at t=15s via T10 script
- Collect artifacts into `artifacts/S4/baseline/` and `artifacts/S4/resilient/`
- Artifacts must show per-pod UNAVAILABLE burst and recovery timeline

**DoD**
Proof commands:
```bash
./scripts/run_scenario.sh S4 baseline
```
Expected: completes, prints "Artifacts saved to artifacts/S4/baseline/"

```bash
./scripts/run_scenario.sh S4 resilient
ls artifacts/S4/resilient/ | wc -l
```
Expected: at least 3 files in resilient artifacts

**Depends on**
#T05 (needs P1 resilience), #T10 (needs fault injection), #T09B (needs runner base)

**Out of scope**
- Verification logic (handled in T11)
- Comparison charts (manual or future tooling)' || echo "Issue T09C might already exist"

# T11 - Verification scripts
gh issue create --title "T11 Verification scripts (verify_s1.sh / verify_s4.sh)" \
  --milestone "P4 Verification + Docs" \
  --label "task,area/scripts,prio/P2" \
  --body '**Refs**
- docs/plan.md (SSOT) - Section 5

**Scope**
- Create `tests/verify_s1.sh`: assert resilient p99 < baseline p99 (directional)
- Assert resilient has more QUEUE_FULL/CIRCUIT_OPEN errors than baseline
- Create `tests/verify_s4.sh`: assert baseline has UNAVAILABLE burst on injected A pod
- Assert resilient recovers faster (error count drops within N seconds)
- Scripts should parse artifacts and return exit 0 (pass) or exit 1 (fail)

**DoD**
Proof commands:
```bash
./tests/verify_s1.sh
```
Expected: prints "PASS" or "FAIL" with reasoning, exits 0 on success

```bash
./tests/verify_s4.sh
echo $?
```
Expected: exit code 0 (assumes S4 artifacts exist and are valid)

**Depends on**
#T09B (needs S1 artifacts), #T09C (needs S4 artifacts)

**Out of scope**
- Graphical dashboards
- Automated CI integration (future work)' || echo "Issue T11 might already exist"

# T12 - Docs packaging
gh issue create --title "T12 Docs packaging (PLAN alignment + runbook)" \
  --milestone "P4 Verification + Docs" \
  --label "task,area/docs,prio/P2" \
  --body '**Refs**
- docs/plan.md (SSOT)

**Scope**
- Ensure docs/plan.md is up-to-date with actual implementation
- Create or update runbook doc with:
  - One-command drill examples (run_scenario.sh S1/S4 baseline/resilient)
  - Expected outputs and verification commands
  - Troubleshooting common issues
- Confirm all helm commands, proof commands, and artifact paths match reality

**DoD**
Proof commands:
```bash
cat docs/plan.md | grep -E "helm upgrade.*values-s1"
```
Expected: contains accurate helm command examples

```bash
grep -E "run_scenario.sh S1 baseline|run_scenario.sh S4 resilient" docs/*.md | wc -l
```
Expected: at least 2 occurrences across docs (plan + runbook)

**Depends on**
All previous tasks (T01-T11)

**Out of scope**
- Video demos
- External blog posts' || echo "Issue T12 might already exist"

echo ""
echo "=== Final Status ==="
echo ""
echo "Milestones:"
gh api repos/:owner/:repo/milestones --jq '.[] | "\(.number)\t\(.title)\t(\(.open_issues) open)"'
echo ""
echo "Issues:"
gh issue list --limit 50
