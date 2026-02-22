# Task Prompt: Service B Observability Metrics (Issue #47)

Reference Issue: https://github.com/yschiang/resilience_pattern/issues/47

---

## Context

Implementing observability metrics for Service B, a Go gRPC backend at `apps/app-b/main.go`.
Service B is intentionally single-threaded via a mutex to demonstrate saturation and queueing behavior.

Current state: Basic metrics exist (`b_requests_total`, `b_busy`) but cannot distinguish
between received vs started vs completed vs failed requests.

Goal: Add SRE-teaching-friendly metrics with clear semantics that allow operators to
understand Service B's internal state.

---

## Task

Implement Option B metrics in the `Work()` gRPC handler (NOT in framework interceptors).
Add instrumentation only — no behavior changes.

---

## Requirements

### 1. Add These Prometheus Metrics

| Metric | Type | Increment When | Semantics |
|---|---|---|---|
| `b_requests_received_total` | Counter | Very start of `Work()`, before any logic | ALL incoming requests (cache hits, fail injection, normal) |
| `b_requests_started_total` | Counter | Immediately after `workerMutex.Lock()` | Only requests that entered the single-thread worker |
| `b_requests_completed_total` | Counter | Right before returning successful WorkReply | Only successful completions (work finished without errors) |
| `b_requests_failed_total{reason}` | Counter | On failure paths | Failures by reason (minimum: `reason="fail_injection"`) |
| `b_busy` | Gauge | EXISTING — keep semantics | 1 while holding mutex, 0 otherwise |

### 2. Decision Rules

Critical decision: Cache hits
- Cache-hit does NOT count as `completed_total`
- Rationale: No actual work performed (immediate return from cache)
- Only count as "completed" if work was done inside mutex-protected section

Metric relationships (must hold):
```
received_total >= started_total + failed_total
started_total  >= completed_total
```

Increment order:
1. `received_total` increments FIRST (before any early returns)
2. `failed_total{reason=...}` increments BEFORE returning error
3. `started_total` increments ONLY inside mutex-protected section
4. `completed_total` increments ONLY for successful non-error completion
5. inflight (if added) uses defer to handle all return paths

### 3. Constraints

- NO behavior changes: Do NOT change request logic, delays, fail injection, idempotency cache, or return codes
- Instrumentation only: Only add metric increments and variable definitions
- No new dependencies: Use existing atomic pattern (no prometheus/client_golang in go.mod)
- Minimal code changes: Consistent with current style
- Thread-safe: sync/atomic used throughout

### 4. Documentation

Add `apps/app-b/README.md` with:
- One-line description of each metric
- Metric relationships (e.g., queue depth = received - started - failed)
- Cache-hit decision note

### 5. Git Workflow

- No "Co-Authored-By" in commit messages
- Final merge will be squash
- Put verification commands in PR description

---

## Edge Cases

| Path | Increments | Does NOT Increment |
|---|---|---|
| Cache hit | `received_total` | `started_total`, `completed_total`, `failed_total` |
| Fail injection | `received_total`, `failed_total{reason="fail_injection"}` | `started_total`, `completed_total` |
| Normal success | `received_total`, `started_total`, `completed_total` | `failed_total` |
