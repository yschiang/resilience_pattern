# P5 Learning Roadmap — Design Document

## Why This Restructure

The original project demonstrated all five resilience patterns simultaneously in
a binary "baseline vs resilient" toggle. A developer running it could observe
that the resilient mode was better, but could not learn *which pattern did what*
— they were all on or off together.

P5 restructures the demo into four cumulative scenarios. Each scenario adds
exactly one group of patterns and introduces exactly one new failure mode. A
learner runs baseline scenario → 4 in order, watching each pattern's contribution in
isolation before the next is added.

---

## The Four Scenarios

| # | Name | Patterns added | Failure mode | Key lesson |
|---|---|---|---|---|
| 1 | Baseline | none | FAIL_RATE=0.3 (30% RESOURCE_EXHAUSTED) | Raw failure propagates end-to-end |
| 2 | +Retry+Idempotency | gRPC retry policy, dedup in B | same | Retryable errors drop ~30% → ~3% |
| 3 | +Deadline+Bulkhead+CB | deadline, semaphore, Resilience4j CB | + B_DELAY_MS=200 (slow B) | Overload cascade severed |
| 4 | +Keepalive+ChannelPool | keepalive, channel pool | + iptables tcp-reset | Connection failure self-heals |

---

## Coverage Matrix

| Failure mode | baseline scenario | retry scenario | failfast scenario | selfheal scenario |
|---|---|---|---|---|
| RESOURCE_EXHAUSTED (30%) | ❌ raw propagation | ✅ retry absorbs | ✅ retry + CB | ✅ retry + CB |
| Slow B / overload cascade | ❌ | ❌ worse (retry amplifies) | ✅ CB + bulkhead shed | ✅ |
| TCP connection reset | ❌ | ❌ | ❌ slow detection | ✅ self-heals |

---

## The Anti-Pattern Lesson (retry scenario → 3)

retry scenario deliberately makes overload *worse* before failfast scenario fixes it.

When B is slow (B_DELAY_MS=200), retry without a circuit breaker amplifies
load. Each failed call is retried up to 3 times — effectively multiplying
inflight requests by up to 3×. This accelerates queue saturation at B and
increases latency at A.

failfast scenario adds the circuit breaker and bulkhead *alongside* retry. The CB
opens after 50% errors in 10 calls and sheds load via CIRCUIT_OPEN (no network
attempt). The bulkhead caps inflight at MAX_INFLIGHT=10. Together they contain
the blast radius that retry scenario's retry exposed.

This is the most important distributed systems lesson in the demo: **retry is
not free; it must be paired with a circuit breaker.**

---

## Pattern Inventory

| Pattern | Added in | File | Mechanism |
|---|---|---|---|
| gRPC retry | retry scenario | RetryAppABaseline.java, ResilientAppABaseline.java | gRPC service config: maxAttempts=3, RESOURCE_EXHAUSTED |
| Idempotency dedup | retry scenario | app-b/main.go | seenRequests sync.Map keyed on req.Id, 30s TTL |
| Deadline | failfast scenario | ResilientAppABaseline.java | withDeadlineAfter(800ms) |
| Bulkhead | failfast scenario | ResilientAppABaseline.java | Semaphore.tryAcquire(MAX_INFLIGHT) |
| Circuit Breaker | failfast scenario | ResilientAppABaseline.java | Resilience4j COUNT_BASED(10), 50% threshold |
| gRPC Keepalive | selfheal scenario | ResilientAppABaseline.java | HTTP/2 PING every 30s, 10s timeout |
| Channel Pool | selfheal scenario | ResilientAppABaseline.java | N ManagedChannels, round-robin AtomicInteger |

---

## Client Activation Matrix

| Client | RESILIENCE_ENABLED | RETRY_ENABLED | Scenario |
|---|---|---|---|
| AppA | false | false | 1 — baseline |
| RetryAppA | false | true | 2 — retry only |
| ResilientAppA | true | any | 3, 4 — full stack |

Activation is via Spring `@ConditionalOnExpression`. Only one client bean is
active per deployment.

---

## Failure Injection

### FAIL_RATE (Scenarios 1–4)
- Env var on app-b: `FAIL_RATE=0.3`
- Injects `RESOURCE_EXHAUSTED` randomly before workerMutex (cheap, non-blocking)
- Consistent 30% rate across all scenarios so error reduction is attributable
  solely to the patterns, not to changing the failure rate

### B_DELAY_MS=200 (failfast scenario)
- App-b sleeps 200ms per request, simulating a slow downstream
- Combined with FAIL_RATE=0.3 and retry amplification, triggers overload cascade

### iptables TCP reset (selfheal scenario)
- `inject_s4.sh` runs inside the first app-a pod at t=15s
- Resets all TCP connections on port 50051 for 30s
- Keepalive detects dead connection; channel pool limits blast radius per reset

---

## Verification Scripts

| Script | Compares | Assertions |
|---|---|---|
| verify_retry.sh | baseline vs retry | C08: baseline BACKEND_ERROR>1000; C09: retry BACKEND_ERROR<100; C10: directional |
| verify_failfast.sh | baseline vs failfast | C01: baseline max-latency > failfast max-latency; C02: failfast QUEUE_FULL+CIRCUIT_OPEN>100 |
| verify_selfheal.sh | selfheal only | C05: UNAVAILABLE>50; C06: SUCCESS>10000; C07: UNAVAILABLE < 10% of SUCCESS |

---

## Architecture Invariants (unchanged from P0)

- App-A: 2 replicas (Spring Boot, :8080)
- App-B: 3 replicas (Go, single-threaded, gRPC :50051, metrics :8080)
- Namespace: demo
- Kind cluster: resilience-pattern

---

## Execution Order (task dependencies)

```
T13 (app-b: FAIL_RATE + dedup)
  └─ T14 (app-a: BACKEND_ERROR + RetryAppA + retry in Resilient)
       └─ T15 (chart: values-{baseline,retry,failfast,selfheal}.yaml)
            └─ T16 (run_scenario.sh rewrite)
                 └─ T17 (verify_scenario{2,3,4}.sh)
                      └─ T18 (docs: plan2.md + README + runbook)
```

Each task is a separate PR. Developer does not merge own PRs.

---

## End-to-End Verification

```bash
./scripts/build-images.sh
./scripts/load-images-kind.sh

./scripts/run_scenario.sh 1
./scripts/run_scenario.sh 2
./scripts/run_scenario.sh 3
./scripts/run_scenario.sh 4

./tests/verify_retry.sh   # PASS=3 FAIL=0
./tests/verify_failfast.sh   # PASS=2 FAIL=0
./tests/verify_selfheal.sh   # PASS=3 FAIL=0
```
