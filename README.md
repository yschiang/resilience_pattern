# Resilience Pattern Demo

A progressive learning roadmap for five gRPC resilience patterns. Each of four
scenarios adds one group of patterns and one new failure mode — run them in
order to watch each pattern's contribution in isolation.

---

## The Four Scenarios

| # | Name | Patterns added | Failure injection | Observable result |
|---|---|---|---|---|
| 1 | Baseline | none | FAIL_RATE=0.3 | Raw failure propagates |
| 2 | +Retry+Idempotency | gRPC retry, dedup in B | same | Retryable errors drop 30% → 3% |
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

| Failure mode | baseline scenario | retry scenario | failfast scenario | selfheal scenario |
|---|---|---|---|---|
| RESOURCE_EXHAUSTED (30%) | ❌ | ✅ | ✅ | ✅ |
| Slow B / overload | ❌ | ❌ worse | ✅ | ✅ |
| TCP connection reset | ❌ | ❌ | ❌ slow | ✅ |

retry scenario deliberately makes overload *worse* before failfast scenario fixes it —
the most important anti-pattern lesson: **retry amplifies load without a
circuit breaker**.

---

## Quick Start

```bash
# Build and load images
./scripts/build-images.sh
./scripts/load-images-kind.sh

# Run scenarios in order
./scripts/run_scenario.sh 1   # baseline — raw failure
./scripts/run_scenario.sh 2   # +retry+idempotency
./scripts/run_scenario.sh 3   # +deadline+bulkhead+CB (slow B)
./scripts/run_scenario.sh 4   # +keepalive+pool (+TCP reset at t=15s)

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
| gRPC retry | retry scenario | `RetryAppA.java`, `ResilientAppA.java` | gRPC service config: maxAttempts=3, retryable on `RESOURCE_EXHAUSTED` |
| Idempotency dedup | retry scenario | `app-b/main.go` | `seenRequests sync.Map` keyed on `req.Id`, 30 s TTL |
| Deadline | failfast scenario | `ResilientAppA.java` | `withDeadlineAfter(800ms)` |
| Bulkhead | failfast scenario | `ResilientAppA.java` | `Semaphore.tryAcquire(MAX_INFLIGHT=10)` |
| Circuit Breaker | failfast scenario | `ResilientAppA.java` | Resilience4j `COUNT_BASED(10)`, 50% threshold |
| gRPC Keepalive | selfheal scenario | `ResilientAppA.java` | HTTP/2 PING every 30 s, 10 s timeout |
| Channel Pool | selfheal scenario | `ResilientAppA.java` | N `ManagedChannel` instances, round-robin `AtomicInteger` |

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

### baseline scenario — Baseline

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
- Client: `AppA` (plain gRPC, no patterns)
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

### retry scenario — +Retry + Idempotency

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
- Client: `RetryAppA` (gRPC retry only, no CB/bulkhead/deadline)
- Retry policy: `maxAttempts=3`, `initialBackoff=0.05s`, retryable on `RESOURCE_EXHAUSTED`
- B behavior: same `FAIL_RATE=0.3`, `B_DELAY_MS=5` (still fast)

**What happens (detail):**
```
HTTP client (200 QPS)
  → A generates UUID once: "abc-123"
  → RetryAppA.callWork("abc-123")

  Attempt 1: WorkRequest{id="abc-123"} → RESOURCE_EXHAUSTED (30% chance)
    ↓ gRPC retry (50ms backoff)
  Attempt 2: WorkRequest{id="abc-123"} → B checks seenRequests["abc-123"]
    ↓ miss (first success) OR retry again
  Attempt 3: WorkRequest{id="abc-123"} → B checks seenRequests["abc-123"]
    ↓ cache HIT → returns cached reply (no duplicate work)
