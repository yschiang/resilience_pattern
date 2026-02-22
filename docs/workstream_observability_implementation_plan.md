# Workstream A: Implementation Plan

**For Developer:** Follow this step-by-step guide to implement observability standardization for App A.

**Reference:** See `docs/workstream_A_observability.md` for the contract and requirements.

---

## Executive Summary

**What:** Add standardized gRPC client metrics with error taxonomy to App A
**Why:** Enable decision-useful observability for teaching resilience patterns
**How:** New classifier + metrics service enhancement + client instrumentation
**Constraint:** NO behavior changes (retry/CB/bulkhead/deadline unchanged)

**Key Decisions:**
- **Metric names:** `grpc_client_requests_total` (counter), `grpc_client_latency_ms` (histogram)
- **Classifier:** Single `GrpcErrorClassifier` for all error mapping
- **Reason taxonomy:** 9-value enum (SUCCESS, CONNECTION_FAILURE, TIMEOUT, BACKEND_ERROR, CLIENT_ERROR, SERVER_ERROR, CIRCUIT_OPEN, BULKHEAD_REJECTED, UNKNOWN)
- **Retryable flag:** Label-only, does NOT affect behavior in Workstream A
- **Dependencies:** None (use existing Micrometer)

---

## Step 1: Create ErrorReason Enum

**File:** `apps/app-a/src/main/java/com/demo/appa/observability/ErrorReason.java`

```java
package com.demo.appa.observability;

/**
 * Fixed error taxonomy for gRPC client outcomes.
 * Used as the 'reason' label in grpc_client_requests_total metric.
 */
public enum ErrorReason {
    SUCCESS,                 // Call succeeded
    CONNECTION_FAILURE,      // UNAVAILABLE, connection reset
    TIMEOUT,                 // DEADLINE_EXCEEDED
    BACKEND_ERROR,           // RESOURCE_EXHAUSTED
    CLIENT_ERROR,            // INVALID_ARGUMENT, UNAUTHENTICATED, etc.
    SERVER_ERROR,            // INTERNAL, DATA_LOSS, UNIMPLEMENTED
    CIRCUIT_OPEN,            // Circuit breaker rejected
    BULKHEAD_REJECTED,       // Semaphore full
    UNKNOWN                  // Fallback
}
```

**Test:** Compile and verify enum has 9 values.

---

## Step 2: Create CallOutcome Record

**File:** `apps/app-a/src/main/java/com/demo/appa/observability/CallOutcome.java`

```java
package com.demo.appa.observability;

/**
 * Classification result for a gRPC call outcome.
 */
public record CallOutcome(
    ErrorReason reason,
    boolean retryable,
    String grpcStatus
) {
    public boolean isSuccess() {
        return reason == ErrorReason.SUCCESS;
    }

    public String resultLabel() {
        return isSuccess() ? "SUCCESS" : "FAILURE";
    }
}
```

**Test:** Verify record compiles (requires Java 17+).

---

## Step 3: Create GrpcErrorClassifier

**File:** `apps/app-a/src/main/java/com/demo/appa/observability/GrpcErrorClassifier.java`

