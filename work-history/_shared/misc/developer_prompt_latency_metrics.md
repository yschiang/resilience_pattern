# Developer Prompt: Service B Latency Observability Metrics

## Task

Implement three Prometheus histogram metrics in Service B (Go gRPC handler) to observe latency distribution at different stages of request processing. This enables tail latency visibility (p95, p99) and prevents fast-fail paths from "washing out" slow-path observability.

**Reference:** Issue #49 - https://github.com/yschiang/resilience_pattern/issues/49

---

## Context

**Current state (after PR #48):**
- Service B has flow counters: `b_requests_received_total`, `b_requests_started_total`, `b_requests_completed_total`, `b_requests_failed_total`
- Flow counters tell us **how many** requests progressed through each stage
- Missing: **how long** requests took at each stage

**Problem to solve:**
- Cannot observe latency distribution (p50, p95, p99)
- Fast-fail paths (cache hits ~1ms, fail injection ~5ms) would wash out averages
- Queue wait vs processing time not separated
- Tail latency invisible

**Solution:**
Add three histograms with outcome-based labels to preserve distribution visibility.

---

## Workflow

### 1. Plan (15 minutes)

Read Issue #49 completely, then answer these questions:

**Q1: Where does each histogram get observed?**
- `b_request_latency_ms`: Which code paths? (Hint: ALL returns, use defer)
- `b_processing_latency_ms`: Which code paths? (Hint: only after mutex)
- `b_queue_wait_ms`: Measured from where to where?

**Q2: How do we determine the `outcome` label for each histogram?**
- Cache hit: How do we detect? (Check seenRequests.Load result)
- Fail injection: How do we detect? (Check failRate path)
- Normal success: How do we detect? (Reached end of function)
- Normal failure: How do we detect? (Return error after mutex)

**Q3: Defer timing pattern**
- How do we ensure `b_request_latency_ms` is observed on ALL return paths?
- How do we pass `outcome` to the defer closure?
- Can we use multiple defers? (Answer: Yes, they execute in LIFO order)

**Q4: Edge cases to verify**
| Code path | `b_request_latency_ms` | `b_processing_latency_ms` | `b_queue_wait_ms` |
|---|---|---|---|
| Cache hit | ✅ outcome=cache_hit | ❌ Not observed | ❌ Not observed |
| Fail injection | ✅ outcome=failure | ❌ Not observed | ❌ Not observed |
| Normal success | ✅ outcome=success | ✅ outcome=success | ✅ outcome=acquired |
| Normal failure (future) | ✅ outcome=failure | ✅ outcome=failure | ✅ outcome=acquired |

**Q5: Histogram buckets**
- Why use `[]float64{1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000}`?
- What latencies do these cover? (cache hits 1-5ms, normal 200-500ms, saturation 1000-5000ms)

Write your plan in comments in the code or in a scratch file before coding.

---

### 2. Implement (45 minutes)

#### Step 2.1: Define histogram variables (top of file)

Add after existing metric variables (around line 30-43):

```go
var (
	// ... existing metrics ...

	// Latency histograms (ms)
	requestLatencyHist = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "b_request_latency_ms",
			Help:    "End-to-end handler latency in milliseconds (all outcomes)",
			Buckets: []float64{1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000},
		},
		[]string{"outcome"},
	)

	processingLatencyHist = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "b_processing_latency_ms",
			Help:    "Worker processing latency in milliseconds (mutex-protected section)",
			Buckets: []float64{1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000},
		},
		[]string{"outcome"},
	)

	queueWaitHist = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "b_queue_wait_ms",
			Help:    "Queue wait time before acquiring worker mutex (milliseconds)",
			Buckets: []float64{0.5, 1, 2, 5, 10, 25, 50, 100, 250, 500, 1000},
		},
		[]string{"outcome"},
	)
)
```

Add new import if not present:
```go
import (
	// ... existing imports ...
	"github.com/prometheus/client_golang/prometheus"
)
```

#### Step 2.2: Register histograms in init() or main()

If there's an `init()` function with Prometheus registration, add there. Otherwise, add early in `main()`:

```go
func init() {
	prometheus.MustRegister(requestLatencyHist)
	prometheus.MustRegister(processingLatencyHist)
	prometheus.MustRegister(queueWaitHist)
}
```

#### Step 2.3: Instrument Work() handler

**Critical implementation pattern:**

```go
func (s *server) Work(ctx context.Context, req *pb.WorkRequest) (*pb.WorkReply, error) {
	// === START: End-to-end latency measurement ===
	handlerStart := time.Now()
	outcome := "success" // default, will be overridden for cache_hit/failure

	defer func() {
		latencyMs := float64(time.Since(handlerStart).Milliseconds())
		requestLatencyHist.WithLabelValues(outcome).Observe(latencyMs)
	}()
	// === END: End-to-end latency measurement ===

	atomic.AddInt64(&requestsReceivedTotal, 1)

	// Idempotency: return cached reply if we've seen this ID
	if req.GetId() != "" {
		if cached, ok := seenRequests.Load(req.GetId()); ok {
			if cached.(cachedReply).expires.After(time.Now()) {
				outcome = "cache_hit" // Override outcome for defer
				return cached.(cachedReply).reply, nil
			}
		}
	}

	// Retryable failure injection
	if failRate > 0 && rand.Float64() < failRate {
		atomic.AddInt64(&requestsFailedTotal, 1)
		outcome = "failure" // Override outcome for defer
		return nil, status.Errorf(codes.ResourceExhausted, "rate limited")
	}

	// === START: Queue wait measurement ===
	queueStart := time.Now()
	// === END: Queue wait measurement ===

	// Enforce single-thread processing
	workerMutex.Lock()
	defer workerMutex.Unlock()

	// === START: Observe queue wait after acquiring mutex ===
	queueWaitMs := float64(time.Since(queueStart).Milliseconds())
	queueWaitHist.WithLabelValues("acquired").Observe(queueWaitMs)
	// === END: Queue wait observation ===

	// === START: Processing latency measurement ===
	processingStart := time.Now()
	processingOutcome := "success" // Will be set to "failure" if error occurs

	defer func() {
		processingMs := float64(time.Since(processingStart).Milliseconds())
		processingLatencyHist.WithLabelValues(processingOutcome).Observe(processingMs)
	}()
	// === END: Processing latency measurement ===

	atomic.AddInt64(&requestsStartedTotal, 1)

	atomic.StoreInt32(&busy, 1)
	defer atomic.StoreInt32(&busy, 0)

	atomic.AddInt64(&requestsTotal, 1)

	start := time.Now()

	// Simulate work with configurable delay
	time.Sleep(time.Duration(delayMS) * time.Millisecond)

	latency := time.Since(start).Milliseconds()

	reply := &pb.WorkReply{
		Ok:        true,
		Code:      "SUCCESS",
		LatencyMs: latency,
	}

	// Cache reply for idempotency
	if req.GetId() != "" {
		seenRequests.Store(req.GetId(), cachedReply{
			reply:   reply,
			expires: time.Now().Add(30 * time.Second),
		})
	}

	atomic.AddInt64(&requestsCompletedTotal, 1)
	// outcome remains "success" (default set at top)
	// processingOutcome remains "success" (default set after mutex)
	return reply, nil
}
```

**Key implementation notes:**

1. **End-to-end latency defer MUST be first** (executes last due to LIFO)
2. **Use closure variable `outcome`** to pass result to defer
3. **Set `outcome = "cache_hit"` before cache hit return**
4. **Set `outcome = "failure"` before fail injection return**
5. **Processing latency defer is inside mutex** (only executes for mutex holders)
6. **Queue wait is measured immediately after `workerMutex.Lock()`**

#### Step 2.4: Expose metrics in metricsHandler()

Add to `metricsHandler()` function (around line 105-135):

```go
func metricsHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")

	// ... existing metrics (b_busy, b_requests_total, etc.) ...

	// Latency histograms (Prometheus client library formats these automatically)
	// No manual formatting needed - promhttp.Handler() or prometheus.WriteToTextfile() handles it
	// But if using manual fmt.Fprintf, histograms are complex - use promhttp instead:

	// OPTION A: Keep using manual fmt.Fprintf for existing metrics, add note
	fmt.Fprintf(w, "# Latency histograms: b_request_latency_ms, b_processing_latency_ms, b_queue_wait_ms\n")
	fmt.Fprintf(w, "# (Use promhttp.Handler() for automatic histogram formatting)\n")

	// OPTION B: Switch entire handler to promhttp.Handler() (cleaner, recommended)
	// Replace metricsHandler with:
	//   http.Handle("/metrics", promhttp.Handler())
}
```

**Recommendation:** Switch to `promhttp.Handler()` for cleaner histogram exposition.

If switching to promhttp:
```go
// In main(), replace:
//   http.HandleFunc("/metrics", metricsHandler)
// With:
//   http.Handle("/metrics", promhttp.Handler())

// And remove metricsHandler() function entirely
```

**BUT:** Existing metrics are manually formatted. To preserve them, keep metricsHandler and add:

```go
import "github.com/prometheus/client_golang/prometheus/promhttp"

// At end of metricsHandler:
promhttp.HandlerFor(prometheus.DefaultGatherer, promhttp.HandlerOpts{}).ServeHTTP(w, r)
```

**SIMPLEST:** Since we're already using `prometheus` package, just use promhttp.Handler():

```go
// In main(), line ~169:
http.Handle("/metrics", promhttp.Handler())  // Was: http.HandleFunc("/metrics", metricsHandler)
```

Then delete `metricsHandler()` function entirely. Prometheus will auto-expose all registered metrics.

---

### 3. Update Documentation (15 minutes)

#### Step 3.1: Update apps/app-b/README.md

Add after the "Metrics" table:

```markdown
## Latency Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `b_request_latency_ms` | Histogram | outcome | End-to-end handler latency (ms) — all code paths (cache hit, fail injection, normal) |
| `b_processing_latency_ms` | Histogram | outcome | Worker processing latency (ms) — only requests that acquired mutex |
| `b_queue_wait_ms` | Histogram | outcome | Queue wait time (ms) — from handler entry to mutex acquisition |

### Histogram Buckets

All latency histograms use buckets: `1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000` ms

- Fast paths (cache hit, fail injection): 1-10ms
- Normal processing (B_DELAY_MS=5): 5-25ms
- Slow processing (B_DELAY_MS=200): 200-500ms
- Queue saturation: 500-5000ms

### Latency Relationships

For requests that enter the worker (normal success/failure):
```
b_request_latency_ms ≈ b_queue_wait_ms + b_processing_latency_ms
```

For early-return paths (cache hit, fail injection):
- `b_request_latency_ms{outcome="cache_hit"}`: observed (fast, 1-10ms)
- `b_request_latency_ms{outcome="failure"}`: observed (fast, 1-10ms)
- `b_processing_latency_ms`: NOT observed (never acquired mutex)
- `b_queue_wait_ms`: NOT observed

### Outcome Labels

| Outcome | Code path | Request latency | Processing latency | Queue wait |
|---|---|---|---|---|
| `cache_hit` | Early return from cache | ✅ | ❌ | ❌ |
| `failure` (fail injection) | Early return before mutex | ✅ | ❌ | ❌ |
| `success` | Normal processing | ✅ | ✅ | ✅ |
| `failure` (worker error) | Error after mutex (future) | ✅ | ✅ | ✅ |
```

---

### 4. Verify (20 minutes)

#### Step 4.1: Build and load images

```bash
# From repo root
./scripts/build-images.sh
./scripts/load-images-kind.sh
```

#### Step 4.2: Run Scenario 2 (retry with cache traffic)

```bash
./scripts/run_scenario.sh retry
```

Wait for completion (~2 minutes).

#### Step 4.3: Check histogram metrics

```bash
# Get metrics from one app-b pod
POD=$(kubectl get pod -n demo -l app=app-b -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n demo $POD -- curl -s localhost:8080/metrics > /tmp/b_metrics.txt

# Check histogram presence
grep "b_request_latency_ms" /tmp/b_metrics.txt | head -20
grep "b_processing_latency_ms" /tmp/b_metrics.txt | head -20
grep "b_queue_wait_ms" /tmp/b_metrics.txt | head -20
```

**Expected output:**
```prometheus
# HELP b_request_latency_ms End-to-end handler latency in milliseconds (all outcomes)
# TYPE b_request_latency_ms histogram
b_request_latency_ms_bucket{outcome="cache_hit",le="1"} 0
b_request_latency_ms_bucket{outcome="cache_hit",le="5"} 42
b_request_latency_ms_bucket{outcome="cache_hit",le="10"} 42
...
b_request_latency_ms_bucket{outcome="success",le="250"} 89
b_request_latency_ms_bucket{outcome="success",le="500"} 134
...
b_request_latency_ms_sum{outcome="success"} 45231.0
b_request_latency_ms_count{outcome="success"} 134
```

#### Step 4.4: Verify outcome labels

```bash
# Should see three outcomes for request latency
grep "b_request_latency_ms_count" /tmp/b_metrics.txt

# Should see:
# b_request_latency_ms_count{outcome="cache_hit"} <number>
# b_request_latency_ms_count{outcome="failure"} <number>
# b_request_latency_ms_count{outcome="success"} <number>
```

#### Step 4.5: Verify processing latency only for mutex holders

```bash
grep "b_processing_latency_ms_count" /tmp/b_metrics.txt

# Should NOT see cache_hit (never acquired mutex)
# Should see:
# b_processing_latency_ms_count{outcome="success"} <number>

# Verify: processing count == started_total
grep "b_requests_started_total" /tmp/b_metrics.txt
# Compare: should be equal (both count mutex acquisitions)
```

#### Step 4.6: Spot-check latency values

```bash
# Cache hits should be fast (1-5ms buckets)
grep 'b_request_latency_ms_bucket{outcome="cache_hit"' /tmp/b_metrics.txt | grep 'le="10"'

# Normal success should be slower (B_DELAY_MS=5, so ~5-25ms range)
grep 'b_request_latency_ms_bucket{outcome="success"' /tmp/b_metrics.txt | grep 'le="25"'
```

#### Step 4.7: Run Scenario 3 (failfast with slow B)

```bash
./scripts/run_scenario.sh failfast
```

Check that slow B creates high queue wait:

```bash
POD=$(kubectl get pod -n demo -l app=app-b -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n demo $POD -- curl -s localhost:8080/metrics | grep "b_queue_wait_ms_bucket" | grep 'le="1000"'

# Should see significant counts in 100-1000ms buckets (saturation)
```

---

### 5. Commit and PR (10 minutes)

#### Step 5.1: Review changes

```bash
git status
git diff apps/app-b/main.go
git diff apps/app-b/README.md
```

#### Step 5.2: Commit

```bash
git add apps/app-b/main.go apps/app-b/README.md
git commit -m "feat: add latency observability histograms to Service B

Add three Prometheus histograms for latency distribution:
- b_request_latency_ms: end-to-end handler latency (all outcomes)
- b_processing_latency_ms: worker processing time (mutex holders only)
- b_queue_wait_ms: queue wait before mutex acquisition

Outcome labels (cache_hit, success, failure) preserve visibility of
fast-fail paths vs slow-path tail latency. Histograms enable p95/p99
calculations and prevent fast paths from washing out averages.

Closes #49"
```

**Git guidelines:**
- ❌ Do NOT include "Co-Authored-By" trailer
- ✅ Use descriptive commit message explaining the "why"
- ✅ Reference issue number ("Closes #49")
- ✅ Keep commit focused (latency metrics only, no unrelated changes)

#### Step 5.3: Push and create PR

```bash
git push -u origin <your-branch-name>
gh pr create --title "Add latency observability histograms to Service B" --body "Implements Issue #49

## Summary

Adds three Prometheus histograms to Service B for latency observability:
- \`b_request_latency_ms\`: end-to-end handler latency (all code paths)
- \`b_processing_latency_ms\`: worker processing time (mutex holders only)
- \`b_queue_wait_ms\`: queue wait before acquiring mutex

## Key Design

- **Outcome labels** (\`cache_hit\`, \`success\`, \`failure\`) preserve visibility
- **Defer-based timing** ensures ALL return paths measured
- **Separate queue vs processing** enables saturation diagnosis
- **Histogram buckets** 1-5000ms cover fast-fail to saturation scenarios

## Verification

Ran Scenario 2 (retry) and Scenario 3 (failfast):

\`\`\`bash
# Histogram metrics present with outcome labels
kubectl exec -n demo deploy/app-b -- curl -s localhost:8080/metrics | grep b_request_latency_ms_count
# Output:
# b_request_latency_ms_count{outcome=\"cache_hit\"} 42
# b_request_latency_ms_count{outcome=\"failure\"} 156
# b_request_latency_ms_count{outcome=\"success\"} 134

# Processing latency only for mutex holders (no cache_hit)
kubectl exec -n demo deploy/app-b -- curl -s localhost:8080/metrics | grep b_processing_latency_ms_count
# Output:
# b_processing_latency_ms_count{outcome=\"success\"} 134

# Verification: processing count == started_total (both count mutex acquisitions)
kubectl exec -n demo deploy/app-b -- curl -s localhost:8080/metrics | grep b_requests_started_total
# Output:
# b_requests_started_total 134
\`\`\`

## Documentation

Updated \`apps/app-b/README.md\`:
- Added latency metrics table
- Documented histogram buckets and outcome labels
- Explained latency relationships (request ≈ queue + processing)
- Added edge case semantics table

Closes #49"
```

---

## Critical Implementation Decisions

### Decision 1: Defer timing pattern for end-to-end latency

**Problem:** How to ensure `b_request_latency_ms` is observed on ALL return paths (cache hit, fail injection, normal success)?

**Solution:** Use defer with closure variable:

```go
handlerStart := time.Now()
outcome := "success"  // default

defer func() {
	latencyMs := float64(time.Since(handlerStart).Milliseconds())
	requestLatencyHist.WithLabelValues(outcome).Observe(latencyMs)
}()

// Override outcome for specific paths:
if cacheHit {
	outcome = "cache_hit"
	return cached
}
```

**Why this works:** Defer executes on ANY return (normal or early), and closure captures `outcome` by reference.

### Decision 2: Processing latency defer placement

**Problem:** Processing latency should only be observed for requests that acquired mutex.

**Solution:** Place second defer AFTER `workerMutex.Lock()`:

```go
workerMutex.Lock()
defer workerMutex.Unlock()

processingStart := time.Now()
defer func() {
	// This defer only executes for mutex holders
	processingLatencyHist.WithLabelValues(processingOutcome).Observe(...)
}()
```

**Why this works:** Defers before mutex never execute for cache hits or fail injection.

### Decision 3: Histogram vs Summary

**Choice:** Use Histogram (not Summary)

**Rationale:**
- Histograms are aggregatable across pods (summaries are not)
- PromQL `histogram_quantile()` calculates p95/p99 across all pods
- Buckets are observable in raw metrics (useful for debugging)

### Decision 4: Outcome label values

**Choice:** `cache_hit`, `success`, `failure` (3 values max)

**Rationale:**
- Low cardinality (3 values) prevents label explosion
- Separates fast paths from slow paths (preserves tail latency visibility)
- Matches existing flow counter semantics

---

## Common Pitfalls to Avoid

### Pitfall 1: ❌ Not using defer for end-to-end latency

**Wrong:**
```go
start := time.Now()
if cacheHit {
	return cached  // ❌ Latency not observed!
}
// ... later ...
latency := time.Since(start)
requestLatencyHist.Observe(latency)
```

**Right:**
```go
start := time.Now()
defer func() {
	requestLatencyHist.WithLabelValues(outcome).Observe(time.Since(start).Milliseconds())
}()

if cacheHit {
	outcome = "cache_hit"
	return cached  // ✅ Defer will observe
}
```

### Pitfall 2: ❌ Observing processing latency for cache hits

**Wrong:**
```go
processingStart := time.Now()
defer func() {
	processingLatencyHist.Observe(...)  // ❌ Observes even for cache hits!
}()

if cacheHit {
	return cached
}

workerMutex.Lock()
```

**Right:**
```go
if cacheHit {
	return cached
}

workerMutex.Lock()
defer workerMutex.Unlock()

processingStart := time.Now()
defer func() {
	processingLatencyHist.Observe(...)  // ✅ Only for mutex holders
}()
```

### Pitfall 3: ❌ Using unbounded outcome labels

**Wrong:**
```go
requestLatencyHist.WithLabelValues(req.GetId()).Observe(...)  // ❌ Infinite cardinality!
```

**Right:**
```go
requestLatencyHist.WithLabelValues(outcome).Observe(...)  // ✅ 3 values max
```

### Pitfall 4: ❌ Wrong time conversion

**Wrong:**
```go
latencyMs := time.Since(start).Seconds() * 1000  // ❌ float64 precision issues
```

**Right:**
```go
latencyMs := float64(time.Since(start).Milliseconds())  // ✅ Clean conversion
```

---

## Success Criteria Checklist

Before creating PR, verify:

- [ ] Three histograms defined with correct buckets
- [ ] `b_request_latency_ms` observed on ALL return paths (cache hit, fail injection, success)
- [ ] `b_processing_latency_ms` observed ONLY after mutex acquisition
- [ ] `b_queue_wait_ms` observed after mutex acquisition
- [ ] Outcome labels correctly set for each path
- [ ] Metrics registered with Prometheus
- [ ] Metrics exposed in `/metrics` endpoint (visible via curl)
- [ ] README.md updated with latency metrics table
- [ ] README.md documents histogram buckets and outcome labels
- [ ] README.md explains latency relationships
- [ ] Verification commands run successfully (Scenario 2 and 3)
- [ ] Histogram buckets contain non-zero counts for expected outcomes
- [ ] `processing_latency_ms_count{outcome="success"}` == `b_requests_started_total`
- [ ] No `cache_hit` outcome in `b_processing_latency_ms` (correct exclusion)
- [ ] Git commit message follows guidelines (no Co-Authored-By, references #49)

---

## Reference

**Issue #49:** https://github.com/yschiang/resilience_pattern/issues/49

**Related PRs:**
- PR #48: Flow counters (received/started/completed/failed/busy)

**Key files:**
- `apps/app-b/main.go` — gRPC handler implementation
- `apps/app-b/README.md` — Service B documentation

**Verification scenarios:**
- Scenario 2 (retry): Cache hits present, B_DELAY_MS=5
- Scenario 3 (failfast): Slow B (B_DELAY_MS=200), high queue wait

---

## Questions?

If blocked or uncertain:
1. Re-read Issue #49 edge cases section
2. Check existing flow counter implementation in PR #48 for patterns
3. Verify defer execution order with simple test program
4. Ask in PR comments with specific code snippet
