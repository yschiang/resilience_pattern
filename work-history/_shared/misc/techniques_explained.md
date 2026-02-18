# Resilience Techniques — Full Explanation

> Context: these are the patterns implemented in app-a (Spring Boot, gRPC client)
> talking to app-b (Go, single-threaded gRPC server).

---

## The Core Problem

Without any resilience patterns, one slow or broken downstream can collapse the
entire upstream service. The mechanism is always the same:

1. Downstream slows down or drops connections
2. Upstream threads/goroutines pile up waiting
3. All capacity is exhausted waiting for a downstream that cannot respond
4. Upstream becomes unresponsive to ALL clients — not just the ones hitting the bad downstream

This is called **cascading failure**. The patterns below each cut the chain at
a different point.

---

## P0 Patterns — Stop the Bleeding

### 1. Deadline (per-call timeout)

**Problem it solves:** Without a deadline, a call to a slow downstream waits
forever. Threads accumulate. Memory grows. Eventually the upstream crashes.

**How it works:**
```java
stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).work(request)
```
Every gRPC call gets a hard cutoff. If the downstream has not responded by
`deadlineMs`, gRPC cancels the call and throws `StatusRuntimeException` with
status `DEADLINE_EXCEEDED`. The thread is freed immediately.

**When it triggers:** Any call to B that takes longer than `deadlineMs` (800ms
in S1, 500ms in S4 per plan).

**What the demo shows (S1 baseline vs resilient):**
- Baseline: max latency hits 4.023s — calls are waiting well past any
  reasonable deadline because there is none
- Resilient: max latency ~1s — capped near the deadline

**Trade-off:** Setting deadline too low causes unnecessary failures on
temporarily slow backends. Too high and you lose the protection. The right
value is: `p99 of healthy B latency × 2` as a starting point.

---

### 2. Bulkhead (semaphore-based inflight cap)

**Problem it solves:** Deadlines alone still allow N threads to be in-flight
simultaneously (one per concurrent request). If the deadline is 800ms and you
get 1000 QPS, you can accumulate 800 simultaneous in-flight calls. Threads,
sockets, and heap are all consumed. The bulkhead caps this.

**How it works:**
```java
private final Semaphore semaphore = new Semaphore(maxInflight);

if (!semaphore.tryAcquire()) {
    return new WorkResult(false, ErrorCode.QUEUE_FULL.name(), 0, ErrorCode.QUEUE_FULL);
}
// ... make gRPC call ...
finally {
    semaphore.release();
}
```
`tryAcquire()` is non-blocking. If all `maxInflight` permits are taken, the
call is rejected immediately with `QUEUE_FULL` — no waiting, no queueing.

**When it triggers:** When more than `MAX_INFLIGHT` (10) calls to B are
in-flight simultaneously on one A pod. At 200 QPS with 800ms deadline, the
steady-state inflight count is ~160 without a bulkhead. With the bulkhead,
once 10 are in-flight, every new call returns QUEUE_FULL in microseconds.

**What the demo shows (S1 resilient):**
- QUEUE_FULL: 854 across both pods — the bulkhead was active
- The thread pool of A is never exhausted because rejected calls return
  immediately

**Trade-off:** `maxInflight` per pod, not global. Two A pods each have their
own semaphore with 10 permits → 20 total in-flight across the deployment.
Setting too low starves throughput. Too high and you lose protection.

---

### 3. Circuit Breaker (per-downstream, Resilience4j)

**Problem it solves:** Even with deadline + bulkhead, every request still
*attempts* to call the downstream before being rejected. When the downstream
is completely broken, each attempt wastes `deadlineMs` before failing. The
circuit breaker remembers that the downstream is unhealthy and rejects calls
immediately without trying.

**How it works (state machine):**
```
CLOSED → (failure rate > 50% in 10 calls) → OPEN
OPEN → (after 5s wait) → HALF_OPEN
HALF_OPEN → (3 probe calls succeed) → CLOSED
HALF_OPEN → (probe calls fail) → OPEN
```

```java
if (!circuitBreaker.tryAcquirePermission()) {
    return new WorkResult(false, ErrorCode.CIRCUIT_OPEN.name(), 0, ErrorCode.CIRCUIT_OPEN);
}
// ... make call ...
circuitBreaker.onSuccess(latency, TimeUnit.MILLISECONDS);
// or
circuitBreaker.onError(latency, TimeUnit.MILLISECONDS, e);
```