```java
package com.demo.appa.observability;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;
import javax.annotation.Nullable;

@Component
public class GrpcErrorClassifier {

    public CallOutcome classify(@Nullable Throwable throwable, @Nullable String contextHint) {
        // Success path
        if (throwable == null && contextHint == null) {
            return new CallOutcome(ErrorReason.SUCCESS, false, "OK");
        }

        // Protection events
        if (contextHint != null) {
            return switch (contextHint) {
                case "CIRCUIT_OPEN" -> new CallOutcome(ErrorReason.CIRCUIT_OPEN, false, "CIRCUIT_OPEN");
                case "BULKHEAD_REJECTED" -> new CallOutcome(ErrorReason.BULKHEAD_REJECTED, false, "BULKHEAD_REJECTED");
                default -> new CallOutcome(ErrorReason.UNKNOWN, false, contextHint);
            };
        }

        // gRPC status-based failures
        if (throwable instanceof StatusRuntimeException sre) {
            Status.Code code = sre.getStatus().getCode();
            return switch (code) {
                case UNAVAILABLE -> new CallOutcome(ErrorReason.CONNECTION_FAILURE, true, code.name());
                case DEADLINE_EXCEEDED -> new CallOutcome(ErrorReason.TIMEOUT, false, code.name());
                case RESOURCE_EXHAUSTED -> new CallOutcome(ErrorReason.BACKEND_ERROR, true, code.name());
                case INVALID_ARGUMENT, UNAUTHENTICATED, PERMISSION_DENIED, NOT_FOUND ->
                    new CallOutcome(ErrorReason.CLIENT_ERROR, false, code.name());
                case INTERNAL, DATA_LOSS, UNIMPLEMENTED ->
                    new CallOutcome(ErrorReason.SERVER_ERROR, false, code.name());
                default -> new CallOutcome(ErrorReason.UNKNOWN, false, code.name());
            };
        }

        // Non-gRPC exceptions
        return new CallOutcome(ErrorReason.UNKNOWN, false, throwable.getClass().getSimpleName());
    }
}
```

**Test:** Unit tests in Step 7.

---

## Step 4: Update MetricsService

**File:** `apps/app-a/src/main/java/com/demo/appa/MetricsService.java`

**Changes:**

1. Add field:
```java
@Autowired
private GrpcErrorClassifier classifier;
```

2. Add new metric builders in `@PostConstruct` method (after existing metrics):
```java
// Note: These will be registered on first use with tags
// No explicit registration needed in init()
```

3. Add new method:
```java
/**
 * Record a gRPC client call outcome (Workstream A standardized metrics).
 *
 * @param method gRPC method name (e.g., "Work")
 * @param latencyMs Call latency in milliseconds
 * @param error Exception thrown, or null for success
 * @param contextHint Optional hint for protection events (e.g., "CIRCUIT_OPEN")
 */
public void recordCall(String method, long latencyMs, @Nullable Throwable error, @Nullable String contextHint) {
    CallOutcome outcome = classifier.classify(error, contextHint);

    // Counter: grpc_client_requests_total
    Counter.builder("grpc_client_requests_total")
        .description("Total gRPC client requests")
        .tag("service", "demo-service-b")
        .tag("method", method)
        .tag("result", outcome.resultLabel())
        .tag("reason", outcome.reason().name())
        .tag("retryable", String.valueOf(outcome.retryable()))
        .register(registry)
        .increment();

    // Histogram: grpc_client_latency_ms
    Timer.builder("grpc_client_latency_ms")
        .description("gRPC client request latency")
        .tag("service", "demo-service-b")
        .tag("method", method)
        .register(registry)
        .record(latencyMs, TimeUnit.MILLISECONDS);
}
```

**Important:** Keep existing `recordDownstreamCall()` method unchanged for backward compatibility.

**Imports to add:**
```java
import com.demo.appa.observability.CallOutcome;
import com.demo.appa.observability.GrpcErrorClassifier;
import javax.annotation.Nullable;
```

---

## Step 5: Update AppABaseline

**File:** `apps/app-a/src/main/java/com/demo/appa/AppABaseline.java`

**Find the `callWork()` method and update:**

**Before:**
```java
public WorkResult callWork(String requestId) {
    long start = System.currentTimeMillis();
    try {
        WorkReply reply = stub.work(WorkRequest.newBuilder().setId(requestId).build());
        long latency = System.currentTimeMillis() - start;
        metricsService.recordDownstreamCall(latency, ErrorCode.SUCCESS);
        return new WorkResult(true, "SUCCESS", latency, ErrorCode.SUCCESS);
    } catch (StatusRuntimeException e) {
        long latency = System.currentTimeMillis() - start;
        ErrorCode code = ErrorCode.fromGrpcStatus(e.getStatus().getCode());
        metricsService.recordDownstreamCall(latency, code);
        return new WorkResult(false, code.name(), latency, code);
    }
}
```

