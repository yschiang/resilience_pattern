# Plan: Resilience Learning Roadmap (P5)

## Context

The project currently demonstrates 5 patterns in a binary "baseline vs resilient"
structure. A developer cannot learn *which pattern does what* because all 5 are
enabled at once. This restructure turns the project into a progressive learning
roadmap: 4 cumulative scenarios where each adds one group of patterns and one new
failure mode.

**Outcome:** `docs/plan2.md` captures the roadmap design. The codebase is
restructured so a learner can run Scenario 1 → 4, watching each pattern's
contribution in isolation.

---

## The Four Scenarios

| # | Name | Patterns added | Failure mode injected | Key lesson |
|---|---|---|---|---|
| 1 | Baseline | none | FAIL_RATE=0.3 (retryable errors) | Raw failure propagates |
| 2 | +Retry+Idempotency | gRPC retry, dedup in B | same | Retryable errors drop 30% → 3% |
| 3 | +Deadline+Bulkhead+CB | deadline, semaphore, Resilience4j CB | + B_DELAY_MS=200 (slow B) | Overload cascade severed |
| 4 | +Keepalive+ChannelPool | keepalive, channel pool, + TCP reset | + iptables tcp-reset | Connection failure self-heals |

**Coverage matrix (the spine of README + plan2):**

| Failure mode | Scenario 1 | Scenario 2 | Scenario 3 | Scenario 4 |
|---|---|---|---|---|
| RESOURCE_EXHAUSTED (30%) | ❌ | ✅ | ✅ | ✅ |
| Slow B / overload | ❌ | ❌ worse | ✅ | ✅ |
| TCP connection reset | ❌ | ❌ | ❌ slow | ✅ |

Scenario 2 deliberately shows retry making overload **worse** — the most important
anti-pattern lesson in distributed systems.

---

## GitHub: P5 Milestone + Issues

**Milestone:** `P5 — Learning Roadmap Restructure`

| Issue | Task | Scope |
|---|---|---|
| #21 | T13 | App-B: FAIL_RATE env var + idempotency dedup |
| #22 | T14 | App-A: RATE_LIMITED error code + new RetryBClient + gRPC retry in ResilientBClient |
| #23 | T15 | Chart: values-scenario{1,2,3,4}.yaml |
| #24 | T16 | Scripts: run_scenario.sh restructure (args: 1\|2\|3\|4) |
| #25 | T17 | Tests: verify_scenario{2,3,4}.sh (retire verify_s1/s4) |
| #26 | T18 | Docs: docs/plan2.md + README rewrite + runbook update |

---

## File Changes

### App-B: `apps/app-b/main.go`

Add `FAIL_RATE` env var and idempotency dedup.

**New globals:**
```go
var (
    failRate     float64  // from FAIL_RATE env var (0.0–1.0)
    seenRequests sync.Map // map[string]cachedReply for idempotency
)

type cachedReply struct {
    reply   *pb.WorkReply
    expires time.Time
}
```

**`main()` — parse env var:**
```go
if envRate := os.Getenv("FAIL_RATE"); envRate != "" {
    if parsed, err := strconv.ParseFloat(envRate, 64); err == nil {
        failRate = parsed
    }
}
// Start cleanup goroutine
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

**`Work()` handler — add before mutex:**
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

**After successful reply (inside Work(), before return):**
```go
if req.GetId() != "" {
    seenRequests.Store(req.GetId(), cachedReply{
        reply:   reply,
        expires: time.Now().Add(30 * time.Second),
    })
}
```

**New imports:** `"google.golang.org/grpc/codes"`, `"google.golang.org/grpc/status"`, `"math/rand"`

**New metric in metricsHandler:**
```go
fmt.Fprintf(w, "b_fail_rate %.2f\n", failRate)
```

---

### App-A: `ErrorCode.java`

Change RESOURCE_EXHAUSTED mapping from QUEUE_FULL → new RATE_LIMITED:

```java
// Add new enum value:
RATE_LIMITED,

// In fromGrpcStatus():
case RESOURCE_EXHAUSTED:
    return RATE_LIMITED;   // was QUEUE_FULL
