# Resilience Demo Plan (SSOT)

> **Goal: Run reproducible S1/S4 demos to compare Baseline vs Resilient, proving that downstream slowness/failure must NOT cause upstream traffic‑jam collapse, and that we can clearly demonstrate fail‑fast, isolation, recovery/self‑heal with evidence (metrics + artifacts).**

---

## Section 1 — Summary (Fast)

| Capability | Demo meaning | Typical Baseline symptom | Target Resilient behavior | Evidence (must capture) |
|---|---|---|---|---|
| Detect fast | Detect downstream slow/unhealthy quickly | Latency keeps rising until it blows up | Breaker opens early, fail-fast | `a_breaker_state`, `a_downstream_latency p99` |
| Deny fast | **No queueing / no traffic jam** | inflight/threads pile up, timeouts spread | return `QUEUE_FULL` / `CIRCUIT_OPEN` quickly | `a_downstream_inflight`, `a_downstream_errors_total{code}` |
| Isolate | Failure impacts only the affected downstream | one downstream drags the whole service | per-downstream bulkhead + breaker | per-downstream labels |
| Recover | Connection failure recovers quickly | UNAVAILABLE burst persists | self-heal + backoff, return to steady state fast | recovery time, UNAVAILABLE curve |
| Diagnose | Errors are semantically readable | everything looks like 500/timeout | taxonomy: DEADLINE / UNAVAILABLE / QUEUE_FULL / CIRCUIT_OPEN | `errors_total{code}` + logs |
| Verify | One-command drill | too many manual steps, not reproducible | `run_scenario.sh` produces evidence | complete `tmp/artifacts/` |

---

## Section 2 — Details

### 2.1 Architecture

**Workload**
- HTTP client → **Application A** (Spring Boot) `GET /api/work`
- Each request in A makes **exactly one** gRPC call to **Service B**
- Response must include:
  - `ok` (true/false)
  - `code` (semantic reason code)
  - `latencyMs`

**Deployment**
- **A = 2 pods** (K8S Deployment replicas=2)
- **B = 3 pods** (K8S Deployment replicas=3)
- Each B pod: **single-thread** (async API but only 1 worker → effectively “only 1 concurrent request at a time”)

**Key architectural risk (intentionally amplified in the demo)**
- Baseline: each A pod uses a **shared gRPC channel/connection** (typically a singleton bean)
- K8S Service + TCP behavior → **connection stickiness**
  - A has only 2 pods → likely only 2 dominant TCP connections → **uneven load across B’s 3 pods**
  - This should show up clearly in B’s `busy_ratio`: some pods `~1`, some pods `~0`

**ASCII view**
```
Client
  |
  v
A (2 pods)  -- gRPC -->  B Service (3 pods, each single-thread)
A-0 ---[1 conn]--------> B-? (sticky)
A-1 ---[1 conn]--------> B-? (sticky)

=> risk: only 2 of 3 B pods get traffic => uneven load + early queueing on hot pods
```

---

### 2.2 Resilience Patterns Policy (P0→P3)

#### P0 MUST (Stop the bleeding)
1) **Deadline per call**
- Every gRPC call must use `withDeadlineAfter(deadlineMs)`
- Demo defaults:
  - S1: `deadlineMs=800`
  - S4: `deadlineMs=500`

2) **Bounded inflight (Bulkhead)**
- Per pod, enforce `maxInflightPerPod` for downstream B
- On reject, return `QUEUE_FULL` (must be a semantic code, not a generic 500)

3) **Circuit Breaker per downstream**
- Per-downstream (optionally per-method); must not be a global shared breaker
- Purpose: fail fast on slow/error conditions; prevent queueing and retry storms

#### P1 SHOULD (Self-heal + reduce correlated failures)
4) **Connection self-heal with backoff**
- gRPC keepalive + controlled reconnect/backoff
- Avoid tight-loop reconnection

5) **Optional channel pool**
- `channelPoolSize=N` (N channels per pod, round-robin)
- Used to:
  - reduce “one bad connection → many inflight die together” peak
  - improve load distribution across B pods (often more even when N ≥ number of B pods)

#### P2 MAY (Controlled degradation)
6) Fallback (optional for demo)
- For `CIRCUIT_OPEN` / `QUEUE_FULL`, return a consistent error contract (with `reason_code`)

