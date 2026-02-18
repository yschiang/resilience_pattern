# Resilience Pattern Demo

Demonstrates how five resilience patterns prevent a slow or broken downstream
from collapsing an upstream Spring Boot service. Includes reproducible
before/after measurements using Kubernetes, gRPC, and Fortio load testing.

---

## The Problem

Without protection, one slow downstream causes a **cascading failure**:

1. Downstream slows down → upstream threads pile up waiting
2. Thread pool exhausts → upstream stops serving all clients
3. One bad dependency takes the whole service down

The patterns below each cut this chain at a different point.

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
failures easy to demonstrate.

---

## Resilience Techniques

### P0 — Stop the Bleeding

**1. Deadline** — every gRPC call gets `withDeadlineAfter(800ms)`. Without
it, a slow B holds threads indefinitely. With it, the thread is freed at
exactly 800 ms and returns `DEADLINE_EXCEEDED`. S1 baseline: max latency 4 s.
S1 resilient: max latency ~1 s.

**2. Bulkhead** — `Semaphore.tryAcquire()` with `MAX_INFLIGHT=10`.
Non-blocking: if 10 calls are already in-flight, the 11th returns `QUEUE_FULL`
in microseconds. Prevents thread exhaustion even within the deadline window.
S1 resilient: 854 `QUEUE_FULL` across 2 pods.

**3. Circuit Breaker** — Resilience4j state machine
(`CLOSED → OPEN → HALF_OPEN`). After ≥5 failures in 10 calls, trips OPEN.
All subsequent calls return `CIRCUIT_OPEN` without touching the network.
Recovers with 3 probe calls after 5 s. S1 resilient: 10,993 `CIRCUIT_OPEN` —
once tripped, nearly all load was shed immediately. Order matters: CB is
checked before the bulkhead — `CIRCUIT_OPEN` is cheaper than `QUEUE_FULL`.

### P1 — Self-Heal

**4. gRPC Keepalive** — sends HTTP/2 PING frames every 30 s on idle
connections (`keepAliveWithoutCalls=true`). If no PONG within 10 s, declares
the connection dead and reconnects. **One unanswered PING is enough** — there
is no retry before acting. Without it, a broken TCP connection can sit
undetected for minutes: the OS-level TCP keepalive retries 9 probes at 75 s
intervals before giving up, whereas gRPC's application-level PING acts on the
first failure. S4 resilient: `UNAVAILABLE` drops from 1,797 to 21 (−99%).

**5. Channel Pool** — N `ManagedChannel` instances, round-robined via
`AtomicInteger`. With pool=1, one TCP reset kills all in-flight RPCs
simultaneously (correlated burst). With pool=4, only ~¼ of in-flights are
affected — smaller burst, faster recovery. Also improves load distribution:
a single channel is sticky to 1 of 3 B pods; pool=4 reaches all three.

### Error Taxonomy (cross-cutting)

Every failure returns a named code — `DEADLINE_EXCEEDED`, `UNAVAILABLE`,
`QUEUE_FULL`, `CIRCUIT_OPEN`. This is what makes S1 vs S4 legible: S1
resilient shows `QUEUE_FULL`/`CIRCUIT_OPEN` (the system protecting itself),
S4 resilient shows a small `UNAVAILABLE` spike then silence (connection reset
+ self-heal). Without named codes, all you see is "error rate went up."

### What's Deliberately Absent

- **No retry** — retries on a slow or broken downstream amplify load; CB +
  keepalive achieve recovery without it.
- **No fallback** — deliberately omitted to keep error codes visible in the
  demo. In production, `CIRCUIT_OPEN`/`QUEUE_FULL` could return a cached or
  degraded response.

### Quick Reference

| # | Pattern | Mechanism | Rejects with | Defends against |
|---|---|---|---|---|
| 1 | Deadline | `withDeadlineAfter(800ms)` | `DEADLINE_EXCEEDED` | Slow downstream |
| 2 | Bulkhead | `Semaphore.tryAcquire()` | `QUEUE_FULL` | Thread exhaustion |
| 3 | Circuit Breaker | Resilience4j COUNT_BASED(10), threshold 50% | `CIRCUIT_OPEN` | Repeated failures |
| 4 | Keepalive | HTTP/2 PING every 30 s | — (auto-reconnects) | Dead TCP connection |
| 5 | Channel Pool | Round-robin `AtomicInteger` over N channels | — (reduces blast radius) | Correlated burst |

**Check order (cheapest first):**
```
Circuit Breaker  →  CIRCUIT_OPEN   (no lock, no network)
Bulkhead         →  QUEUE_FULL     (semaphore CAS)
gRPC + deadline  →  SUCCESS / DEADLINE_EXCEEDED / UNAVAILABLE
```

