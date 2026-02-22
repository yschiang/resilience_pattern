# Workstream A: Observability Standardization

**Scope:** App A (Spring Boot gRPC client) metrics standardization. **NO behavior changes** to retry, circuit breaker, bulkhead, or deadlines.

---

## Metrics Contract

### Counter: `grpc_client_requests_total`
Labels: `{service, method, result, reason, retryable}`

**Example:**
```
grpc_client_requests_total{service="demo-service-b",method="Work",result="FAILURE",reason="CONNECTION_FAILURE",retryable="true"} 127
```

### Histogram: `grpc_client_latency_ms`
Labels: `{service, method}`

Records latency for both success and failure.

---

## Error Taxonomy

| Reason | Retryable | gRPC Status / Trigger |
|---|---|---|
| `SUCCESS` | N/A | Call succeeded |
| `CONNECTION_FAILURE` | Yes | UNAVAILABLE, connection reset |
| `TIMEOUT` | No | DEADLINE_EXCEEDED |
| `BACKEND_ERROR` | Yes | RESOURCE_EXHAUSTED |
| `CLIENT_ERROR` | No | INVALID_ARGUMENT, UNAUTHENTICATED, PERMISSION_DENIED, NOT_FOUND |
| `SERVER_ERROR` | No | INTERNAL, DATA_LOSS, UNIMPLEMENTED |
| `CIRCUIT_OPEN` | No | Circuit breaker rejected |
| `BULKHEAD_REJECTED` | No | Semaphore full |
| `UNKNOWN` | No | Fallback |

---

## Implementation

**Classifier:** `GrpcErrorClassifier.classify(Throwable, String contextHint) -> CallOutcome`

**Instrumentation:** `MetricsService.recordCall(String method, long latencyMs, Throwable error, String contextHint)`

**Usage in clients:**
```java
try {
    WorkReply reply = stub.work(request);
    metricsService.recordCall("Work", latency, null, null);
} catch (StatusRuntimeException e) {
    metricsService.recordCall("Work", latency, e, null);
}
```

**Protection events:**
```java
if (!circuitBreaker.tryAcquirePermission()) {
    metricsService.recordCall("Work", 0, null, "CIRCUIT_OPEN");
}
```

---

## PromQL Queries

See `observability/promql/*.promql`

**Top 5 Common Panels:**
1. Request rate: `sum(rate(grpc_client_requests_total[1m])) by (result)`
2. Error rate by reason: `sum(rate(grpc_client_requests_total{result="FAILURE"}[1m])) by (reason)`
3. P95 latency: `histogram_quantile(0.95, sum(rate(grpc_client_latency_ms_bucket[1m])) by (le))`
4. Request volume: `sum(rate(grpc_client_requests_total[1m])) by (method)`
5. Error ratio: `100 * sum(rate(...{result="FAILURE"}[1m])) / sum(rate(...[1m]))`

**Retryable-specific:**
6. Retryable vs non-retryable: `sum(rate(grpc_client_requests_total{result="FAILURE"}[1m])) by (retryable)`
7. Retryable breakdown: `sum(rate(grpc_client_requests_total{result="FAILURE",retryable="true"}[1m])) by (reason)`

---

## Verification

1. Check metrics exist: `curl http://localhost:8080/actuator/prometheus | grep grpc_client`
2. Verify labels present and cardinality ≤36
3. Confirm retry behavior unchanged (compare baseline artifact counts before/after)
4. Run all scenarios, verify no regressions

---

## Files Changed

**New:**
- `observability/ErrorReason.java` (enum, 9 values)
- `observability/CallOutcome.java` (record)
- `observability/GrpcErrorClassifier.java` (classifier)
- `observability/GrpcErrorClassifierTest.java` (unit tests)
- `observability/promql/*.promql` (7 query files)

**Modified:**
- `MetricsService.java` (add `recordCall()` method)
- `AppABaseline.java`, `AppARetry.java`, `AppAResilient.java` (wrap calls)

**Dependencies:** None (uses existing Micrometer from Spring Boot Actuator)
