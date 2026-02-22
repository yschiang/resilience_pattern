# Implementation Plan: Service B Observability (Issue #47)

## Key Observations

1. No `prometheus/client_golang` in `go.mod` — codebase uses hand-rolled atomics + custom `metricsHandler`
2. Must stay consistent with existing `sync/atomic` pattern
3. For `b_requests_failed_total{reason="fail_injection"}`, only one label value exists currently → single dedicated atomic, formatted with label in `metricsHandler`
4. `b_inflight` skipped — `b_busy` already signals single-thread occupancy; inflight would be redundant for this single-worker topology

## New Global Variables

```go
// Workstream B observability counters
requestsReceivedTotal  int64 // b_requests_received_total
requestsStartedTotal   int64 // b_requests_started_total
requestsCompletedTotal int64 // b_requests_completed_total
requestsFailedFail     int64 // b_requests_failed_total{reason="fail_injection"}
```

## Insertion Points in `Work()`

| # | Location | Code Marker | Action |
|---|---|---|---|
| 1 | First line of `Work()`, before cache check | Before `if req.GetId() != ""` | `atomic.AddInt64(&requestsReceivedTotal, 1)` |
| 2 | Cache-hit early return (lines 46–51) | No change | No additional increments — received already done |
| 3 | Fail-injection branch, before `return nil, ...` | Inside `if failRate > 0 && rand.Float64() < failRate` | `atomic.AddInt64(&requestsFailedFail, 1)` |
| 4 | Immediately after `workerMutex.Lock()` | After lock, before `busy=1` | `atomic.AddInt64(&requestsStartedTotal, 1)` |
| 5 | Right before `return reply, nil` | After idempotency cache store | `atomic.AddInt64(&requestsCompletedTotal, 1)` |

## `metricsHandler` Additions

Append after existing metrics:
```
# HELP b_requests_received_total All requests entering the Work() handler
# TYPE b_requests_received_total counter
b_requests_received_total <value>

# HELP b_requests_started_total Requests that acquired the worker mutex
# TYPE b_requests_started_total counter
b_requests_started_total <value>

# HELP b_requests_completed_total Successful completions inside mutex-protected worker
# TYPE b_requests_completed_total counter
b_requests_completed_total <value>

# HELP b_requests_failed_total Requests failed by reason
# TYPE b_requests_failed_total counter
b_requests_failed_total{reason="fail_injection"} <value>
```

## Files to Modify

- `apps/app-b/main.go` — add 4 global vars, 4 atomic increments, 4 metric blocks in handler
- `apps/app-b/README.md` — create with metric descriptions and relationship invariants
