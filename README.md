# Resilience Pattern Demo

A progressive learning roadmap for five gRPC resilience patterns. Each of four
scenarios adds one group of patterns and one new failure mode — run them in
order to watch each pattern's contribution in isolation.

---

## Table of Contents

- [The Four Scenarios](#the-four-scenarios)
- [Architecture](#architecture)
- [Coverage Matrix](#coverage-matrix)
- [Error Classification & Retry Gating](#error-classification--retry-gating)
  - [Two-Layer Error Model: Internal vs External](#two-layer-error-model-internal-vs-external)
  - [Example Flow: Exception → Classification → Retry → HTTP Response](#example-flow-exception--classification--retry--http-response)
- [Observability](#observability)
  - [App-A: Error Classification Metrics](#app-a-error-classification-metrics)
  - [App-B: Flow Counters & Latency Histograms](#app-b-flow-counters--latency-histograms)
- [Quick Start](#quick-start)
- [Pattern Inventory](#pattern-inventory)
- [Failure Injection → Expected Results](#failure-injection--expected-results)
- [Per-Scenario Detail](#per-scenario-detail)
  - [Scenario 1: Baseline](#scenario-1-baseline)
  - [Scenario 2: Retry — +Retry + Idempotency](#scenario-2-retry--retry--idempotency)
  - [Scenario 3: Failfast — +Deadline + Bulkhead + Circuit Breaker](#scenario-3-failfast--deadline--bulkhead--circuit-breaker)
  - [Scenario 4: Selfheal — +Keepalive + Channel Pool](#scenario-4-selfheal--keepalive--channel-pool)
- [Configuration](#configuration)
- [Project Structure](#project-structure)

---

## The Four Scenarios

| # | Name | Patterns added | Failure injection | Observable result |
|---|---|---|---|---|
| 1 | Baseline | none | FAIL_RATE=0.3 | Raw failure propagates |
| 2 | +Retry+Idempotency | Resilience4j retry, dedup in B | same | Retryable errors drop 30% → 3% |
| 3 | +Deadline+Bulkhead+CB | deadline, inflight limit, circuit breaker | + B_DELAY_MS=200 (slow B) | Fast-fail contains overload |
| 4 | +Keepalive+ChannelPool | keepalive, channel pool | + iptables tcp-reset | Connection failure self-heals |

## Architecture

```
HTTP client
    │  200 QPS
    ▼
App A  ×2 pods  (Spring Boot, :8080)
    │  gRPC :50051
    ▼
App B  ×3 pods  (Go, single-threaded)
```

App B is intentionally single-threaded — ~5 RPS per pod, 15 RPS total
capacity. At 200 QPS the mismatch is 13×, making saturation and connection
failures easy to reproduce.

---

## Coverage Matrix

| Failure mode | Scenario 1: Baseline | Scenario 2: Retry | Scenario 3: Failfast | Scenario 4: Selfheal |
|---|---|---|---|---|
| RESOURCE_EXHAUSTED (30%) | ❌ | ✅ | ✅ | ✅ |
| Slow B / overload | ❌ | ❌ worse | ✅ | ✅ |
| TCP connection reset | ❌ | ❌ | ❌ slow | ✅ |

Scenario 2: Retry deliberately makes overload *worse* before Scenario 3: Failfast fixes it —
the most important anti-pattern lesson: **retry amplifies load without a
circuit breaker**.

---

## Error Classification & Retry Gating

**Error Classification** — `GrpcErrorClassifier` maps exceptions to semantic error reasons:
- **Input:** `Throwable` + optional `contextHint` (e.g., "CIRCUIT_OPEN")
- **Output:** `CallOutcome{reason, retryable, grpcStatus}`
- **Location:** `apps/app-a/src/main/java/com/demo/appa/observability/GrpcErrorClassifier.java`

**Retry Gating** — `RetryDecisionPolicy` uses classifier output to determine retry eligibility:
- Uses `CallOutcome.retryable()` as retry predicate for Resilience4j Retry
- **CRITICAL safety constraint:** Protection events (CIRCUIT_OPEN, BULKHEAD_REJECTED) NEVER retried
- Unknown errors default to non-retryable (conservative fail-safe)
- **Location:** `apps/app-a/src/main/java/com/demo/appa/retry/RetryDecisionPolicy.java`

---

### Two-Layer Error Model: Internal vs External

App-A separates **internal observability** (semantic names like `CONNECTION_FAILURE` for metrics/retry decisions) from **external API contracts** (stable codes like `UNAVAILABLE` for HTTP responses). This lets internal telemetry evolve without breaking clients.

#### Layer 1: ErrorReason (INTERNAL - semantic classification)

Used internally for metrics labels, retry decisions, and observability analysis.

| ErrorReason | gRPC Status | Retryable? | Rationale | Scenario |
|---|---|---|---|---|
| `SUCCESS` | (no error) | N/A | — | All |
| `BACKEND_ERROR` | RESOURCE_EXHAUSTED | ✅ **Yes** | Backend may recover after brief backoff | S2 |
| `CONNECTION_FAILURE` | UNAVAILABLE | ✅ **Yes** | Transient network issue, reconnect may succeed | S4 |
| `TIMEOUT` | DEADLINE_EXCEEDED | ❌ **No** | Deadline already exceeded; retry amplifies load | S3 |
| `CIRCUIT_OPEN` | (protection event) | ❌ **No** | **Safety:** Retrying defeats circuit breaker | S3, S4 |
| `BULKHEAD_REJECTED` | (protection event) | ❌ **No** | **Safety:** Retrying defeats bulkhead | S3, S4 |
| `CLIENT_ERROR` | INVALID_ARGUMENT, etc. | ❌ **No** | Client-side bug; won't succeed on retry | All |
| `SERVER_ERROR` | INTERNAL, DATA_LOSS, etc. | ❌ **No** | Backend bug; retry won't help | All |
| `UNKNOWN` | UNKNOWN, unmapped | ❌ **No** | Conservative default for safety | All |

**Where used:** Prometheus metrics (`reason` label), retry predicate, logging, root cause analysis
**Classification logic:** `GrpcErrorClassifier.java:36-89`

#### Layer 2: ErrorCode (EXTERNAL - HTTP API contract)
Used for external HTTP responses to Fortio client (maps closer to gRPC status names).

| ErrorCode | Maps from ErrorReason | HTTP Response | Where set |
|---|---|---|---|
| `SUCCESS` | SUCCESS | 200 OK | Success path |
| `BACKEND_ERROR` | BACKEND_ERROR | 500 | B's FAIL_RATE |
| `UNAVAILABLE` | CONNECTION_FAILURE | 503 | TCP reset |
| `DEADLINE_EXCEEDED` | TIMEOUT | 504 | Deadline hit |
| `QUEUE_FULL` | BULKHEAD_REJECTED | 503 | Bulkhead full |
| `CIRCUIT_OPEN` | CIRCUIT_OPEN | 503 | CB rejection |
| `UNKNOWN` | UNKNOWN, CLIENT_ERROR, SERVER_ERROR | 500 | Fallback |

**Where used:** REST API `WorkResult` response, test verification scripts
**Mapping logic:** `ErrorCode.fromGrpcStatus()` in catch blocks

---

### Example Flow: Exception → Classification → Retry → HTTP Response

```
INTERNAL (App-A observability):
  gRPC exception: StatusRuntimeException(RESOURCE_EXHAUSTED)
    → GrpcErrorClassifier.classify()
    → CallOutcome{reason=BACKEND_ERROR, retryable=true}
    → Metrics: grpc_client_requests_total{reason="BACKEND_ERROR", retryable="true"}
    → RetryDecisionPolicy.shouldRetry() → true (retry allowed)
    → (after retries exhaust...)

EXTERNAL (HTTP API response):
    → ErrorCode.fromGrpcStatus(RESOURCE_EXHAUSTED) → BACKEND_ERROR
    → WorkResult{success=false, code="BACKEND_ERROR", latency=120}
    → HTTP 500 response to Fortio client
```

---

## Observability

### App-A: Error Classification Metrics

**Metric:** `grpc_client_requests_total{method, service, reason, retryable, result}`

**Purpose:** Semantic error classification for retry decisions and root cause analysis

**Key labels:**
- `reason`: ErrorReason (BACKEND_ERROR, TIMEOUT, CIRCUIT_OPEN, etc.)
- `retryable`: "true" or "false" (drives retry decisions)
- `result`: "SUCCESS" or "FAILURE"

**Example queries:**
```promql
# Error rate by reason
rate(grpc_client_requests_total{result="FAILURE"}[1m])

# Retryable vs non-retryable failures
sum by (retryable) (rate(grpc_client_requests_total{result="FAILURE"}[1m]))

# Circuit breaker shed rate
rate(grpc_client_requests_total{reason="CIRCUIT_OPEN"}[1m])
```

**Latency:** `grpc_client_latency_ms_seconds_bucket{method, service}`
- Histogram for p50, p95, p99 calculations
- Buckets: 0.01, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, +Inf seconds

---

### App-B: Flow Counters & Latency Histograms

**Flow counters** track request progression through the single-threaded worker:

| Metric | Description |
|---|---|
| `b_requests_received_total` | All requests entering `Work()` handler (cache hits, fail injection, normal) |
| `b_requests_started_total` | Requests that acquired the worker mutex (entered single-thread section) |
| `b_requests_completed_total` | Successful completions (work done inside mutex) |
| `b_requests_failed_total{reason}` | Failures by reason (`reason="fail_injection"`) |
| `b_busy` | Worker utilization: 1 while holding mutex, 0 otherwise |

**Metric relationships:**
```
received_total >= started_total + failed_total
started_total  >= completed_total
```

**Derived quantities:**
- **Queue depth** (waiting for mutex): `received - started - failed`
- **Worker utilization**: `b_busy` (1=busy, 0=idle)

**Latency histograms** preserve tail latency visibility:

| Metric | Labels | Description |
|---|---|---|
| `b_request_latency_ms{outcome}` | `cache_hit`, `success`, `failure` | End-to-end handler latency (ms) — all code paths |
| `b_processing_latency_ms{outcome}` | `success`, `failure` | Worker processing latency (ms) — mutex holders only |
| `b_queue_wait_ms{outcome}` | `acquired` | Queue wait time (ms) — handler entry to mutex acquisition |

**Latency relationships:**
```
b_request_latency_ms ≈ b_queue_wait_ms + b_processing_latency_ms  (for success path)
```

**Why separate histograms?**
- Fast-fail paths (cache hits ~1ms, fail injection ~5ms) would wash out slow-path averages
- Separate `outcome` labels preserve tail latency visibility (p95, p99)
- Queue wait vs processing latency separation enables saturation diagnosis

**Example queries:**
```promql
# p95 end-to-end latency by outcome
histogram_quantile(0.95, sum by (outcome, le) (rate(b_request_latency_ms_bucket[1m])))

# Queue depth (waiting for mutex)
b_requests_received_total - b_requests_started_total - b_requests_failed_total

# Worker utilization (should be near 1.0 under saturation)
avg_over_time(b_busy[1m])

# p99 queue wait time (high = saturation)
histogram_quantile(0.99, sum by (le) (rate(b_queue_wait_ms_bucket[1m])))
```

**What to observe in each scenario:**

| Scenario | App-A metrics | App-B metrics |
|---|---|---|
| Baseline | 30% `BACKEND_ERROR` | High queue wait (500-2000ms), `b_busy`≈1.0 |
| Retry | 3% `BACKEND_ERROR` (retry working) | `received_total` > `started_total` (cache hits), `b_busy`≈1.0 |
| Failfast | `CIRCUIT_OPEN` dominant (83% of traffic) | Low queue wait (CB sheds load before queuing), `b_busy`<1.0 |
| Selfheal | Burst of `UNAVAILABLE` (t=15-45s) | Normal queue wait, self-heals after keepalive detection |

See `apps/app-b/README.md` for complete Service B metrics documentation.

---

## Quick Start

```bash
# Build and load images
./scripts/build-images.sh
./scripts/load-images-kind.sh

# Run scenarios in order
./scripts/run_scenario.sh baseline   # baseline — raw failure
./scripts/run_scenario.sh retry      # +retry+idempotency
./scripts/run_scenario.sh failfast   # +deadline+bulkhead+CB (slow B)
./scripts/run_scenario.sh selfheal   # +keepalive+pool (+TCP reset at t=15s)

# Verify
./tests/verify_retry.sh   # PASS=3 FAIL=0
./tests/verify_failfast.sh   # PASS=2 FAIL=0
./tests/verify_selfheal.sh   # PASS=3 FAIL=0
```

Each run deploys via Helm, runs a 60 s Fortio load test, and saves 8 artifacts
to `tmp/artifacts/scenarios/`.

---

## Pattern Inventory

| Pattern | Added in | File | Mechanism |
|---|---|---|---|
| Resilience4j Retry | Scenario 2: Retry | `AppARetry.java`, `AppAResilient.java`, `RetryDecisionPolicy.java` | maxAttempts=3, waitDuration=50ms, classifier-based predicate (retries only errors marked `retryable=true`) |
| Idempotency dedup | Scenario 2: Retry | `app-b/main.go` | `seenRequests sync.Map` keyed on `req.Id`, 30 s TTL |
| Deadline | Scenario 3: Failfast | `AppAResilient.java` | `withDeadlineAfter(800ms)` |
| Bulkhead | Scenario 3: Failfast | `AppAResilient.java` | `Semaphore.tryAcquire(MAX_INFLIGHT=10)` |
| Circuit Breaker | Scenario 3: Failfast | `AppAResilient.java` | Resilience4j `COUNT_BASED(10)`, 50% threshold |
| gRPC Keepalive | Scenario 4: Selfheal | `AppAResilient.java` | HTTP/2 PING every 30 s, 10 s timeout |
| Channel Pool | Scenario 4: Selfheal | `AppAResilient.java` | N `ManagedChannel` instances, round-robin `AtomicInteger` |

---

## Failure Injection → Expected Results

| Scenario | Failure injected | Expected observation |
|---|---|---|
| baseline | `FAIL_RATE=0.3` — B injects `RESOURCE_EXHAUSTED` randomly | ~30% `BACKEND_ERROR`; all propagate to caller |
| retry | Same `FAIL_RATE=0.3` | `BACKEND_ERROR` drops ~10×; retry amplifies load when B is slow (hidden cost) |
| failfast | `FAIL_RATE=0.3` + `B_DELAY_MS=200` (slow B) | `QUEUE_FULL` + `CIRCUIT_OPEN` > 100; max latency lower than baseline |
| selfheal | `FAIL_RATE=0.3` + iptables tcp-reset at t=15 s | `UNAVAILABLE` > 50 (fault visible); `SUCCESS` > 10,000; `UNAVAILABLE` < 10% of `SUCCESS` (self-heal) |

---

## Per-Scenario Detail

### Scenario 1: Baseline

**Call chain:**
```
HTTP client (Fortio, 200 QPS)
  ↓ no patterns
app-a: WorkController
  ↓ generates UUID
app-a: AppA (plain gRPC)
  ↓ WorkRequest{id=uuid}
app-b: 30% RESOURCE_EXHAUSTED, 70% SUCCESS (5 ms)
  ↓ error propagates unchanged
HTTP client: sees 30% BACKEND_ERROR
```

**Configuration:**
- Client: `AppABaseline` (plain gRPC, no patterns)
- B behavior: `FAIL_RATE=0.3`, `B_DELAY_MS=5`
- Load: 200 QPS for 60 s (12,000 total requests)

**Capacity vs load:**
- B capacity: 3 pods × ~5 RPS = 15 RPS total
- Incoming: 200 QPS
- **Overload: 13×** (200/15)

**What you observe:**
- `BACKEND_ERROR` errors: ~3,600 (30% of 12,000)
- No retry, no dedup — every failure visible
- Latency: p50 ~5 ms, p99 ~50 ms (queue backlog)

---

### Scenario 2: Retry — +Retry + Idempotency

**Call chain:**
```
HTTP client (Fortio, 200 QPS)
  ↓
app-a: WorkController
  ↓ generates UUID once: "abc-123"
app-a: RetryAppA (maxAttempts=3)
  ↓ WorkRequest{id="abc-123"}
  ↓ attempt 1 → RESOURCE_EXHAUSTED (30% chance)
  ↓ 50 ms backoff
  ↓ attempt 2 (same id) → app-b checks cache → miss or retry
  ↓ 100 ms backoff
  ↓ attempt 3 (same id) → app-b checks cache → HIT (no work)
  ↓
HTTP client: sees 3% BACKEND_ERROR (down from 30%)
```

**Configuration:**
- Client: `AppARetry` (Resilience4j retry only, no CB/bulkhead/deadline)
- Retry policy: `maxAttempts=3`, `waitDuration=50ms`, classifier-based gating
  - **Retries:** `BACKEND_ERROR` (RESOURCE_EXHAUSTED), `CONNECTION_FAILURE` (UNAVAILABLE)
  - **No retry:** `TIMEOUT`, `CLIENT_ERROR`, `SERVER_ERROR`, `UNKNOWN`
- B behavior: same `FAIL_RATE=0.3`, `B_DELAY_MS=5` (still fast)

**What happens (detail):**
```
HTTP client (200 QPS)
  → A generates UUID once: "abc-123"
  → RetryAppA.callWork("abc-123")

  Attempt 1: WorkRequest{id="abc-123"} → RESOURCE_EXHAUSTED (30% chance)
    ↓ RetryDecisionPolicy: RESOURCE_EXHAUSTED → BACKEND_ERROR → retryable=true → RETRY
    ↓ Resilience4j retry (50ms backoff)
  Attempt 2: WorkRequest{id="abc-123"} → B checks seenRequests["abc-123"]
    ↓ miss (first success) OR retry again
  Attempt 3: WorkRequest{id="abc-123"} → B checks seenRequests["abc-123"]
    ↓ cache HIT → returns cached reply (no duplicate work)
```

**How retry gating works:**
```java
// AppARetry.java:60-63
WorkReply reply = retry.executeSupplier(() ->
    stub.work(WorkRequest.newBuilder().setId(requestId).build())
);
```
- `retry` is Resilience4j Retry with predicate: `e -> retryPolicy.shouldRetry(e, null)`
- `RetryDecisionPolicy` calls `GrpcErrorClassifier.classify(exception, null)`
- If `CallOutcome.retryable() == true` → retry; else → fail immediately
- `RESOURCE_EXHAUSTED` maps to `BACKEND_ERROR` → `retryable=true` → **retry happens**

**Retry amplification math:**
- Visible error rate drops: 30% → ~3% (0.3³ = 2.7%)
- **But**: downstream RPC volume increases: 12,000 → ~15,600 requests to B
  - 70% succeed on attempt 1: 8,400 requests
  - 30% retry once: 3,600 × 2 = 7,200 more attempts
  - Total: 8,400 + 7,200 = 15,600 (1.3× amplification)

**Idempotency dedup** (app-b `main.go:44-51`, `80-85`):
- `seenRequests sync.Map` keyed on `req.Id`, 30 s TTL
- Check happens **before** `workerMutex.Lock()` → instant return, no queue wait
- Cached reply reused across retry attempts

**The anti-pattern hidden here:** Under fast B (5 ms), retry looks like pure win. But when B is slow (Scenario 3: Failfast's 200 ms), retry amplifies load up to 3× and accelerates saturation. Without a circuit breaker to shed excess load, the system gets worse, not better.

**What you observe:**
- `BACKEND_ERROR` visible errors: ~300 (down from 3,600)
- `b_requests_received_total` at B: ~15,600 (up from 12,000) — dedup prevents duplicate work, but retry still amplifies network load

---

### Scenario 3: Failfast — +Deadline + Bulkhead + Circuit Breaker

**Call chain (protection layers):**
```
HTTP client (Fortio, 200 QPS, C=80)
  ↓
app-a: WorkController
  ↓ generates UUID
app-a: ResilientAppA
  ↓ ① Circuit Breaker check
  ↓    OPEN? → return CIRCUIT_OPEN immediately (no network)
  ↓ ② Bulkhead check
  ↓    >10 inflight? → return QUEUE_FULL (semaphore reject)
  ↓ ③ gRPC call with 800 ms deadline + retry
  ↓    WorkRequest{id=uuid}
  ↓
app-b: slow (200 ms) + 30% RESOURCE_EXHAUSTED
  ↓
app-a: response or timeout
  ↓    >800 ms? → DEADLINE_EXCEEDED
  ↓    RESOURCE_EXHAUSTED? → retry (up to 3×)
  ↓ ④ CB records result → may trip OPEN
  ↓
HTTP client: sees CIRCUIT_OPEN + QUEUE_FULL + DEADLINE_EXCEEDED (fast-fail)
```

**Retry safety constraints:**

When errors occur in the call chain above, `RetryDecisionPolicy` checks:
```
Error occurs
  ↓
GrpcErrorClassifier.classify(exception, contextHint)
  ↓
CallOutcome{reason=CIRCUIT_OPEN, retryable=false, ...}
  ↓
RetryDecisionPolicy.shouldRetry() → returns false
  ↓
NO RETRY (fail immediately)
```

**CRITICAL:** Protection events are NEVER retried:
- `CIRCUIT_OPEN` (line 136) → contextHint="CIRCUIT_OPEN" → `retryable=false`
- `QUEUE_FULL` (line 144) → contextHint="BULKHEAD_REJECTED" → `retryable=false`
- `DEADLINE_EXCEEDED` → reason=TIMEOUT → `retryable=false`

**Why this matters:**
- Without this constraint, retry would defeat the circuit breaker (retry → CB opens → retry anyway → infinite loop)
- Bulkhead rejection means "too much load" — retrying makes it worse
- Timeouts already exceeded deadline — retrying amplifies load further

**What IS retried in Scenario 3:**
- `RESOURCE_EXHAUSTED` (BACKEND_ERROR) → still retried (transient B failure)
- But: retry happens AFTER bulkhead check (line 172), so retries don't bypass semaphore

**Configuration:**
- Client: `AppAResilient` (full protection stack)
- Patterns: deadline (800 ms), bulkhead (10 inflight), CB (10 calls, 50% threshold, 5 s wait), **plus retry**
- B behavior: `FAIL_RATE=0.3`, **`B_DELAY_MS=200`** (slow!)
- Load: 200 QPS, C=80 (higher concurrency to trigger overload)

**Capacity crisis:**
- B now takes 200 ms/request (was 5 ms)
- B capacity drops: 3 pods × ~5 RPS = **15 RPS** (unchanged count, but 40× slower per request)
- Incoming: 200 QPS
- **Overload: 13× with retry amplification up to 3× → effective 40× overload**

**What happens (check order, cheapest first):**
```
HTTP request arrives
  ↓
1. Circuit Breaker check (AppAResilient.java:127)
   - State = CLOSED initially
   - After ≥5 failures in 10 calls → trips OPEN
   - OPEN: return CIRCUIT_OPEN immediately (no lock, no network)
   - After 5 s → HALF_OPEN, allows 3 probe calls
   ↓ (if CLOSED or HALF_OPEN)
2. Bulkhead check (AppAResilient.java:133)
   - semaphore.tryAcquire(MAX_INFLIGHT=10)
   - If 10 already inflight → return QUEUE_FULL (CAS, <1 μs)
   ↓ (if acquired)
3. gRPC call with deadline (AppAResilient.java:153-154)
   - withDeadlineAfter(800ms)
   - B takes 200 ms + queue wait
   - If total > 800 ms → DEADLINE_EXCEEDED
   - If < 800 ms and B returns RESOURCE_EXHAUSTED → retry (up to 3 attempts)
   ↓ (after call)
4. Bulkhead release (AppAResilient.java:165)
   - semaphore.release()
5. Circuit Breaker record (AppAResilient.java:170)
   - Success or failure recorded
   - Triggers state transition if threshold crossed
```

**Timeline (typical 60 s run):**
- **t=0-2s**: CB CLOSED, some requests succeed, many fail (BACKEND_ERROR + slow B)
- **t=2s**: CB trips OPEN (50% failure rate reached in sliding window of 10 calls)
- **t=2-7s**: Most requests return CIRCUIT_OPEN instantly, bulkhead rarely fills
- **t=7s**: CB enters HALF_OPEN, allows 3 probe calls
- **t=7-8s**: If probes fail → back to OPEN for 5s; if succeed → CLOSED
- **t=8-60s**: Oscillates between OPEN (shedding load) and CLOSED/HALF_OPEN (probing)

**Protection cascade:**
- Deadline prevents indefinite wait (was 4s in baseline, now capped at 800ms)
- Bulkhead prevents thread exhaustion (without it, all 80 client threads would block)
- CB sheds load before it reaches network (saves B from retry amplification)

**What you observe:**
- `CIRCUIT_OPEN`: >10,000 (CB shedding majority of load)
- `QUEUE_FULL`: >800 (bulkhead rejecting excess even when CB closed)
- `DEADLINE_EXCEEDED`: ~500 (slow B responses that exceeded 800 ms)
- `BACKEND_ERROR`: ~100 (B's 30% fail rate, but only on requests that get through)
- `a_downstream_latency_ms_seconds_max`: ~1 s (vs 4 s in baseline) — deadline enforced
- `a_breaker_state`: oscillates 0 (CLOSED) → 2 (OPEN) → 1 (HALF_OPEN)

---

### Scenario 4: Selfheal — +Keepalive + Channel Pool

**Call chain (with channel pool):**
```
HTTP client (Fortio, 200 QPS)
  ↓
app-a: WorkController
  ↓ generates UUID
app-a: ResilientAppA (pool=4)
  ↓ round-robin: channel 0, 1, 2, 3, 0, 1, ...
  ↓ CB → bulkhead → gRPC call
  ┌─ channel 0 ──→ app-b pod 1 ─┐
  ├─ channel 1 ──→ app-b pod 2 ─┤
  ├─ channel 2 ──→ app-b pod 3 ─┤  ← at t=15s: iptables RST
  └─ channel 3 ──→ app-b pods  ─┘     only channel 2 affected
  ↓
  ↓ t=15-25s: channel 2 dead, keepalive detects (10-40s)
  ↓ t=25-45s: channel 2 reconnects, load rebalances
  ↓
HTTP client: small UNAVAILABLE burst, then self-heals
```

**Configuration:**
- Client: `AppAResilient` with `CHANNEL_POOL_SIZE=4`
- Patterns: deadline, bulkhead, CB, retry, **keepalive (30s interval, 10s timeout), 4-channel pool**
- B behavior: `FAIL_RATE=0.3`, `B_DELAY_MS=5` (fast again)
- Load: 200 QPS for 60 s
- **Fault injection**: `scripts/inject_s4.sh` runs at t=15 s inside one A pod

**What the fault does** (`inject_s4.sh`):
```bash
# Runs inside app-a-<hash> pod at t=15s
iptables -A OUTPUT -p tcp --dport 50051 -j REJECT --reject-with tcp-reset
sleep 30
iptables -D OUTPUT -p tcp --dport 50051 -j REJECT --reject-with tcp-reset
```
- Resets all TCP connections on port 50051 for 30 s
- Only affects **one of two A pods** (the one where script runs)
- Other A pod continues normally

**What happens (timeline for injected pod):**
```
t=0-15s:  Normal operation, 4 channels healthy
  ↓
t=15s:    iptables rule applied → all 4 TCP connections RST
  ↓
t=15-16s: Burst of UNAVAILABLE errors (requests in-flight when RST hit)
          - Without channel pool: ALL requests on single channel fail
          - With pool=4: only ~¼ of concurrent requests fail per RST
  ↓
t=16-25s: gRPC keepalive detects dead connections
          - Keepalive PING every 30s, but can send anytime on activity
          - After 10s timeout → connection declared GOAWAY
          - gRPC auto-reconnects in background
  ↓
t=25-45s: Gradual reconnection (spread across 4 channels)
          - Each channel reconnects independently
          - Load rebalances across healing channels
  ↓
t=45s:    iptables rule removed
  ↓
t=45-60s: All 4 channels healthy, normal operation resumes
```

**gRPC Keepalive** (`AppAResilient.java:72-74`):
```java
.keepAliveTime(30, TimeUnit.SECONDS)      // HTTP/2 PING interval
.keepAliveTimeout(10, TimeUnit.SECONDS)   // PING response timeout
.keepAliveWithoutCalls(true)              // PING even when idle
```
- Detects dead connection in **10-40 s** (depends on when last PING sent)
- Far faster than OS TCP keepalive: 9 probes × 75 s = **11 minutes**

**Channel Pool** (`AppAResilient.java:77-79`, `145-146`):
```java
for (int i = 0; i < channelPoolSize; i++) {
    channels.add(/* create channel with keepalive */);
}
// Round-robin via AtomicInteger
stubs.get(Math.abs(roundRobin.getAndIncrement() % channelPoolSize));
```

**Blast radius containment:**
- Without pool (pool=1): TCP RST → **all** in-flight RPCs fail → spike of 100s of errors
- With pool=4: TCP RST → only **~¼** of in-flight RPCs on that channel fail → smaller bursts
- Each channel reconnects independently → graceful degradation

**What you observe (injected pod only):**
- `UNAVAILABLE`: 50-200 (fault visible during 15-45s window)
- `SUCCESS`: >10,000 (majority of 12,000 requests still succeed)
- `UNAVAILABLE` < 10% of `SUCCESS` (self-heal confirmed, blast contained)
- Other A pod: no UNAVAILABLE spike (fault isolated to one pod)
- `a_channel_pool_size`: 4 (pool enabled)

---

## Configuration

| Env var | Default | Purpose | Active from |
|---|---|---|---|
| `RESILIENCE_ENABLED` | false | Activates ResilientAppA | Scenario 3: Failfast |
| `RETRY_ENABLED` | false | Activates RetryAppA | Scenario 2: Retry |
| `FAIL_RATE` | 0.0 | B-side failure injection rate | Scenario 1: Baseline |
| `B_DELAY_MS` | 5 | B response delay (ms) | Scenario 3: Failfast (200ms) |
| `DEADLINE_MS` | 800 | Per-call gRPC deadline | Scenario 3: Failfast |
| `MAX_INFLIGHT` | 10 | Bulkhead semaphore size | Scenario 3: Failfast |
| `CHANNEL_POOL_SIZE` | 1 | gRPC channel pool size | Scenario 4: Selfheal (4) |

---

## Project Structure

```
.
├── apps/
│   ├── app-a/              # Spring Boot: REST → gRPC, resilience patterns
│   │   ├── observability/
│   │   │   ├── GrpcErrorClassifier.java    # Exception → CallOutcome (semantic classification)
│   │   │   ├── CallOutcome.java            # Record: {reason, retryable, grpcStatus}
│   │   │   └── ErrorReason.java            # Enum: 9 semantic error categories
│   │   └── retry/
│   │       ├── RetryDecisionPolicy.java    # Classifier-based retry predicate
│   │       └── RetryDecisionPolicyTest.java # 11 unit tests
│   └── app-b/              # Go gRPC: single-threaded, FAIL_RATE, idempotency
├── chart/
│   ├── values-common.yaml        # A=2, B=3 (immutable)
│   └── values-{baseline,retry,failfast,selfheal}.yaml  # Scenario configurations
├── scripts/
│   ├── run_scenario.sh     # ./run_scenario.sh {baseline|retry|failfast|selfheal}
│   └── inject_s4.sh        # iptables TCP reset (used by selfheal scenario)
├── tests/
│   ├── verify_retry.sh     # Scenario 2: retry verification (PASS=3 FAIL=0)
│   ├── verify_failfast.sh  # Scenario 3: failfast verification (PASS=2 FAIL=0)
│   └── verify_selfheal.sh  # Scenario 4: selfheal verification (PASS=3 FAIL=0)
└── docs/
    ├── plan.md             # P0–P4 design history
    ├── plan2.md            # P5 learning roadmap design
    ├── runbook.md          # Step-by-step demo guide
    ├── workstream_observability.md               # Error classification design
    ├── workstream_retry_gating.md                # Retry gating design
    └── workstream_retry_gating_implementation_plan.md  # Retry gating implementation plan
```

See `docs/plan2.md` for full roadmap design rationale.
