# Developer Prompt — T14: App-A RATE_LIMITED + RetryBClient + Retry in Resilient

## Context
You are implementing GitHub issue #16 for the P5 Learning Roadmap milestone.
Read .clinerules/ in order before starting. Read tmp/T14/plan.md.
DEPENDS ON #15 (T13) being merged first.

## Task
5 changes across 5 files:

---

## 1. ErrorCode.java

Add new enum value RATE_LIMITED:
```java
/** Request rejected by downstream rate limiter (RESOURCE_EXHAUSTED, retryable) */
RATE_LIMITED,
```
Place it between CIRCUIT_OPEN and UNKNOWN.

Change mapping:
```java
case RESOURCE_EXHAUSTED:
    return RATE_LIMITED;   // was QUEUE_FULL
```

---

## 2. BClient.java

Change annotation (replace @ConditionalOnProperty):
```java
// Remove:
@ConditionalOnProperty(name = "resilience.enabled", havingValue = "false", matchIfMissing = true)

// Add:
@ConditionalOnExpression("'${resilience.enabled:false}' == 'false' && '${retry.enabled:false}' == 'false'")
```
Update import: replace ConditionalOnProperty with ConditionalOnExpression.

---

## 3. New file: RetryBClient.java

Path: apps/app-a/src/main/java/com/demo/appa/RetryBClient.java

```java
package com.demo.appa;

import com.demo.grpc.DemoServiceGrpc;
import com.demo.grpc.WorkReply;
import com.demo.grpc.WorkRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Map;

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

    @PreDestroy
    public void shutdown() { channel.shutdown(); }
}
```

---

## 4. ResilientBClient.java

Add `import java.util.Map;` to imports.

In the channel builder loop, add retry service config before `.build()`:
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

Note: the retryPolicy and serviceConfig must be defined OUTSIDE the loop
(define once before the loop, reference inside).

---

## 5. application.yml

Add under existing properties:
```yaml
retry:
  enabled: ${RETRY_ENABLED:false}
```

---

## DoD Proof Commands (save to tmp/T14/proof.txt)
```bash
# Proof 1: RATE_LIMITED in ErrorCode
grep "RATE_LIMITED" apps/app-a/src/main/java/com/demo/appa/ErrorCode.java

# Proof 2: RetryBClient exists
ls apps/app-a/src/main/java/com/demo/appa/RetryBClient.java

# Proof 3: application.yml has retry.enabled
grep "retry" apps/app-a/src/main/resources/application.yml

# Proof 4: build succeeds
docker build -t app-a:dev ./apps/app-a && echo "Build OK"
```

## Commit Message (save to tmp/T14/commit_msg.txt)
```
feat: add RetryBClient, RATE_LIMITED error code, retry service config

- RESOURCE_EXHAUSTED now maps to RATE_LIMITED (retryable, not bulkhead)
- RetryBClient activates when retry.enabled=true, resilience.enabled=false
- BClient condition narrowed: only when both retry and resilience are false
- ResilientBClient gains gRPC retry config for RESOURCE_EXHAUSTED

Fixes #16
```

## After Implementing
Follow 10_workflow_implement_task.md Steps 5-8.
Do NOT merge your own PR. Post proof to issue, wait for architect review.
