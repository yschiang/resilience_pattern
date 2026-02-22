# T13 + T14 Implementation Summary

## T13 — App-B: FAIL_RATE + Idempotency
**Branch:** p5-t13-appb-fail-rate
**PR:** #27
**Commit:** 906d4a9
**Issue:** #21

### Files Changed
- `apps/app-b/main.go` (only file)

### What Was Added
- **FAIL_RATE env var** (float64, 0.0–1.0): before `workerMutex.Lock()`, injects
  `codes.ResourceExhausted` with `status.Errorf` when `rand.Float64() < failRate`
- **Idempotency** (`seenRequests sync.Map`): keyed on `req.GetId()`, 30s TTL;
  checked before FAIL_RATE injection so retried requests bypass failure
- **Cleanup goroutine**: evicts expired entries every 30s via `time.Tick`
- **`b_fail_rate` gauge**: exposed at `/metrics` via `metricsHandler`
- **Updated log line**: `Starting app-b with B_DELAY_MS=%d FAIL_RATE=%.2f`

### New imports
`"math/rand"`, `"google.golang.org/grpc/codes"`, `"google.golang.org/grpc/status"`

### DoD Results
| Check | Result |
|---|---|
| docker build app-b:dev exit 0 | PASS |
| grep -c FAIL_RATE main.go ≥ 3 | 4 |
| grep b_fail_rate main.go ≥ 1 | 3 lines |
| grep seenRequests main.go \| wc -l ≥ 4 | 5 |

---

## T14 — App-A: RATE_LIMITED + RetryBClient + Retry in ResilientBClient
**Branch:** p5-t14-retry-client
**PR:** #28
**Commit:** 498b3a1
**Issue:** #22

### Files Changed (5)
1. `apps/app-a/src/main/java/com/demo/appa/ErrorCode.java`
2. `apps/app-a/src/main/java/com/demo/appa/BClient.java`
3. `apps/app-a/src/main/java/com/demo/appa/RetryBClient.java` *(new)*
4. `apps/app-a/src/main/java/com/demo/appa/ResilientBClient.java`
5. `apps/app-a/src/main/resources/application.yml`

### What Was Added / Changed

**ErrorCode.java**
- Added `RATE_LIMITED` enum value (between `CIRCUIT_OPEN` and `UNKNOWN`)
- Remapped `RESOURCE_EXHAUSTED` → `RATE_LIMITED` (was `QUEUE_FULL`; semantically distinct)

**BClient.java**
- Replaced `@ConditionalOnProperty(resilience.enabled=false)` with
  `@ConditionalOnExpression` requiring both `resilience.enabled=false` AND `retry.enabled=false`
- Prevents conflict with new `RetryBClient`

**RetryBClient.java** *(new)*
- Activates when `retry.enabled=true` AND `resilience.enabled=false` (Scenario 2)
- Single channel, no CB / bulkhead / deadline
- gRPC retry service config: maxAttempts=3, initialBackoff=0.05s, maxBackoff=0.5s,
  backoffMultiplier=2.0, retryableStatusCodes=["RESOURCE_EXHAUSTED"]
- Sends `requestId` in `WorkRequest` for idempotency key

**ResilientBClient.java**
- Added `import java.util.Map;`
- Retry service config (same params as RetryBClient) defined once before pool loop;
  `.defaultServiceConfig(serviceConfig).enableRetry()` added to each channel builder

**application.yml**
- Added `retry.enabled: ${RETRY_ENABLED:false}`

### Three-Client Activation Matrix
| Client           | resilience.enabled | retry.enabled | Scenario |
|------------------|--------------------|---------------|----------|
| BClient          | false              | false         | 1        |
| RetryBClient     | false              | true          | 2        |
| ResilientBClient | true               | any           | 3 + 4    |

### DoD Results
| Check | Result |
|---|---|
| grep RATE_LIMITED ErrorCode.java | 2 lines PASS |
| ls RetryBClient.java | exists PASS |
| grep retry application.yml | `retry:` PASS |
| docker build app-a:dev exit 0 | PASS |
