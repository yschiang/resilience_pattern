# Developer Prompt — T13: App-B FAIL_RATE + Idempotency

## Context
You are implementing GitHub issue #15 for the P5 Learning Roadmap milestone.
Read .clinerules/ in order (00→10→20→30→40) before starting.
Read tmp/T13/plan.md for architect decisions.

## Task
Modify apps/app-b/main.go to add:
1. FAIL_RATE env var — random RESOURCE_EXHAUSTED failure injection
2. Idempotency dedup via seenRequests sync.Map

## Exact Changes Required

### New imports (add to import block)
- "math/rand"
- "google.golang.org/grpc/codes"
- "google.golang.org/grpc/status"

### New type (add before var block)
```go
type cachedReply struct {
    reply   *pb.WorkReply
    expires time.Time
}
```

### New globals (add to var block)
```go
failRate     float64  // from FAIL_RATE env var (0.0–1.0)
seenRequests sync.Map // map[string]cachedReply for idempotency
```

### Work() handler — add BEFORE workerMutex.Lock()
```go
// Idempotency: return cached reply if we've seen this ID
if req.GetId() != "" {
    if cached, ok := seenRequests.Load(req.GetId()); ok {
        if cached.(cachedReply).expires.After(time.Now()) {
            return cached.(cachedReply).reply, nil
        }
    }
}

// Retryable failure injection
if failRate > 0 && rand.Float64() < failRate {
    return nil, status.Errorf(codes.ResourceExhausted, "rate limited")
}
```

### Work() handler — add BEFORE final return, AFTER computing reply
```go
// Cache reply for idempotency
if req.GetId() != "" {
    seenRequests.Store(req.GetId(), cachedReply{
        reply:   reply,
        expires: time.Now().Add(30 * time.Second),
    })
}
```
Note: refactor the final return to use a named `reply` variable so you can
cache it before returning.

### main() — add AFTER delayMS parsing
```go
// Read FAIL_RATE from environment
if envRate := os.Getenv("FAIL_RATE"); envRate != "" {
    if parsed, err := strconv.ParseFloat(envRate, 64); err == nil {
        failRate = parsed
    }
}
```

### main() — add cleanup goroutine BEFORE starting servers
```go
go func() {
    for range time.Tick(30 * time.Second) {
        seenRequests.Range(func(k, v any) bool {
            if v.(cachedReply).expires.Before(time.Now()) {
                seenRequests.Delete(k)
            }
            return true
        })
    }
}()
```

### metricsHandler — add at end
```go
fmt.Fprintf(w, "# HELP b_fail_rate Configured failure injection rate\n")
fmt.Fprintf(w, "# TYPE b_fail_rate gauge\n")
fmt.Fprintf(w, "b_fail_rate %.2f\n", failRate)
```

### log.Printf in main()
Update to include FAIL_RATE:
```go
log.Printf("Starting app-b with B_DELAY_MS=%d FAIL_RATE=%.2f", delayMS, failRate)
```

## DoD Proof Commands (run these, save output to tmp/T13/proof.txt)
```bash
# Proof 1: Build succeeds
docker build -t app-b:dev ./apps/app-b
echo "Exit code: $?"

# Proof 2: FAIL_RATE env var parsing exists
grep -c "FAIL_RATE" apps/app-b/main.go

# Proof 3: b_fail_rate metric emitted
grep "b_fail_rate" apps/app-b/main.go

# Proof 4: idempotency via seenRequests
grep "seenRequests" apps/app-b/main.go | wc -l
```

## Commit Message (save to tmp/T13/commit_msg.txt)
```
feat: add FAIL_RATE injection and idempotency dedup to app-b

- FAIL_RATE env var injects RESOURCE_EXHAUSTED at configured rate
- seenRequests sync.Map deduplicates retried requests by ID
- b_fail_rate gauge exposed at /metrics

Fixes #15
```

## After Implementing
Follow 10_workflow_implement_task.md Steps 5-8.
Do NOT merge your own PR. Post proof to issue, wait for architect review.