**After:**
```java
public WorkResult callWork(String requestId) {
    long start = System.currentTimeMillis();
    Throwable error = null;
    try {
        WorkReply reply = stub.work(WorkRequest.newBuilder().setId(requestId).build());
        long latency = System.currentTimeMillis() - start;

        // NEW: Workstream A metrics
        metricsService.recordCall("Work", latency, null, null);

        // KEEP: Legacy metrics (backward compat)
        metricsService.recordDownstreamCall(latency, ErrorCode.SUCCESS);

        return new WorkResult(true, "SUCCESS", latency, ErrorCode.SUCCESS);
    } catch (StatusRuntimeException e) {
        error = e;
        long latency = System.currentTimeMillis() - start;
        ErrorCode code = ErrorCode.fromGrpcStatus(e.getStatus().getCode());

        // NEW: Workstream A metrics
        metricsService.recordCall("Work", latency, error, null);

        // KEEP: Legacy metrics
        metricsService.recordDownstreamCall(latency, code);

        return new WorkResult(false, code.name(), latency, code);
    }
}
```

---

## Step 6: Update AppARetry

**File:** `apps/app-a/src/main/java/com/demo/appa/AppARetry.java`

**Apply same pattern as Step 5:**
- Add `Throwable error = null;` before try block
- Add `metricsService.recordCall("Work", latency, null, null);` in success path
- Add `error = e;` at start of catch block
- Add `metricsService.recordCall("Work", latency, error, null);` in catch path
- Keep existing `recordDownstreamCall()` calls

---

## Step 7: Update AppAResilient

**File:** `apps/app-a/src/main/java/com/demo/appa/AppAResilient.java`

**This is the most complex update because it has protection events.**

**Find circuit breaker rejection (around line 126-130):**

**Before:**
```java
if (!circuitBreaker.tryAcquirePermission()) {
    logger.warn("Circuit breaker OPEN for request {}", requestId);
    metricsService.recordDownstreamCall(0, ErrorCode.CIRCUIT_OPEN);
    return new WorkResult(false, ErrorCode.CIRCUIT_OPEN.name(), 0, ErrorCode.CIRCUIT_OPEN);
}
```

**After:**
```java
if (!circuitBreaker.tryAcquirePermission()) {
    logger.warn("Circuit breaker OPEN for request {}", requestId);

    // NEW: Workstream A metrics
    metricsService.recordCall("Work", 0, null, "CIRCUIT_OPEN");

    // KEEP: Legacy metrics
    metricsService.recordDownstreamCall(0, ErrorCode.CIRCUIT_OPEN);

    return new WorkResult(false, ErrorCode.CIRCUIT_OPEN.name(), 0, ErrorCode.CIRCUIT_OPEN);
}
```

**Find bulkhead rejection (around line 133-137):**

**Before:**
```java
if (!semaphore.tryAcquire()) {
    circuitBreaker.releasePermission();
    logger.warn("Bulkhead full (QUEUE_FULL) for request {}", requestId);
    metricsService.recordDownstreamCall(0, ErrorCode.QUEUE_FULL);
    return new WorkResult(false, ErrorCode.QUEUE_FULL.name(), 0, ErrorCode.QUEUE_FULL);
}
```

**After:**
```java
if (!semaphore.tryAcquire()) {
    circuitBreaker.releasePermission();
    logger.warn("Bulkhead full (QUEUE_FULL) for request {}", requestId);

    // NEW: Workstream A metrics (use BULKHEAD_REJECTED reason)
    metricsService.recordCall("Work", 0, null, "BULKHEAD_REJECTED");

    // KEEP: Legacy metrics (still uses QUEUE_FULL name)
    metricsService.recordDownstreamCall(0, ErrorCode.QUEUE_FULL);

    return new WorkResult(false, ErrorCode.QUEUE_FULL.name(), 0, ErrorCode.QUEUE_FULL);
}
```

**Find the main try/catch block (around line 148-180):**

Apply same pattern as Step 5:
- Add `Throwable error = null;` before try
- Add `metricsService.recordCall("Work", latency, null, null);` after line 160
- Add `error = e;` at start of catch (line 165)
- Add `metricsService.recordCall("Work", latency, error, null);` after line 169

