# S1 vs S4 — Why They Are Different Failure Modes

## The Short Answer

S1 = **capacity exhaustion** (fix with fail-fast: deadline + bulkhead + circuit breaker)
S4 = **connection failure** (fix with self-heal: keepalive + channel pool)

They cannot substitute for each other because they demonstrate different problems and prove different patterns.

---

## S1 — Slow Backend (B_DELAY_MS=200)

**What happens:**
- B is alive and reachable, but responds slowly (200ms per request)
- B is single-threaded → effective capacity is ~5 RPS per pod, ~15 RPS total across 3 pods
- Incoming load is 200 QPS → 13x over capacity
- A's in-flight count grows as requests pile up waiting for slow B responses
- Without resilience: threads/goroutines exhaust, latency climbs, everything times out together — **traffic jam / queueing collapse**

**What resilience proves:**
- **Deadline** caps how long any one call waits
- **Bulkhead** (semaphore) rejects new calls when in-flight hits maxInflight → returns QUEUE_FULL fast
- **Circuit Breaker** opens when failures accumulate → returns CIRCUIT_OPEN without touching B at all
- Together: fail fast, bound the damage, prevent the jam

**Error taxonomy visible:**
- DEADLINE_EXCEEDED (call timed out waiting for slow B)
- QUEUE_FULL (bulkhead rejected before even calling B)
- CIRCUIT_OPEN (breaker open, call rejected immediately)

**Connection state:** TCP connection to B stays alive throughout. gRPC is connected; it just waits.

---

## S4 — TCP Connection Reset (iptables REJECT --reject-with tcp-reset)

**What happens:**
- B is fast (B_DELAY_MS=5), low load — no queueing pressure
- At t=15s, iptables in one A pod drops all outgoing TCP to port 50051 with a RST
- The gRPC TCP connection is torn down immediately
- All in-flight RPCs on that connection die **simultaneously** in the same millisecond → **correlated burst**
- Without resilience: gRPC detects the dead connection only when it next tries to send → slow recovery
- With keepalive: gRPC sends a ping on the idle connection and detects it is dead without waiting for a request to fail first → faster reconnect

**What resilience proves:**
- **Keepalive** (30s/10s/withoutCalls=true) proves the connection is alive between requests. When the connection dies, the next keepalive ping fails → gRPC reconnects proactively
- **Channel pool** limits blast radius: with pool=1, one TCP reset kills ALL in-flight RPCs on that connection at once. With pool=4, only ~1/4 of in-flight RPCs are on the dead channel → smaller burst, faster recovery

**Error taxonomy visible:**
- UNAVAILABLE / CANCELLED (connection reset mid-call)
- Notably: NOT DEADLINE_EXCEEDED, NOT QUEUE_FULL — the errors are immediate, not timeout-shaped

**Connection state:** TCP connection is actively severed. gRPC must reconnect.

---

## Side-by-Side Comparison

| Aspect | S1 (B_DELAY_MS=200) | S4 (iptables TCP reset) |
|---|---|---|
| Failure type | Slow response (capacity shortage) | Broken connection (immediate drop) |
| gRPC status codes | DEADLINE_EXCEEDED, QUEUE_FULL, CIRCUIT_OPEN | UNAVAILABLE, CANCELLED |
| Failure shape | Gradual buildup → traffic jam | Instant correlated burst in same millisecond |
| Connection state | Alive, just slow | Severed, must reconnect |
| Blast radius mechanism | Queue fills up over time | All in-flight on one channel die together |
| Channel pool benefit | Minimal (slow on all channels equally) | **Primary defense** (only 1/N channels affected) |
| Keepalive benefit | Not needed | **Primary defense** (detects dead TCP proactively) |
| Circuit breaker role | Primary defense | Secondary (may open after burst) |
| Bulkhead role | Primary defense | Limited (errors are instant, not queued) |
| Recovery trigger | B speeds up → CB closes | gRPC reconnects → channel healthy again |

---

## Why You Cannot Substitute B_DELAY_MS for iptables in S4

If you use a high B_DELAY_MS instead of iptables to simulate S4:

1. **Connection stays alive** — gRPC never reconnects. Keepalive has nothing to prove.
2. **No correlated burst** — errors spread out over the deadline window. It looks like S1 with different numbers.
3. **Channel pool doesn't help** — slowness is uniform across all channels. Pool size is irrelevant.
4. **Wrong error taxonomy** — you'd see DEADLINE_EXCEEDED, not UNAVAILABLE. The story is wrong.
5. **You'd just be running S1 again** — same pattern, same fix, same evidence.

The correlated burst is the core evidence of S4. It can only come from a real connection break (iptables RST or tc netem loss=100%).

---

## What Each Scenario Proves to an Audience

**S1 proves:** "Without fail-fast patterns, a slow downstream causes a traffic jam that collapses the whole service. With deadline + bulkhead + circuit breaker, we bound the damage and return fast errors instead."

**S4 proves:** "When a TCP connection breaks, all in-flight RPCs on that connection die simultaneously. With keepalive, we detect the break faster. With a channel pool, we reduce how many RPCs die in one burst — smaller blast radius, faster recovery."

They are complementary stories. S1 is about **capacity**. S4 is about **connectivity**.