#### P3 MUST (Evidence)
7) Semantic error taxonomy (A side)
- `DEADLINE_EXCEEDED`
- `UNAVAILABLE` (including CANCELLED / connection reset)
- `QUEUE_FULL`
- `CIRCUIT_OPEN`
- `RATE_LIMITED` (optional)
- `UNKNOWN`

8) Metrics (must capture both A and B)

---

### 2.3 Observability Spec (Demo-required)

#### A Metrics (`/actuator/prometheus`)
Required (names can differ, but must be equivalent):
- `a_downstream_latency_ms{downstream="B",method=...,quantile="0.95|0.99"}`
- `a_downstream_errors_total{downstream="B",method=...,code=...}`
- `a_downstream_inflight{downstream="B"}`
- `a_breaker_state{downstream="B"}` (0/1/2 or labeled state)

A Logs (recommended structured fields)
- `pod`, `trace_id` (optional), `downstream`, `method`, `code`, `deadline_ms`, `inflight`, `breaker_state`

#### B Metrics (recommended `/metrics`, Prometheus text format)
**Single-thread core metric:**
- `b_busy` gauge: **0/1**
  - 1 while processing a request
  - 0 while idle
- `b_requests_total` counter
- (optional) `b_request_latency_ms` histogram

**Busy ratio definition**
- `inflight_busy_ratio(pod) = avg_over_time(b_busy[1m]) by (pod)`
- Interpretation:
  - ~1: that B pod is saturated
  - ~0: that B pod receives little/no traffic (load imbalance / stickiness)

---

## Section 3 — Configuration Profiles (Helm overlays)

> Standard approach: `values-common + mode + scenario`

### 3.1 Replicas (updated)
- A: 2 pods
- B: 3 pods

### 3.2 Overlay files
- `chart/values-common.yaml`
- `chart/values-baseline.yaml` (`resilience.enabled=false`)
- `chart/values-resilient.yaml` (P0+P3 on)
- `chart/values-s1.yaml` (B_DELAY_MS=200 + load)
- `chart/values-s4.yaml` (iptables reset + load + channelPoolSize)

### 3.3 Deploy commands
S1 baseline
```bash
helm upgrade --install demo ./chart \
  -f chart/values-common.yaml \
  -f chart/values-baseline.yaml \
  -f chart/values-s1.yaml
```

S1 resilient
```bash
helm upgrade --install demo ./chart \
  -f chart/values-common.yaml \
  -f chart/values-resilient.yaml \
  -f chart/values-s1.yaml
```

S4 baseline
```bash
helm upgrade --install demo ./chart \
  -f chart/values-common.yaml \
  -f chart/values-baseline.yaml \
  -f chart/values-s4.yaml
```

S4 resilient
```bash
helm upgrade --install demo ./chart \
  -f chart/values-common.yaml \
  -f chart/values-resilient.yaml \
  -f chart/values-s4.yaml
```

---

## Section 4 — Failure Scenarios (Demo Plan)

> For each scenario: run baseline → resilient (same parameters), and generate artifacts.

### 4.1 S1 — Queueing collapse / saturation
**Purpose**
- Show: downstream capacity shortage → baseline queueing collapse
- Show: resilient (deadline + inflight cap + breaker) → fail-fast (no traffic jam)

**Parameters**
- B: `DELAY_MS=200` (~5 RPS per pod; total ~15 RPS)
- Load (to A):
  - **qps=200**
  - **concurrency=80**
  - warmup=10s
  - duration=60s
  - http_timeout=2s

**Fault injection**
- Use `B_DELAY_MS=200` as the injection (no iptables/tc needed)

**Baseline expected**
- A p99 rises significantly (possibly client timeouts)
- A inflight grows
- errors skew to timeout / DEADLINE_EXCEEDED (depending on A timeout settings)

**Resilient expected**
- p99 is capped near the deadline (or lower if QUEUE_FULL/CB kicks in earlier)
- error shift:
  - `QUEUE_FULL` + `CIRCUIT_OPEN` increase
  - `DEADLINE_EXCEEDED` decreases or at least stops dominating