**Error taxonomy:**

| Code | Meaning | Primary scenario |
|---|---|---|
| `DEADLINE_EXCEEDED` | Call timed out at deadline | S1 |
| `UNAVAILABLE` | Connection reset or broken | S4 |
| `QUEUE_FULL` | Bulkhead rejected | S1 resilient |
| `CIRCUIT_OPEN` | Breaker open, no network attempt | S1 resilient |
| `UNKNOWN` | Unexpected error | — |

---

## Scenarios

### S1 — Queueing Collapse (slow downstream)

B delay = 200 ms, load = 200 QPS × c80 × 60 s.

| Metric | Baseline | Resilient |
|---|---|---|
| Max latency | 4.0 s | 1.0 s |
| QUEUE_FULL | 0 | 854 |
| CIRCUIT_OPEN | 0 | 10,993 |

Baseline: threads pile up → max latency reaches 4 s as calls queue behind
each other. Resilient: bulkhead fills at 10 in-flight → QUEUE_FULL sheds
excess; circuit breaker opens → 10,993 calls rejected in microseconds.

### S4 — Connection Reset (iptables tcp-reset)

B delay = 5 ms (no saturation). One A pod has iptables inject a TCP reset
on port 50051 at t=15 s for 15 s. Load = 200 QPS × c50 × 60 s.

| Metric | Baseline | Resilient |
|---|---|---|
| UNAVAILABLE | 1,797 | 21 |
| Reduction | — | −99% |

Baseline: every RPC on the dead connection fails and the connection stays
broken for the rest of the test. Resilient: initial burst of ~21 failures,
then keepalive detects the dead connection, gRPC reconnects, and traffic
resumes normally.

---

## Quick Start

### Prerequisites

```bash
# Required tools
docker  kubectl  helm  kind
```

### Build and load images

```bash
./scripts/build-images.sh
./scripts/load-images-kind.sh
```

### Run scenarios

```bash
./scripts/run_scenario.sh S1 baseline    # slow backend, no resilience
./scripts/run_scenario.sh S1 resilient   # slow backend, with protection
./scripts/run_scenario.sh S4 baseline    # connection reset, no resilience
./scripts/run_scenario.sh S4 resilient   # connection reset, with self-heal
```

Each run deploys via Helm, runs a 60 s Fortio load test, and saves 8
artifacts to `tmp/artifacts/<SCENARIO>/<MODE>/`:
- `fortio.txt` — load test summary
- `app-a-<pod>.prom` — A metrics (latency, errors, inflight, breaker state)
- `app-a-<pod>.log` — A logs
- `app-b-<pod>.metrics` — B busy ratio and request count

### Verify results

```bash
./tests/verify_s1.sh   # exits 0 = PASS
./tests/verify_s4.sh   # exits 0 = PASS
```

---

## Project Structure

```
.
├── apps/
│   ├── app-a/              # Spring Boot REST API + resilience patterns
│   └── app-b/              # Go gRPC service (single-threaded)
├── chart/                  # Helm chart + values overlays
│   ├── values-common.yaml  # A=2, B=3 (immutable)
│   ├── values-baseline.yaml
│   ├── values-resilient.yaml
│   ├── values-s1.yaml
│   └── values-s4.yaml
├── scripts/
│   ├── run_scenario.sh     # One-command scenario runner
│   └── inject_s4.sh        # iptables fault injection (tc netem fallback)
├── tests/
│   ├── verify_s1.sh        # S1 pass/fail assertions
│   └── verify_s4.sh        # S4 pass/fail assertions
├── docs/
│   ├── plan.md             # SSOT: full design and acceptance criteria
│   └── runbook.md          # Step-by-step demo guide
└── tmp/
    └── artifacts/          # Scenario outputs (gitignored)
```

---

## Configuration

Switch between baseline and resilient by Helm values overlay only — no code changes.

| `RESILIENCE_ENABLED` | Active bean | Patterns |
|---|---|---|
| `false` (default) | `BClient` | none |
| `true` | `ResilientBClient` | all P0+P1 |

Key tuning knobs (set via env vars in `values-resilient.yaml`):

| Env var | Default | Purpose |
|---|---|---|
| `DEADLINE_MS` | 800 | Per-call gRPC deadline |
| `MAX_INFLIGHT` | 10 | Bulkhead semaphore size |
| `CHANNEL_POOL_SIZE` | 4 | gRPC channel pool |

See `docs/plan.md` for full design rationale.
