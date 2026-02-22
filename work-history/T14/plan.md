# T14 Architect Plan — App-A: RATE_LIMITED + RetryBClient + Retry in Resilient

## Problem
With T13 done, B returns RESOURCE_EXHAUSTED. App-A needs to:
1. Map RESOURCE_EXHAUSTED → RATE_LIMITED (not QUEUE_FULL — different semantics)
2. Provide a retry-only client (Scenario 2: retry without full resilience stack)
3. Add retry service config to ResilientBClient (Scenario 3+4)
4. Narrow BClient's activation condition so all three clients don't conflict

## Design Decisions

### ErrorCode.RATE_LIMITED
- New enum value. QUEUE_FULL stays (it's the bulkhead label, not a B-side error).
- RESOURCE_EXHAUSTED → RATE_LIMITED (B is rate-limiting us, retryable)
- QUEUE_FULL stays as the bulkhead semaphore rejection (App-A side)

### Three-client activation matrix
| Client         | resilience.enabled | retry.enabled | When used         |
|----------------|-------------------|---------------|-------------------|
| BClient        | false             | false         | Scenario 1        |
| RetryBClient   | false             | true          | Scenario 2        |
| ResilientBClient | true            | any           | Scenario 3 + 4    |

BClient current condition: @ConditionalOnProperty(name="resilience.enabled", havingValue="false")
Problem: this fires even when retry.enabled=true → BClient conflicts with RetryBClient.
Fix: @ConditionalOnExpression("'${resilience.enabled:false}'=='false' && '${retry.enabled:false}'=='false'")

### RetryBClient (new file)
- @ConditionalOnExpression: retry.enabled=true AND resilience.enabled=false
- Single channel (no pool), no CB, no bulkhead, no deadline
- gRPC retry service config:
  - maxAttempts: "3"
  - initialBackoff: "0.05s"
  - maxBackoff: "0.5s"
  - backoffMultiplier: 2.0
  - retryableStatusCodes: ["RESOURCE_EXHAUSTED"]
- Sends requestId in WorkRequest for idempotency key
- Records metrics via metricsService (same as BClient)

### ResilientBClient retry addition
- Same retry service config as RetryBClient
- Added to each channel in the pool builder loop
- .defaultServiceConfig(serviceConfig).enableRetry() before .build()
- Scenario 2 "worse" lesson: retry amplifies load under slow B (S3 has CB+bulkhead to contain this)

### application.yml
- Add: retry.enabled: ${RETRY_ENABLED:false}

## Files Changed
- ErrorCode.java (add RATE_LIMITED, remap RESOURCE_EXHAUSTED)
- BClient.java (ConditionalOnExpression)
- ResilientBClient.java (add retry service config + Map import)
- RetryBClient.java (new file)
- application.yml (add retry.enabled)

## DoD
1. grep ErrorCode.java confirms RATE_LIMITED exists
2. ls RetryBClient.java confirms file exists
3. docker build app-a succeeds
4. grep application.yml confirms retry.enabled property