```

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

**The anti-pattern hidden here:** Under fast B (5 ms), retry looks like pure win. But when B is slow (failfast scenario's 200 ms), retry amplifies load up to 3× and accelerates saturation. Without a circuit breaker to shed excess load, the system gets worse, not better.

**What you observe:**
- `BACKEND_ERROR` visible errors: ~300 (down from 3,600)
- `b_requests_total` at B: ~15,600 (up from 12,000) — dedup prevents duplicate work, but retry still amplifies network load

---

### failfast scenario — +Deadline + Bulkhead + Circuit Breaker

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

**Configuration:**
- Client: `ResilientAppA` (full protection stack)
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
1. Circuit Breaker check (ResilientAppA.java:127)
   - State = CLOSED initially
   - After ≥5 failures in 10 calls → trips OPEN
   - OPEN: return CIRCUIT_OPEN immediately (no lock, no network)
   - After 5 s → HALF_OPEN, allows 3 probe calls
   ↓ (if CLOSED or HALF_OPEN)
2. Bulkhead check (ResilientAppA.java:133)
   - semaphore.tryAcquire(MAX_INFLIGHT=10)
   - If 10 already inflight → return QUEUE_FULL (CAS, <1 μs)
   ↓ (if acquired)
3. gRPC call with deadline (ResilientAppA.java:153-154)
   - withDeadlineAfter(800ms)
   - B takes 200 ms + queue wait
   - If total > 800 ms → DEADLINE_EXCEEDED
   - If < 800 ms and B returns RESOURCE_EXHAUSTED → retry (up to 3 attempts)
   ↓ (after call)
4. Bulkhead release (ResilientAppA.java:165)
   - semaphore.release()
5. Circuit Breaker record (ResilientAppA.java:170)
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

### selfheal scenario — +Keepalive + Channel Pool

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
- Client: `ResilientAppA` with `CHANNEL_POOL_SIZE=4`
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

**gRPC Keepalive** (`ResilientAppA.java:72-74`):
```java
.keepAliveTime(30, TimeUnit.SECONDS)      // HTTP/2 PING interval
.keepAliveTimeout(10, TimeUnit.SECONDS)   // PING response timeout
.keepAliveWithoutCalls(true)              // PING even when idle
```
- Detects dead connection in **10-40 s** (depends on when last PING sent)
- Far faster than OS TCP keepalive: 9 probes × 75 s = **11 minutes**

**Channel Pool** (`ResilientAppA.java:77-79`, `145-146`):
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

## Error Taxonomy

| Code | Returned by | When | Location |
|---|---|---|---|
| `BACKEND_ERROR` | **app-b** | Random 30% of requests (FAIL_RATE injection) | `main.go:55` → gRPC `RESOURCE_EXHAUSTED` → mapped in `ErrorCode.java:34` |
| `DEADLINE_EXCEEDED` | **gRPC client** | Call exceeds 800 ms deadline | grpc-java runtime → caught in `ResilientAppA.java:161` |
| `QUEUE_FULL` | **app-a bulkhead** | Semaphore full (>10 inflight) | `ResilientAppA.java:136` (tryAcquire fails) |
| `CIRCUIT_OPEN` | **app-a circuit breaker** | Breaker state = OPEN | `ResilientAppA.java:129` (Resilience4j) |
| `UNAVAILABLE` | **gRPC client** | TCP connection dead/reset | grpc-java runtime → caught in `ResilientAppA.java:161` |

---

## Configuration

| Env var | Default | Purpose | Active from |
|---|---|---|---|
| `RESILIENCE_ENABLED` | false | Activates ResilientAppA | failfast scenario |
| `RETRY_ENABLED` | false | Activates RetryAppA | retry scenario |
| `FAIL_RATE` | 0.0 | B-side failure injection rate | baseline scenario |
| `B_DELAY_MS` | 5 | B response delay (ms) | failfast scenario (200ms) |
| `DEADLINE_MS` | 800 | Per-call gRPC deadline | failfast scenario |
| `MAX_INFLIGHT` | 10 | Bulkhead semaphore size | failfast scenario |
| `CHANNEL_POOL_SIZE` | 1 | gRPC channel pool size | selfheal scenario (4) |

---

## Project Structure

```
.
├── apps/
│   ├── app-a/              # Spring Boot: REST → gRPC, resilience patterns
│   └── app-b/              # Go gRPC: single-threaded, FAIL_RATE, idempotency
├── chart/
│   ├── values-common.yaml        # A=2, B=3 (immutable)
│   ├── values-{baseline,retry,failfast,selfheal}.yaml  # P5 learning roadmap (use these)
│   └── values-{baseline,resilient,s1,s4}.yaml  # deprecated
├── scripts/
│   ├── run_scenario.sh     # ./run_scenario.sh <1|2|3|4>
│   └── inject_s4.sh        # iptables TCP reset (used by scenario 4)
├── tests/
│   ├── verify_scenario{2,3,4}.sh  # P5 assertions (use these)
│   └── verify_s{1,4}.sh           # deprecated
└── docs/
    ├── plan.md             # P0–P4 design history
    ├── plan2.md            # P5 learning roadmap design
    └── runbook.md          # Step-by-step demo guide
```

See `docs/plan2.md` for full roadmap design rationale.