```

---

### App-A: New `RetryBClient.java`

Activated when `retry.enabled=true` AND `resilience.enabled=false`. Single
channel, gRPC retry service config only — no CB, no bulkhead, no deadline.

```java
@Component
@ConditionalOnExpression("'${retry.enabled:false}' == 'true' && '${resilience.enabled:false}' == 'false'")
public class RetryBClient implements BClientPort {
    @Value("${b.service.url}") private String bServiceUrl;
    @Autowired private MetricsService metricsService;

    private ManagedChannel channel;
    private DemoServiceGrpc.DemoServiceBlockingStub stub;

    @PostConstruct
    public void init() {
        Map<String, Object> retryPolicy = Map.of(
            "maxAttempts", "3",
            "initialBackoff", "0.05s",
            "maxBackoff", "0.5s",
            "backoffMultiplier", 2.0,
            "retryableStatusCodes", List.of("RESOURCE_EXHAUSTED")
        );
        Map<String, Object> serviceConfig = Map.of(
            "methodConfig", List.of(Map.of("name", List.of(Map.of()), "retryPolicy", retryPolicy))
        );
        channel = ManagedChannelBuilder.forTarget(bServiceUrl)
            .usePlaintext()
            .defaultServiceConfig(serviceConfig)
            .enableRetry()
            .build();
        stub = DemoServiceGrpc.newBlockingStub(channel);
    }

    @Override
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

    @PreDestroy public void shutdown() { channel.shutdown(); }
}
```

---

### App-A: `ResilientBClient.java` — channel builder

Add retry service config alongside existing keepalive:

```java
Map<String, Object> retryPolicy = Map.of(
    "maxAttempts", "3",
    "initialBackoff", "0.05s",
    "maxBackoff", "0.5s",
    "backoffMultiplier", 2.0,
    "retryableStatusCodes", List.of("RESOURCE_EXHAUSTED")
);
Map<String, Object> serviceConfig = Map.of(
    "methodConfig", List.of(Map.of("name", List.of(Map.of()), "retryPolicy", retryPolicy))
);

ManagedChannel ch = ManagedChannelBuilder.forTarget(bServiceUrl)
    .usePlaintext()
    .keepAliveTime(30, TimeUnit.SECONDS)
    .keepAliveTimeout(10, TimeUnit.SECONDS)
    .keepAliveWithoutCalls(true)
    .defaultServiceConfig(serviceConfig)   // NEW
    .enableRetry()                          // NEW
    .build();
```

---

### App-A: `BClient.java`

Narrow activation — only when BOTH resilience AND retry are false:

```java
@ConditionalOnExpression("'${resilience.enabled:false}' == 'false' && '${retry.enabled:false}' == 'false'")
```

---

### App-A: `application.yml`

```yaml
retry:
  enabled: ${RETRY_ENABLED:false}
```

---

### Chart: New values files

**`chart/values-scenario1.yaml`** (baseline):
```yaml
appA:
  env:
    - {name: RESILIENCE_ENABLED, value: "false"}
    - {name: RETRY_ENABLED,      value: "false"}
appB:
  env:
    - {name: B_DELAY_MS, value: "5"}
    - {name: FAIL_RATE,  value: "0.3"}
```

**`chart/values-scenario2.yaml`** (+retry+idempotency):
```yaml
appA:
  env:
    - {name: RESILIENCE_ENABLED, value: "false"}
    - {name: RETRY_ENABLED,      value: "true"}
appB:
  env:
    - {name: B_DELAY_MS, value: "5"}
    - {name: FAIL_RATE,  value: "0.3"}
```

**`chart/values-scenario3.yaml`** (+deadline+bulkhead+CB):
```yaml
appA:
  env:
    - {name: RESILIENCE_ENABLED, value: "true"}
    - {name: RETRY_ENABLED,      value: "true"}
    - {name: DEADLINE_MS,        value: "800"}
    - {name: MAX_INFLIGHT,       value: "10"}
    - {name: CHANNEL_POOL_SIZE,  value: "1"}
