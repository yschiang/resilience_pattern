# Implementation Summary: Service B Observability (Issue #47)

**Branch:** `workstream-b-observability`

## Changes Made

### `apps/app-b/main.go`

**New global variables (added to `var` block):**
```go
requestsReceivedTotal  int64 // b_requests_received_total: all incoming requests
requestsStartedTotal   int64 // b_requests_started_total: entered mutex-protected worker
requestsCompletedTotal int64 // b_requests_completed_total: successful completions
requestsFailedTotal     int64 // b_requests_failed_total{reason="fail_injection"}
```

**`Work()` handler — 4 new increments:**

```go
// 1. Very first line — counts all paths
atomic.AddInt64(&requestsReceivedTotal, 1)

// 2. Inside fail-injection branch, before early return
atomic.AddInt64(&requestsFailedTotal, 1)

// 3. Immediately after workerMutex.Lock()
atomic.AddInt64(&requestsStartedTotal, 1)

// 4. Right before return reply, nil
atomic.AddInt64(&requestsCompletedTotal, 1)
```

**`metricsHandler` — 4 new metric blocks appended.**

### `apps/app-b/README.md` (new file)

Documents all metrics, invariants, and cache-hit decision.

## Decision: `b_inflight` Skipped

`b_busy` already signals single-thread occupancy (1=busy, 0=idle). For a single-worker
topology, inflight ∈ {0, 1} at all times — identical information. Skipped to minimize
changes; can be added in a follow-up if multi-worker support is introduced.

## Build Verification

```
docker build -f apps/app-b/Dockerfile . -t app-b-test
→ Successfully built sha256:5047a257...
```

## Metric Relationships Verified by Design

```
received_total >= started_total + failed_total   ✅ (fail injection and cache hits exit before mutex)
started_total  >= completed_total                ✅ (completed only inside mutex section)
```

## Verification Commands (for PR)

```bash
# 1. Check metrics exist
curl <service-b-pod>:8080/metrics | grep "^b_requests"

# 2. Fail injection (FAIL_RATE=0.3)
# Expected: received increases, failed{fail_injection} increases
# started and completed do NOT increase for injected failures

# 3. Normal load (FAIL_RATE=0)
# Expected: received >= started >= completed, b_busy toggles

# 4. Cache hits (duplicate request IDs)
# Expected: received increases, started/completed do NOT

# Sample output format:
b_requests_received_total 12000
b_requests_started_total 8400
b_requests_completed_total 5880
b_requests_failed_total{reason="fail_injection"} 3600
b_busy 0

# Relationships:
# ✅ received (12000) >= started (8400) + failed (3600)  →  12000 >= 12000
# ✅ started (8400) >= completed (5880)
```

## Status

- [x] 4 new metrics implemented
- [x] Cache-hit path correctly excluded from started/completed
- [x] No behavior changes
- [x] No new dependencies
- [x] Docker build succeeds
- [x] README created
- [ ] Committed and pushed (pending user approval)