- A inflight is bounded
- **B busy_ratio is “high but explainable”**
  - might see 2 pods busy~1 and 1 pod busy~0 (baseline stickiness)
  - if channel pool / better distribution is enabled, busy_ratio should become more even (optional comparison)

---

### 4.2 S4 — Connection-level correlated failure (reset one A pod)
**Purpose**
- Show: in the same pod, a shared/bad connection causes many inflight RPCs to fail together (correlated failure)
- Show: resilient self-heal + optional channel pool reduces peak and shortens recovery time

**Parameters**
- B: `DELAY_MS=5` (avoid queueing effects)
- Load (to A):
  - qps=200
  - concurrency=50
  - warmup=10s
  - duration=60s
  - inject at **t=15s** for **15s**

**Fault injection (primary)**
- Pick **ONE** A pod (e.g., A-0) and run inside the pod:
```bash
iptables -A OUTPUT -p tcp --dport 50051 -j REJECT --reject-with tcp-reset
sleep 15
iptables -D OUTPUT -p tcp --dport 50051 -j REJECT --reject-with tcp-reset
```

**Fallback injection**
```bash
tc qdisc add dev eth0 root netem loss 100%
sleep 15
tc qdisc del dev eth0 root
```

**Baseline expected**
- The injected A pod shows an UNAVAILABLE/CANCELLED burst during the injection window
- Correlated burst is obvious (sharp spike in the same second)
- After removing injection, recovery is slower or jittery

**Resilient expected**
- Faster recovery (self-heal effective)
- With `channelPoolSize=4`: peak burst is lower (smaller blast radius)
- Taxonomy stays “clean”: mainly `UNAVAILABLE` (unlike S1 which has DEADLINE/QUEUE_FULL)

---

## Section 5 — Test Cases (Acceptance & Verification)

### 5.1 Test case table
| TC | Scenario | Mode | Directional assertion | Evidence |
|---|---|---|---|---|
| C01 | S1 | baseline | A p99 is high, inflight grows | A prom + fortio |
| C02 | S1 | resilient | A p99 is lower than baseline | A prom |
| C03 | S1 | resilient | `QUEUE_FULL`/`CIRCUIT_OPEN` appears significantly | errors_total{code} |
| C04 | S1 | baseline | at least one B pod has busy_ratio near 1 | B metrics |
| C05 | S4 | baseline | injected A pod has clear UNAVAILABLE burst | per-pod errors_total |
| C06 | S4 | resilient | recovery time is shorter than baseline | errors_total vs time |
| C07 | S4 | resilient(pool=4) | peak burst lower than pool=1 (optional) | max errors-window proxy |

### 5.2 Verification scripts (must exist in demo repo)
- `tests/verify_s1.sh`
  - assert resilient p99 < baseline p99 (approx via grep/awk)
  - assert resilient `QUEUE_FULL|CIRCUIT_OPEN` > baseline
  - assert at least one B pod shows “busy”
- `tests/verify_s4.sh`
  - assert baseline has a per-pod UNAVAILABLE burst on one A pod
  - assert resilient recovers faster (approx via “errors drop within N seconds”)

---

## Section 6 — Runbook (One-command drill)

> Standard interface:
```bash
./scripts/run_scenario.sh S1 baseline
./scripts/run_scenario.sh S1 resilient
./scripts/run_scenario.sh S4 baseline
./scripts/run_scenario.sh S4 resilient
```

### 6.1 Artifacts layout (must generate)
```
tmp/artifacts/
  S1/
    baseline/
      fortio.txt
      a-<pod>.prom
      a-<pod>.log
      b-<pod>.metrics
    resilient/
      ...
  S4/
    baseline/
      ...
    resilient/
      ...
```

### 6.2 What to show in demo (minimum 4 charts/data points)
- A p99 (baseline vs resilient)
- A errors by taxonomy (S1: QUEUE_FULL/CIRCUIT_OPEN; S4: UNAVAILABLE burst)
- A inflight + breaker state (S1 is most convincing)
- B busy_ratio by pod (single-thread saturation + load imbalance)

---

## Section 7 — Definition of Done (Demo-ready)

- **S1/S4 are one-command reproducible**
- baseline/resilient switchable by config only (no code edits)
- artifacts complete (A prom/log + B metrics + fortio output)
- taxonomy correct and per-pod visible
- verification scripts provide at least directional pass/fail