appB:
  env:
    - {name: B_DELAY_MS, value: "200"}
    - {name: FAIL_RATE,  value: "0.3"}
```

**`chart/values-scenario4.yaml`** (+keepalive+channel pool):
```yaml
appA:
  env:
    - {name: RESILIENCE_ENABLED, value: "true"}
    - {name: RETRY_ENABLED,      value: "true"}
    - {name: DEADLINE_MS,        value: "800"}
    - {name: MAX_INFLIGHT,       value: "10"}
    - {name: CHANNEL_POOL_SIZE,  value: "4"}
appB:
  env:
    - {name: B_DELAY_MS, value: "5"}
    - {name: FAIL_RATE,  value: "0.3"}
```

Old `values-baseline.yaml`, `values-resilient.yaml`, `values-s1.yaml`,
`values-s4.yaml` — **keep** with deprecated header comments.

---

### Scripts: `scripts/run_scenario.sh`

New interface: `./scripts/run_scenario.sh <1|2|3|4>`

- Validates arg is 1–4
- Composes: `values-common.yaml` + `values-scenario<N>.yaml`
- Fortio params: Scenarios 1-2: QPS=200, C=50, T=60s. Scenario 3: QPS=200,
  C=80, T=60s (overload needed). Scenario 4: QPS=200, C=50, T=60s, fault
  injection at t=15s.
- Artifacts saved to `tmp/artifacts/scenario<N>/`
- Old `<scenario> <mode>` interface removed.

---

### Tests: New verify scripts

**`tests/verify_scenario2.sh`:**
- C08: Scenario 1 RATE_LIMITED > 1000 (failures without retry)
- C09: Scenario 2 RATE_LIMITED < 100 (retry absorbs ~90%)
- C10: S1 RATE_LIMITED > S2 RATE_LIMITED (directional)

**`tests/verify_scenario3.sh`** (adapted from verify_s1.sh):
- C01: Scenario 1 max latency > Scenario 3 max latency
- C02: Scenario 3 QUEUE_FULL + CIRCUIT_OPEN > 100

**`tests/verify_scenario4.sh`** (adapted from verify_s4.sh):
- C05: S4 UNAVAILABLE > 50
- C06: S4 SUCCESS > 10000
- C07: UNAVAILABLE < 10% of SUCCESS

Old `verify_s1.sh`, `verify_s4.sh` — **keep** with deprecated header.

---

### Docs

- `docs/plan2.md` — new file: full roadmap design (spine document)
- `README.md` — rewrite: 4-scenario table leads, coverage matrix is hero diagram
- `docs/runbook.md` — update drill commands to `run_scenario.sh 1-4`

---

## Helm Chart Template Check

Both `app-a-deployment.yaml` and `app-b-deployment.yaml` already use
`.Values.appA.env` / `.Values.appB.env` loops — no template changes needed.
`FAIL_RATE` and `RETRY_ENABLED` flow through cleanly.

---

## Verification (End-to-End)

```bash
# Build + load images (after code changes)
./scripts/build-images.sh
./scripts/load-images-kind.sh

# Run all four scenarios
./scripts/run_scenario.sh 1
./scripts/run_scenario.sh 2
./scripts/run_scenario.sh 3
./scripts/run_scenario.sh 4

# Verify
./tests/verify_scenario2.sh   # PASS=3 FAIL=0
./tests/verify_scenario3.sh   # PASS=2 FAIL=0
./tests/verify_scenario4.sh   # PASS=3 FAIL=0
```

---

## Execution Order

1. **T13** (#21): App-B code (FAIL_RATE + dedup) — unblocks all else
2. **T14** (#22): App-A code (RATE_LIMITED + RetryBClient + retry in Resilient)
3. **T15** (#23): Chart values — depends on T14 (RETRY_ENABLED must exist)
4. **T16** (#24): run_scenario.sh — depends on T15 (values files must exist)
5. **T17** (#25): verify scripts — depends on T16 (artifact paths must match)
6. **T18** (#26): Docs — depends on all
