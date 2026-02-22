# T13 Architect Plan — App-B: FAIL_RATE + Idempotency

## Problem
App-B currently always succeeds. Scenario 1 (baseline) needs to demonstrate
raw failure propagation. We need B to randomly reject requests at a
configurable rate via RESOURCE_EXHAUSTED, and dedup retried requests so the
retry pattern (Scenario 2) doesn't cause duplicate work.

## Design Decisions

### FAIL_RATE env var
- Parsed as float64 in main(), default 0.0 (off)
- Applied before the worker mutex — failure is cheap, doesn't block the worker
- Returns codes.ResourceExhausted so gRPC retry can target it by status code

### Idempotency (seenRequests sync.Map)
- Key: req.GetId() (set by App-A in WorkRequest)
- TTL: 30 seconds (matching typical retry window)
- Checked BEFORE FAIL_RATE injection — idempotent requests bypass failure
- Cleanup goroutine runs every 30s to evict stale entries
- Thread-safe: sync.Map handles concurrent access without explicit locking

### Ordering in Work() handler
1. Idempotency check (return cached if hit)
2. FAIL_RATE injection (return RESOURCE_EXHAUSTED if random < failRate)
3. workerMutex.Lock() (existing single-thread enforcement)
4. Do work + cache result

### New metric
- b_fail_rate gauge: lets verify scripts confirm FAIL_RATE setting at runtime

## Files Changed
- apps/app-b/main.go only

## New imports needed
- "math/rand"
- "google.golang.org/grpc/codes"
- "google.golang.org/grpc/status"

## DoD
1. docker build succeeds
2. grep confirms FAIL_RATE env var parsing exists
3. grep confirms b_fail_rate metric emitted
4. grep confirms idempotency dedup via seenRequests