---

## Step 8: Create Unit Tests

**File:** `apps/app-a/src/test/java/com/demo/appa/observability/GrpcErrorClassifierTest.java`

```java
package com.demo.appa.observability;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrpcErrorClassifierTest {

    private final GrpcErrorClassifier classifier = new GrpcErrorClassifier();

    @Test
    void testSuccess() {
        CallOutcome outcome = classifier.classify(null, null);
        assertEquals(ErrorReason.SUCCESS, outcome.reason());
        assertFalse(outcome.retryable());
        assertEquals("OK", outcome.grpcStatus());
        assertTrue(outcome.isSuccess());
    }

    @Test
    void testUnavailable() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.UNAVAILABLE);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.CONNECTION_FAILURE, outcome.reason());
        assertTrue(outcome.retryable());
        assertEquals("UNAVAILABLE", outcome.grpcStatus());
    }

    @Test
    void testDeadlineExceeded() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.TIMEOUT, outcome.reason());
        assertFalse(outcome.retryable());
    }

    @Test
    void testResourceExhausted() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.BACKEND_ERROR, outcome.reason());
        assertTrue(outcome.retryable());
    }

    @Test
    void testInvalidArgument() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.INVALID_ARGUMENT);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.CLIENT_ERROR, outcome.reason());
        assertFalse(outcome.retryable());
    }

    @Test
    void testInternal() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.INTERNAL);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.SERVER_ERROR, outcome.reason());
        assertFalse(outcome.retryable());
    }

    @Test
    void testUnknownGrpcStatus() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.UNKNOWN);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.UNKNOWN, outcome.reason());
        assertFalse(outcome.retryable());
    }

    @Test
    void testCircuitOpen() {
        CallOutcome outcome = classifier.classify(null, "CIRCUIT_OPEN");
        assertEquals(ErrorReason.CIRCUIT_OPEN, outcome.reason());
        assertFalse(outcome.retryable());
        assertEquals("CIRCUIT_OPEN", outcome.grpcStatus());
    }

    @Test
    void testBulkheadRejected() {
        CallOutcome outcome = classifier.classify(null, "BULKHEAD_REJECTED");
        assertEquals(ErrorReason.BULKHEAD_REJECTED, outcome.reason());
        assertFalse(outcome.retryable());
    }

    @Test
    void testNonGrpcException() {
        IllegalStateException ex = new IllegalStateException("test");
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.UNKNOWN, outcome.reason());
        assertFalse(outcome.retryable());
        assertEquals("IllegalStateException", outcome.grpcStatus());
    }

    @Test
    void testResultLabel() {
        assertEquals("SUCCESS", classifier.classify(null, null).resultLabel());
        assertEquals("FAILURE", classifier.classify(
            new StatusRuntimeException(Status.UNAVAILABLE), null).resultLabel());
    }
}
```

**Run:** `mvn test -Dtest=GrpcErrorClassifierTest`

---

## Step 9: Create PromQL Query Files

**Directory:** `observability/promql/`

Create directory:
```bash
mkdir -p observability/promql
```

### File 1: `observability/promql/01_request_rate.promql`
```promql
# Panel Title: Request Rate (req/sec)
# Visualization: Line chart
sum(rate(grpc_client_requests_total{service="demo-service-b"}[1m])) by (result)
```

### File 2: `observability/promql/02_error_rate.promql`
```promql
# Panel Title: Error Rate by Reason (errors/sec)
# Visualization: Stacked area chart
sum(rate(grpc_client_requests_total{service="demo-service-b",result="FAILURE"}[1m])) by (reason)
```

### File 3: `observability/promql/03_p95_latency.promql`
```promql
# Panel Title: P95 Latency (ms)
# Visualization: Line chart
histogram_quantile(0.95, sum(rate(grpc_client_latency_ms_bucket{service="demo-service-b"}[1m])) by (le))
```

### File 4: `observability/promql/04_request_volume.promql`
```promql
# Panel Title: Request Volume by Method
# Visualization: Line chart
sum(rate(grpc_client_requests_total{service="demo-service-b"}[1m])) by (method)
```

