# Workstream B: Retry Gating

**Status:** Planned
**Foundation:** Workstream A (observability complete)

---

## Objective

Implement intelligent retry gating that only retries errors marked as `retryable` by the existing `GrpcErrorClassifier`, while preserving Scenario 2 teaching behavior.

---

## Retry Policy v1

| Reason | Retryable? | Rationale | Scenario Impact |
|---|---|---|---|
| `SUCCESS` | N/A | Not an error | All |
| `CONNECTION_FAILURE` | **Yes** | Transient network issue, may recover | Scenario 4 (selfheal) |
| `TIMEOUT` | **No** | Deadline already exceeded, retry amplifies load | Scenario 3 (failfast) |
| `BACKEND_ERROR` | **Yes** | RESOURCE_EXHAUSTED, backend may recover | **Scenario 2 (retry demo)** |
| `CLIENT_ERROR` | **No** | Invalid request, will fail again | All |
| `SERVER_ERROR` | **No** | Backend bug, retry won't help | All |
| `CIRCUIT_OPEN` | **No** | Protection event, retry defeats CB purpose | Scenario 3, 4 |
| `BULKHEAD_REJECTED` | **No** | Protection event, retry defeats bulkhead | Scenario 3, 4 |
| `UNKNOWN` | **No** | Conservative default, unknown errors risky | All |

---

## Implementation Approach

**Chosen:** Resilience4j Retry (already a dependency for circuit breaker)

**Rejected alternatives:**
- gRPC service config retry - no custom predicate support
- gRPC interceptor - more complex, harder to test
- Manual retry loop - reinventing the wheel

**Key decision:** Replace gRPC service config retry with Resilience4j retry using classifier output as predicate.

---

## Architecture

```
Error occurs
  ↓
GrpcErrorClassifier.classify(exception, contextHint)
  ↓
CallOutcome{reason, retryable, grpcStatus}
  ↓
RetryDecisionPolicy.shouldRetry()
  ↓
return outcome.retryable()
  ↓
Resilience4j Retry predicate
  ↓
retry OR fail
```

---

## Safety Constraints

**Critical:** Protection events MUST NOT be retried.

**Why:** Retrying protection events defeats their purpose:
- CIRCUIT_OPEN: Circuit breaker opened because backend is failing
- BULKHEAD_REJECTED: Bulkhead full because too much load

Retrying would bypass the protection mechanism.

---

## Verification

See full verification steps in issue #45.

Key scenarios:
- Scenario 2: BACKEND_ERROR retried (teaching preserved)
- Scenario 3: Protection events NOT retried (safety constraint)
- Scenario 4: CONNECTION_FAILURE retried (self-healing)

---

## Dependencies

**None added.** Resilience4j already present for circuit breaker.
