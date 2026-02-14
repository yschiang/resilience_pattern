# Resilience Patterns — Engineering Rules (P0→P3)

## Scope
Applies to: Spring Boot services calling downstream via gRPC (and similar RPC), especially if downstream is single-thread or capacity-limited.

## North Star
**Downstream failure must NOT turn into upstream queueing collapse.**
Preferred behavior under failure: **fail fast, isolate blast radius, self-heal, observable.**

---

## P0 — MUST (Stop the bleeding)
### R1. Deadline/Timeout is mandatory
- Every downstream RPC MUST have a per-call deadline.
- Rule of thumb: downstream_deadline = 30–60% of API budget.
- No default/infinite waits.

### R2. Bounded in-flight is mandatory (anti-traffic-jam)
- For each downstream, enforce `max_inflight` (Semaphore or Resilience4j Bulkhead).
- On saturation, return a semantic error (QUEUE_FULL CE_EXHAUSTED).
- Never rely on unbounded queues.

### R3. Circuit Breaker is mandatory per-downstream
- CircuitBreaker MUST be per-downstream (and optionally per-method), not global.
- Open condition must consider latency and/or error rate.
- When open: fail fast (CIRCUIT_OPEN) or fallback (if defined).

### R4. Bulkhead isolation is mandatory
- Calls to downstream MUST NOT share the same constrained resources with other critical paths.
- Minimum: isolate in-flight limits per downstream; recommended: isolate executor / connection strategy.

---

## P1 — SHOULD (Self-heal + reduce correlated failures)
### R5. Dependency-aware readiness
- Readiness must reflect ability to serve meaningful traffic.
- If critical downstream is unhealthy, either mark readiness down (drain) OR stay ready but enforce degraded mode (explicit).

### R6. Connection self-heal with backoff
- Handle channel/connection bad states without restart.
- Reconnect must backoff; no tight loop.

### R7. Retry discipline
- Retry only retryable ilures (UNAVAILABLE / transient resets).
- Max 1–2 attempts, exponential backoff + jitter.
- Total retry time MUST NOT exceed remaining deadline.

---

## P2 — MAY (Degrade predictably)
### R8. Fallback must be explicit and consistent
- If fallback exists, it must return consistent semantics and include reason_code.
- Never return partial/incorrect data silently.

### R9. Load shedding / rate limiting
- Prefer dropping low-priority traffic first.
- Protect critical endpoints capacity.

---

## P3 — MUST (Evidence)
### R10. Semantic error taxonomy is mandatory
Every failure must be mapped to one of:
- DEADLINE_EXCEEDED
- UNAVAILABLE / CANCELLED
- QUEUE_FULL
- CIRCUIT_OPEN
- RATE_LIMITED
- UNKNOWN

### R11. Metrics are mandatory per pod + per downstream
Expose:
- latency p95/p99
- error_count by error_code
- inflight gauge
- breaker state
- outbound RPS to downstream

### R12. Drill scripts are mandatory for demo
Each scenario must be runnable as: inject fault -> run load -> collect evidence -> restore.
