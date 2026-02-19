# Resilience Pattern Demo

A progressive learning roadmap for five gRPC resilience patterns. Each of four
scenarios adds one group of patterns and one new failure mode — run them in
order to watch each pattern's contribution in isolation.

---

## The Four Scenarios

| # | Name | Patterns added | Failure mode | Key lesson |
|---|---|---|---|---|
| 1 | Baseline | none | FAIL_RATE=0.3 | Raw failure propagates |
| 2 | +Retry+Idempotency | gRPC retry, dedup in B | same | Retryable errors drop 30% → 3% |
| 3 | +Deadline+Bulkhead+CB | deadline, semaphore, Resilience4j CB | + B_DELAY_MS=200 (slow B) | Overload cascade severed |
| 4 | +Keepalive+ChannelPool | keepalive, channel pool | + iptables tcp-reset | Connection failure self-heals |

## Coverage Matrix

| Failure mode | Scenario 1 | Scenario 2 | Scenario 3 | Scenario 4 |
|---|---|---|---|---|
| RESOURCE_EXHAUSTED (30%) | ❌ | ✅ | ✅ | ✅ |
| Slow B / overload | ❌ | ❌ worse | ✅ | ✅ |
| TCP connection reset | ❌ | ❌ | ❌ slow | ✅ |