### File 5: `observability/promql/05_error_ratio.promql`
```promql
# Panel Title: Error Ratio (%)
# Visualization: Single stat with threshold coloring
100 * (
  sum(rate(grpc_client_requests_total{service="demo-service-b",result="FAILURE"}[1m]))
  /
  sum(rate(grpc_client_requests_total{service="demo-service-b"}[1m]))
)
```

### File 6: `observability/promql/06_retryable_vs_nonretryable.promql`
```promql
# Panel Title: Retryable vs Non-Retryable Errors (errors/sec)
# Visualization: Stacked bar chart
sum(rate(grpc_client_requests_total{service="demo-service-b",result="FAILURE"}[1m])) by (retryable)
```

### File 7: `observability/promql/07_retryable_breakdown.promql`
```promql
# Panel Title: Retryable Error Reasons (errors/sec)
# Visualization: Pie chart or stacked area
sum(rate(grpc_client_requests_total{service="demo-service-b",result="FAILURE",retryable="true"}[1m])) by (reason)
```

---

## Step 10: Build and Verify

### 10.1 Compile
```bash
cd apps/app-a
mvn clean compile
```

**Expected:** No compilation errors.

### 10.2 Run Unit Tests
```bash
mvn test
```

**Expected:** All tests pass, including the 11 new classifier tests.

### 10.3 Build Docker Image
```bash
cd /Users/johnson.chiang/workspace/resilience_pattern
./scripts/build-images.sh
```

### 10.4 Load into Kind
```bash
./scripts/load-images-kind.sh
```

---

## Step 11: Smoke Test (Verify No Behavior Change)

### 11.1 Run Baseline Scenario
```bash
./scripts/run_scenario.sh baseline
```

### 11.2 Check New Metrics Exist
```bash
# Port-forward to app-a
kubectl port-forward -n demo svc/app-a 8080:8080 &

# Query metrics
curl -s http://localhost:8080/actuator/prometheus | grep grpc_client
```

**Expected output (sample):**
```
# HELP grpc_client_requests_total Total gRPC client requests
# TYPE grpc_client_requests_total counter
grpc_client_requests_total{method="Work",reason="SUCCESS",result="SUCCESS",retryable="false",service="demo-service-b"} 8234.0
grpc_client_requests_total{method="Work",reason="BACKEND_ERROR",result="FAILURE",retryable="true",service="demo-service-b"} 3566.0

# HELP grpc_client_latency_ms gRPC client request latency
# TYPE grpc_client_latency_ms histogram
grpc_client_latency_ms_bucket{le="0.001",method="Work",service="demo-service-b"} 0.0
grpc_client_latency_ms_bucket{le="0.01",method="Work",service="demo-service-b"} 0.0
grpc_client_latency_ms_bucket{le="0.1",method="Work",service="demo-service-b"} 0.0
grpc_client_latency_ms_bucket{le="1.0",method="Work",service="demo-service-b"} 11800.0
...
grpc_client_latency_ms_count{method="Work",service="demo-service-b"} 11800.0
grpc_client_latency_ms_sum{method="Work",service="demo-service-b"} 123456.789
```

### 11.3 Verify Labels
Check that all expected labels are present:
- `service="demo-service-b"`
- `method="Work"`
- `result` is either "SUCCESS" or "FAILURE"
- `reason` is one of the 9 ErrorReason values
- `retryable` is "true" or "false"

### 11.4 Verify Cardinality
```bash
curl -s http://localhost:8080/actuator/prometheus | grep 'grpc_client_requests_total{' | wc -l
```

**Expected:** ≤36 (typically 3-6 in baseline: SUCCESS, BACKEND_ERROR, maybe TIMEOUT/UNKNOWN)

