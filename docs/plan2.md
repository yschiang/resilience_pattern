# P5 Learning Roadmap — Design Document

## Why This Restructure

The original project demonstrated all five resilience patterns simultaneously in
a binary "baseline vs resilient" toggle. A developer running it could observe
that the resilient mode was better, but could not learn *which pattern did what*
— they were all on or off together.

P5 restructures the demo into four cumulative scenarios. Each scenario adds
exactly one group of patterns and introduces exactly one new failure mode. A
learner runs Scenario 1: Baseline → 4 in order, watching each pattern's contribution in
isolation before the next is added.

---

## The Four Scenarios

| # | Name | Patterns added | Failure mode | Key lesson |
|---|---|---|---|---|
| 1 | Baseline | none | FAIL_RATE=0.3 (30% RESOURCE_EXHAUSTED) | Raw failure propagates end-to-end |
| 2 | +Retry+Idempotency | Resilience4j retry, dedup in B | same | Retryable errors drop ~30% → ~3% |
| 3 | +Deadline+Bulkhead+CB | deadline, semaphore, Resilience4j CB | + B_DELAY_MS=200 (slow B) | Overload cascade severed |
| 4 | +Keepalive+ChannelPool | keepalive, channel pool | + iptables tcp-reset | Connection failure self-heals |

---

## Coverage Matrix

| Failure mode | Scenario 1: Baseline | Scenario 2: Retry | Scenario 3: Failfast | Scenario 4: Selfheal |
|---|---|---|---|---|
| RESOURCE_EXHAUSTED (30%) | ❌ raw propagation | ✅ retry absorbs | ✅ retry + CB | ✅ retry + CB |
| Slow B / overload cascade | ❌ | ❌ worse (retry amplifies) | ✅ CB + bulkhead shed | ✅ |
| TCP connection reset | ❌ | ❌ | ❌ slow detection | ✅ self-heals |

---

## The Anti-Pattern Lesson (Scenario 2: Retry → 3)

Scenario 2: Retry deliberately makes overload *worse* before Scenario 3: Failfast fixes it.

When B is slow (B_DELAY_MS=200), retry without a circuit breaker amplifies
load. Each failed call is retried up to 3 times — effectively multiplying
inflight requests by up to 3×. This accelerates queue saturation at B and
increases latency at A.

Scenario 3: Failfast adds the circuit breaker and bulkhead *alongside* retry. The CB
opens after 50% errors in 10 calls and sheds load via CIRCUIT_OPEN (no network
attempt). The bulkhead caps inflight at MAX_INFLIGHT=10. Together they contain
the blast radius that Scenario 2: Retry's retry exposed.

This is the most important distributed systems lesson in the demo: **retry is
not free; it must be paired with a circuit breaker.**

---

## Pattern Inventory

| Pattern | Added in | File | Mechanism |
|---|---|---|---|
| Resilience4j retry | Scenario 2: Retry | AppARetry.java, AppAResilient.java | maxAttempts=3, waitDuration=50ms, classifier-based predicate |
| Idempotency dedup | Scenario 2: Retry | app-b/main.go | seenRequests sync.Map keyed on req.Id, 30s TTL |
| Deadline | Scenario 3: Failfast | AppAResilient.java | withDeadlineAfter(800ms) |
| Bulkhead | Scenario 3: Failfast | AppAResilient.java | Semaphore.tryAcquire(MAX_INFLIGHT) |
| Circuit Breaker | Scenario 3: Failfast | AppAResilient.java | Resilience4j COUNT_BASED(10), 50% threshold |
| gRPC Keepalive | Scenario 4: Selfheal | AppAResilient.java | HTTP/2 PING every 30s, 10s timeout |
| Channel Pool | Scenario 4: Selfheal | AppAResilient.java | N ManagedChannels, round-robin AtomicInteger |

---

## Client Activation Matrix

| Client | RESILIENCE_ENABLED | RETRY_ENABLED | Scenario |
|---|---|---|---|
| AppABaseline | false | false | 1 — baseline |
| AppARetry | false | true | 2 — retry only |
| AppAResilient | true | any | 3, 4 — full stack |

Activation is via Spring `@ConditionalOnExpression`. Only one client bean is
active per deployment.

---

## Failure Injection

### FAIL_RATE (Scenarios 1–4)
- Env var on app-b: `FAIL_RATE=0.3`
- Injects `RESOURCE_EXHAUSTED` randomly before workerMutex (cheap, non-blocking)
- Consistent 30% rate across all scenarios so error reduction is attributable
  solely to the patterns, not to changing the failure rate

### B_DELAY_MS=200 (Scenario 3: Failfast)
- App-b sleeps 200ms per request, simulating a slow downstream
- Combined with FAIL_RATE=0.3 and retry amplification, triggers overload cascade

### iptables TCP reset (Scenario 4: Selfheal)
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
  └─ T14 (app-a: BACKEND_ERROR + AppARetry + retry in AppAResilient)
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

./scripts/run_scenario.sh baseline
./scripts/run_scenario.sh retry
./scripts/run_scenario.sh failfast
./scripts/run_scenario.sh selfheal

./tests/verify_retry.sh   # PASS=3 FAIL=0
./tests/verify_failfast.sh   # PASS=2 FAIL=0
./tests/verify_selfheal.sh   # PASS=3 FAIL=0
```