Scenario 2 deliberately makes overload *worse* before Scenario 3 fixes it —
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
./tests/verify_scenario2.sh   # PASS=3 FAIL=0
./tests/verify_scenario3.sh   # PASS=2 FAIL=0
./tests/verify_scenario4.sh   # PASS=3 FAIL=0
```

Each run deploys via Helm, runs a 60 s Fortio load test, and saves 8 artifacts
to `tmp/artifacts/scenario<N>/`.

---

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

## Pattern Inventory

| Pattern | Added in | File | Mechanism |
|---|---|---|---|
| gRPC retry | Scenario 2 | `RetryBClient.java`, `ResilientBClient.java` | gRPC service config: maxAttempts=3, retryable on `RESOURCE_EXHAUSTED` |
| Idempotency dedup | Scenario 2 | `app-b/main.go` | `seenRequests sync.Map` keyed on `req.Id`, 30 s TTL |
| Deadline | Scenario 3 | `ResilientBClient.java` | `withDeadlineAfter(800ms)` |
| Bulkhead | Scenario 3 | `ResilientBClient.java` | `Semaphore.tryAcquire(MAX_INFLIGHT=10)` |
| Circuit Breaker | Scenario 3 | `ResilientBClient.java` | Resilience4j `COUNT_BASED(10)`, 50% threshold |
| gRPC Keepalive | Scenario 4 | `ResilientBClient.java` | HTTP/2 PING every 30 s, 10 s timeout |
| Channel Pool | Scenario 4 | `ResilientBClient.java` | N `ManagedChannel` instances, round-robin `AtomicInteger` |

---

## Failure Injection → Expected Results

| Scenario | Failure injected | Expected observation |
|---|---|---|
| S1 | `FAIL_RATE=0.3` — B injects `RESOURCE_EXHAUSTED` randomly | ~30% `RATE_LIMITED`; all propagate to caller |
| S2 | Same `FAIL_RATE=0.3` | `RATE_LIMITED` drops ~10×; retry amplifies load when B is slow (hidden cost) |
| S3 | `FAIL_RATE=0.3` + `B_DELAY_MS=200` (slow B) | `QUEUE_FULL` + `CIRCUIT_OPEN` > 100; max latency lower than S1 |
| S4 | `FAIL_RATE=0.3` + iptables tcp-reset at t=15 s | `UNAVAILABLE` > 50 (fault visible); `SUCCESS` > 10,000; `UNAVAILABLE` < 10% of `SUCCESS` (self-heal) |

---

## Per-Scenario Detail

### Scenario 1 — Baseline

No patterns. `BClient` makes a plain gRPC call. B injects `RESOURCE_EXHAUSTED`
30% of the time. Every failure is visible to the caller.

**What you observe:** ~30% error rate sustained for the full 60 s.

---

### Scenario 2 — +Retry + Idempotency

**gRPC retry policy** (`RetryBClient`): maxAttempts=3, retryable on
`RESOURCE_EXHAUSTED`. Each failed call is silently retried up to 2 more times
before surfacing an error. Expected visible error rate drops from ~30% to ~3%.

**Idempotency dedup** (app-b): `seenRequests sync.Map` keyed on `req.Id` with
30s TTL. A retried request with the same ID gets the cached reply — B does no
duplicate work.

**The anti-pattern hidden here:** when B is slow (Scenario 3's condition),
retry amplifies load up to 3×. Without a circuit breaker, this accelerates
saturation rather than relieving it.

**What you observe:** RATE_LIMITED errors drop ~10×. Under fast B this looks
like a win — Scenario 3 reveals the cost.

---

### Scenario 3 — +Deadline + Bulkhead + Circuit Breaker

`ResilientBClient` activates with the full protection stack, plus retry.
B_DELAY_MS=200 triggers overload. Three patterns work in concert:

**Deadline** — `withDeadlineAfter(800ms)`. Frees the calling thread at 800 ms
instead of waiting indefinitely. Returns `DEADLINE_EXCEEDED`.

**Bulkhead** — `Semaphore.tryAcquire(MAX_INFLIGHT=10)`. Non-blocking: if 10
calls are already in-flight the 11th returns `QUEUE_FULL` in microseconds.
Prevents thread exhaustion within the deadline window.

**Circuit Breaker** — Resilience4j `COUNT_BASED(10)`, 50% failure threshold.
After ≥5 failures in 10 calls, trips OPEN. All calls return `CIRCUIT_OPEN`
without touching the network. Recovers with 3 probe calls after 5 s. Check
order: CB before bulkhead (cheaper first).

**What you observe:** max latency drops vs Scenario 1, QUEUE_FULL +
CIRCUIT_OPEN > 100 (fail-fast patterns firing, protecting the system).

---

### Scenario 4 — +Keepalive + Channel Pool

`ResilientBClient` with `CHANNEL_POOL_SIZE=4`. B is fast (5 ms). At t=15s,
`inject_s4.sh` resets all TCP connections on port 50051 inside one A pod.

**gRPC Keepalive** — HTTP/2 PING every 30 s (`keepAliveWithoutCalls=true`),
10 s timeout. One unanswered PING declares the connection dead and triggers
reconnect. Far faster than OS-level TCP keepalive (9 probes × 75 s).

**Channel Pool** — N `ManagedChannel` instances, round-robined via
`AtomicInteger`. With pool=4, a TCP reset affects only ~¼ of in-flight RPCs
instead of all of them. Smaller burst, faster recovery, better load
distribution across B pods.

**What you observe:** UNAVAILABLE > 50 (fault visible), SUCCESS > 10,000
(majority of traffic unaffected), UNAVAILABLE < 10% of SUCCESS
(self-heal confirmed).

---

## Error Taxonomy

| Code | Source | Meaning |
|---|---|---|
| `RATE_LIMITED` | app-b FAIL_RATE | B injected RESOURCE_EXHAUSTED (retryable) |
| `DEADLINE_EXCEEDED` | deadline | Call timed out at 800 ms |
| `QUEUE_FULL` | bulkhead | Semaphore full, rejected without network attempt |
| `CIRCUIT_OPEN` | circuit breaker | Breaker open, rejected without network attempt |
| `UNAVAILABLE` | keepalive / TCP | Connection dead or reset |

**Check order (cheapest first):**
```
Circuit Breaker  →  CIRCUIT_OPEN        (no lock, no network)
Bulkhead         →  QUEUE_FULL          (semaphore CAS)
gRPC + deadline  →  SUCCESS / DEADLINE_EXCEEDED / UNAVAILABLE / RATE_LIMITED
```

---

## Configuration

| Env var | Default | Purpose | Active from |
|---|---|---|---|
| `RESILIENCE_ENABLED` | false | Activates ResilientBClient | Scenario 3 |
| `RETRY_ENABLED` | false | Activates RetryBClient | Scenario 2 |
| `FAIL_RATE` | 0.0 | B-side failure injection rate | Scenario 1 |
| `B_DELAY_MS` | 5 | B response delay (ms) | Scenario 3 (200ms) |
| `DEADLINE_MS` | 800 | Per-call gRPC deadline | Scenario 3 |
| `MAX_INFLIGHT` | 10 | Bulkhead semaphore size | Scenario 3 |
| `CHANNEL_POOL_SIZE` | 1 | gRPC channel pool size | Scenario 4 (4) |

---

## Project Structure

```
.
├── apps/
│   ├── app-a/              # Spring Boot: REST → gRPC, resilience patterns
│   └── app-b/              # Go gRPC: single-threaded, FAIL_RATE, idempotency
├── chart/
│   ├── values-common.yaml        # A=2, B=3 (immutable)
│   ├── values-scenario{1,2,3,4}.yaml  # P5 learning roadmap (use these)
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