### 11.5 Compare with Legacy Metrics
```bash
# Count BACKEND_ERROR from new metrics
NEW_BACKEND=$(curl -s http://localhost:8080/actuator/prometheus | \
  grep 'grpc_client_requests_total.*reason="BACKEND_ERROR"' | \
  awk '{print $2}')

# Count BACKEND_ERROR from legacy metrics
LEGACY_BACKEND=$(curl -s http://localhost:8080/actuator/prometheus | \
  grep 'a_downstream_call_total.*BACKEND_ERROR' | \
  awk '{print $2}' | awk '{s+=$1} END {print s}')

echo "New metric: $NEW_BACKEND"
echo "Legacy metric: $LEGACY_BACKEND"
```

**Expected:** Counts should match (±1% variance).

### 11.6 Run All Scenarios
```bash
for scenario in baseline retry failfast selfheal; do
    ./scripts/run_scenario.sh $scenario
done
```

**Expected:** All scenarios complete successfully.

### 11.7 Run Verification Scripts
```bash
./tests/verify_retry.sh      # PASS=3 FAIL=0
./tests/verify_failfast.sh   # PASS=2 FAIL=0
./tests/verify_selfheal.sh   # PASS=3 FAIL=0
```

**Expected:** All tests pass (same as before Workstream A).

---

## Step 12: Test PromQL Queries

If you have Prometheus running:

1. Port-forward to Prometheus:
```bash
kubectl port-forward -n monitoring svc/prometheus 9090:9090
```

2. Open http://localhost:9090

3. Execute each query from `observability/promql/*.promql`

4. Verify:
   - All queries return data
   - Error rate query shows BACKEND_ERROR as dominant
   - P95 latency is in expected range (~200-500ms)
   - Retryable errors are present in retry/failfast/selfheal scenarios

---

## Definition of Done Checklist

- [ ] All 3 new Java files created and compile
- [ ] MetricsService updated with `recordCall()` method
- [ ] All 3 client classes instrumented (AppABaseline, AppARetry, AppAResilient)
- [ ] Unit tests added (11 test cases) and pass
- [ ] 7 PromQL query files created
- [ ] Build succeeds: `mvn clean compile`
- [ ] All tests pass: `mvn test`
- [ ] Docker image builds: `./scripts/build-images.sh`
- [ ] New metrics visible at `/actuator/prometheus`
- [ ] All labels present and correct
- [ ] Cardinality ≤36 time series
- [ ] Legacy metrics still present (backward compat)
- [ ] Retry behavior unchanged (verify_retry.sh passes)
- [ ] CB behavior unchanged (verify_failfast.sh passes)
- [ ] Keepalive behavior unchanged (verify_selfheal.sh passes)
- [ ] PromQL queries return data

---

## Rollback Plan

If anything goes wrong:

1. Revert the 4 modified files:
   ```bash
   git checkout HEAD -- apps/app-a/src/main/java/com/demo/appa/MetricsService.java
   git checkout HEAD -- apps/app-a/src/main/java/com/demo/appa/AppABaseline.java
   git checkout HEAD -- apps/app-a/src/main/java/com/demo/appa/AppARetry.java
   git checkout HEAD -- apps/app-a/src/main/java/com/demo/appa/AppAResilient.java
   ```

2. Delete the new observability package:
   ```bash
   rm -rf apps/app-a/src/main/java/com/demo/appa/observability/
   rm -rf apps/app-a/src/test/java/com/demo/appa/observability/
   ```

3. Rebuild:
   ```bash
   ./scripts/build-images.sh
   ./scripts/load-images-kind.sh
   ```

---

## Next Steps (Out of Scope for Workstream A)

- **Workstream B:** Use `retryable` flag to gate retry behavior
- **Workstream C:** Add alerting rules based on error ratio
- **Workstream D:** Deprecate legacy metrics
- **Workstream E:** Add OpenTelemetry trace context

---

## Questions for Architect

If you encounter issues, ask:

1. **Metric cardinality too high?** → Check reason enum, might need aggregation
2. **Latency histogram buckets wrong?** → Can customize in MetricsService
3. **Protection events not firing?** → Check if circuit breaker/bulkhead is configured
4. **Non-gRPC exceptions appearing?** → Add specific handling in classifier
5. **PromQL queries return empty?** → Check service label matches "demo-service-b"

---

**End of Implementation Plan**