---

## Appendix A — Delivery Plan (Phase-based)

> **Phase = milestone (demo outcome). Task = GitHub issue (finishable in < 1 day).**  
> **Rule:** do Phase 0 first to get E2E baseline running, then add evidence, then resilience, then verification.

### Phase 0 — Baseline E2E Running
**Outcome:** kind/K8S runs end-to-end (curl A → gRPC → B) with baseline wiring.

| Task | Title | Depends on | DoD |
|---|---|---|---|
| T01 | Repo hygiene | — | README/.gitignore/basic structure; build/deploy notes exist |
| T06 | App B single-thread + delay + /metrics | — | B serves gRPC, supports `DELAY_MS`, exposes `b_busy` + `b_requests_total` via `/metrics` |
| T02 | App A baseline | T06 | `/api/work` triggers exactly 1 gRPC call to B; returns `{ok, code, latencyMs}`; `/actuator/prometheus` enabled |
| T07 | Helm chart scaffold | T02, T06 | Deploys A=2 pods, B=3 pods, Services, and loadgen pod |
| T08 | Values overlays (minimum) | T07 | `values-common.yaml` + `values-baseline.yaml` exist and deploy works |

**Exit criteria**
- All pods ready
- `curl http://a-svc/api/work` succeeds and B shows traffic (logs/metrics)

---

### Phase 1 — Baseline Evidence Ready
**Outcome:** baseline is measurable with evidence (p99/errors/inflight + B busy_ratio).

| Task | Title | Depends on | DoD |
|---|---|---|---|
| T03 | Error taxonomy + metrics (P3) | T02 | A exports latency p95/p99, errors by semantic code, inflight gauge, breaker state (even if baseline breaker is disabled) |
| T09 | Scenario runner (S1 baseline first) | T08, T03 | `run_scenario.sh S1 baseline` runs load and collects artifacts (A prom/log + B metrics + fortio) |

**Exit criteria**
- `tmp/artifacts/S1/baseline/` exists with required files
- Can explain baseline pain with numbers

---

### Phase 2 — Resilient P0 (Stop the bleeding)
**Outcome:** S1 resilient becomes fail-fast (no traffic jam).

| Task | Title | Depends on | DoD |
|---|---|---|---|
| T04 | P0 resilient wrapper | T03 | deadline + inflight cap + circuit breaker + feature flags; semantic errors correct |
| T09 | Scenario runner (S1 resilient) | T04 | `run_scenario.sh S1 resilient` produces artifacts and shows improvement |

**Exit criteria**
- S1 resilient: p99 clearly lower/capped; inflight bounded
- Error shift to `QUEUE_FULL` / `CIRCUIT_OPEN`

---

### Phase 3 — Resilient P1 (Self-heal + Correlation reduction)
**Outcome:** S4 connection fault recovers faster; optional channel pool reduces peak.

| Task | Title | Depends on | DoD |
|---|---|---|---|
| T05 | P1 self-heal + channel pool | T04 | keepalive + reconnect/backoff; optional `channelPoolSize` flag works |
| T10 | S4 fault injection | T07 | iptables tcp-reset injection for one A pod; tc netem fallback |
| T09 | Scenario runner (S4 baseline/resilient) | T05, T10 | `run_scenario.sh S4 baseline/resilient` collects artifacts |

**Exit criteria**
- S4 baseline shows per-pod UNAVAILABLE burst on injected A pod
- S4 resilient recovers faster; pool=4 reduces peak (if enabled)

---

### Phase 4 — Verification + Docs Packaging
**Outcome:** one-command demo + pass/fail verification + complete docs.

| Task | Title | Depends on | DoD |
|---|---|---|---|
| T11 | Verification tests | T09 | `verify_s1.sh` and `verify_s4.sh` provide directional pass/fail based on artifacts |
| T12 | Docs completion | All | PLAN + SCENARIOS (+ optional BASELINE/FALLBACK) are consistent with actual commands/values |

**Exit criteria**
- `./scripts/run_scenario.sh ...` works for 4 modes
- `tests/verify_*.sh` returns pass for resilient runs
- Docs are consistent and demo-ready