Configuration in the demo:
- Sliding window: 10 calls (COUNT_BASED)
- Failure threshold: 50% → trips to OPEN
- Wait in OPEN: 5 seconds
- Half-open probes: 3 calls

**When it triggers:** After ≥5 failures in the last 10 calls, the breaker
opens. All subsequent calls return `CIRCUIT_OPEN` in microseconds — no network
attempt at all.

**What the demo shows (S1 resilient):**
- CIRCUIT_OPEN: 10,993 across both pods — once the breaker opened, the vast
  majority of calls were shed instantly
- The breaker prevented the bulkhead from ever filling again: calls are
  rejected before they consume a semaphore permit

**Interaction with bulkhead — order matters:**
```java
// Circuit breaker checked FIRST
if (!circuitBreaker.tryAcquirePermission()) → CIRCUIT_OPEN (fast reject)

// Then bulkhead
if (!semaphore.tryAcquire()) → QUEUE_FULL (fast reject)

// Only now do we attempt the actual call
```
This ordering means CIRCUIT_OPEN is cheaper than QUEUE_FULL (no semaphore
contention), and both are cheaper than an actual gRPC call.

---

## P1 Patterns — Self-Heal

### 4. gRPC Keepalive

**Problem it solves:** When a TCP connection is broken (firewall rule, pod
restart, network partition), gRPC does not know the connection is dead until
it next tries to send data. If the connection is idle between requests, the
broken state can persist indefinitely. The next request hits the dead
connection and gets an immediate UNAVAILABLE error — but by then it's too
late for that request.

Keepalive sends periodic ping frames on idle connections to prove they are
alive. If a ping fails, gRPC closes and reconnects immediately — before the
next request arrives.

**How it works:**
```java
ManagedChannelBuilder.forTarget(bServiceUrl)
    .keepAliveTime(30, TimeUnit.SECONDS)     // send PING after 30s idle
    .keepAliveTimeout(10, TimeUnit.SECONDS)  // wait 10s for PONG
    .keepAliveWithoutCalls(true)             // send even if no active RPCs
    .build();
```

- `keepAliveTime=30s`: if no traffic for 30s, send an HTTP/2 PING frame
- `keepAliveTimeout=10s`: if no PONG within 10s, declare connection dead
- `keepAliveWithoutCalls=true`: critical — without this, gRPC only pings
  while there are active RPCs, which defeats the purpose

**When it triggers (S4):**
- iptables rule drops TCP to port 50051
- Any in-flight RPCs fail immediately with UNAVAILABLE (connection reset)
- Within 30s, the keepalive ping fires on the now-dead connection
- No PONG received → gRPC closes the channel and reconnects
- Recovery is automatic without any application-level retry logic

**What the demo shows (S4 resilient vs baseline):**
- Baseline: UNAVAILABLE=1797 — every RPC on the dead connection fails, and
  the connection stays broken for the rest of the test (no keepalive)
- Resilient: UNAVAILABLE=21 — initial burst on connection reset, then
  reconnect via keepalive, then steady-state resumes

---

### 5. Channel Pool (round-robin, AtomicInteger)

**Problem it solves:** A single gRPC channel (ManagedChannel) multiplexes all
RPCs over one TCP connection. When that connection resets, ALL in-flight RPCs
on that channel fail simultaneously — a **correlated burst**. With N channels,
only 1/N in-flight RPCs are on the broken connection at the moment of reset.

**How it works:**
```java
// Create N channels at startup
for (int i = 0; i < channelPoolSize; i++) {
    ManagedChannel ch = ManagedChannelBuilder.forTarget(bServiceUrl)...build();
    channels.add(ch);
    stubs.add(DemoServiceGrpc.newBlockingStub(ch));
}

// Round-robin selection per call
DemoServiceGrpc.DemoServiceBlockingStub stub =
    stubs.get(Math.abs(roundRobin.getAndIncrement() % channelPoolSize));
```

`AtomicInteger.getAndIncrement()` is thread-safe and lock-free — no
synchronization cost.

**When it triggers (S4, CHANNEL_POOL_SIZE=4):**
- iptables resets one TCP connection
- Only ~1/4 of in-flight RPCs are on that connection
- The other 3 channels are unaffected
- Blast radius is reduced from "all concurrent calls" to "~25% of concurrent calls"

**Side benefit — load distribution:**
With a single channel, both A pods each open one TCP connection → likely
land on 2 of 3 B pods → one B pod gets no traffic (uneven load). With
CHANNEL_POOL_SIZE=4, each A pod has 4 connections → more likely to reach
all 3 B pods → more even distribution.

**What the demo shows (S4 resilient):**
UNAVAILABLE=21 vs baseline=1797. The combination of keepalive + channel
pool reduces the blast radius and shortens recovery time dramatically.

---

## Semantic Error Taxonomy

An underappreciated part of the design: every failure returns a **named reason
code**, not a generic 500.

| Code | Meaning | Source |
|---|---|---|
| `SUCCESS` | Call succeeded | B returned ok=true |
| `DEADLINE_EXCEEDED` | Call timed out | gRPC `Status.DEADLINE_EXCEEDED` |
| `UNAVAILABLE` | Connection broken | gRPC `Status.UNAVAILABLE` or `CANCELLED` |
| `QUEUE_FULL` | Bulkhead rejected | `semaphore.tryAcquire()` returned false |
| `CIRCUIT_OPEN` | Breaker rejected | `circuitBreaker.tryAcquirePermission()` returned false |
| `UNKNOWN` | Unexpected error | Catch-all |

This taxonomy makes the demo legible:
- S1 evidence: QUEUE_FULL + CIRCUIT_OPEN dominate → fail-fast patterns are working
- S4 evidence: UNAVAILABLE spikes then drops → connection reset + self-heal

Without named codes, all failures look like "error rate went up" — you cannot
tell whether the system is protecting itself (QUEUE_FULL/CIRCUIT_OPEN) or
genuinely broken (UNAVAILABLE/DEADLINE_EXCEEDED).

---

## How the Patterns Interact

```
Incoming request
    │
    ▼
Circuit Breaker.tryAcquirePermission()
    │ OPEN → return CIRCUIT_OPEN (microseconds)
    │ CLOSED/HALF_OPEN → continue
    ▼
Semaphore.tryAcquire()
    │ full → return QUEUE_FULL (microseconds)
    │ acquired → continue
    ▼
gRPC stub.withDeadlineAfter(800ms).work(request)
    │ timeout → DEADLINE_EXCEEDED, circuitBreaker.onError()
    │ connection reset → UNAVAILABLE, circuitBreaker.onError()
    │ success → circuitBreaker.onSuccess()
    ▼
Semaphore.release()
```

The circuit breaker sees the results of DEADLINE_EXCEEDED and UNAVAILABLE
calls. When failure rate exceeds 50%, it opens and prevents future calls from
reaching the bulkhead or the network at all.

---

## Pattern → Scenario Mapping

| Pattern | Defends against | Evidence in |
|---|---|---|
| Deadline | Slow downstream (S1) | S1: max latency capped ~1s |
| Bulkhead | Thread exhaustion (S1) | S1: QUEUE_FULL appears |
| Circuit Breaker | Repeated failures (S1+S4) | S1: CIRCUIT_OPEN dominates; S4: CB opens after burst |
| Keepalive | Dead connection detection (S4) | S4: UNAVAILABLE −99% |
| Channel Pool | Correlated burst (S4) | S4: blast radius reduced |
| Error taxonomy | Diagnosability (all) | All: errors readable, not generic 500 |

---

## What Is NOT Implemented (and Why)

**Retry:** Not implemented. Retrying on DEADLINE_EXCEEDED amplifies load on
an already-slow downstream. Retrying on UNAVAILABLE can cause duplicate
operations if the RPC was already partially processed. The circuit breaker +
keepalive combination achieves the same goal (eventual success) without
retry storms.

**Rate limiting:** Not implemented. The bulkhead limits concurrent in-flight
calls, which is a stronger constraint than rate limiting for this workload.
Rate limiting would require a token bucket and is more complex to tune.

**Fallback response:** Not implemented in the demo. In production, CIRCUIT_OPEN
and QUEUE_FULL could return a cached or degraded response instead of an error.
The demo deliberately surfaces the error codes to make the patterns visible.
