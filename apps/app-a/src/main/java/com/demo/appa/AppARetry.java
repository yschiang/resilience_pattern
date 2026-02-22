package com.demo.appa;

import com.demo.appa.retry.RetryDecisionPolicy;
import com.demo.grpc.DemoServiceGrpc;
import com.demo.grpc.WorkReply;
import com.demo.grpc.WorkRequest;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;

/**
 * Scenario 2: Retry + Idempotency (no circuit breaker/bulkhead/deadline).
 *
 * LEARNING: Retry helps with transient failures BUT can amplify load:
 * - Scenario 2 (fast B, 5ms): Retry reduces visible errors from 30% → 3% ✅
 * - Scenario 3 (slow B, 200ms): Retry amplifies load up to 3× → accelerates saturation ❌
 *
 * KEY INSIGHT: Retry is NOT free. It must be paired with a circuit breaker to prevent
 * retry amplification from making overload worse. This class intentionally shows retry
 * in isolation so learners can observe the anti-pattern before Scenario 3 fixes it.
 */
@Component
@ConditionalOnExpression("'${retry.enabled:false}' == 'true' && '${resilience.enabled:false}' == 'false'")
public class AppARetry implements AppAPort {

    @Value("${b.service.url}")
    private String bServiceUrl;

    @Autowired
    private MetricsService metricsService;

    @Autowired
    private RetryDecisionPolicy retryPolicy;

    private ManagedChannel channel;
    private DemoServiceGrpc.DemoServiceBlockingStub stub;
    private Retry retry;

    @PostConstruct
    public void init() {
        // LEARNING: Plain gRPC channel - retry is handled by Resilience4j wrapper (below).
        // Still no timeout, no keepalive, no bulkhead (those come in Scenario 3).
        channel = ManagedChannelBuilder.forTarget(bServiceUrl)
            .usePlaintext()
            .build();
        stub = DemoServiceGrpc.newBlockingStub(channel);

        // LEARNING: Resilience4j Retry configuration:
        // - maxAttempts=3: Will try up to 3 times (1 initial + 2 retries)
        // - waitDuration=50ms: Exponential backoff starting at 50ms
        // - retryOnException: Uses RetryDecisionPolicy for classifier-based retry gating
        //
        // CRITICAL: Only retries errors marked retryable=true by GrpcErrorClassifier:
        //   ✅ BACKEND_ERROR (RESOURCE_EXHAUSTED) → retryable
        //   ❌ CIRCUIT_OPEN, BULKHEAD_REJECTED → NOT retryable (would defeat protection)
        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(50))
            .retryOnException(e -> retryPolicy.shouldRetry(e, null))
            .build();
        retry = Retry.of("app-a-retry", retryConfig);
    }

    @Override
    public WorkResult callWork(String requestId) {
        long start = System.currentTimeMillis();

        try {
            // LEARNING: retry.executeSupplier() wraps the gRPC call with retry logic.
            // When B returns RESOURCE_EXHAUSTED:
            //   1. Attempt 1 fails → RetryDecisionPolicy checks retryable=true → retry
            //   2. Wait 50ms (exponential backoff)
            //   3. Attempt 2 with SAME requestId → B checks idempotency cache → may hit
            //   4. If still fails, wait 100ms, attempt 3
            //   5. After 3 attempts exhausted → throw exception to caller
            //
            // Result: Visible error rate drops from 30% → ~3% (0.3³ = 2.7%)
            // Cost: Downstream RPC volume increases ~1.3× (retry amplification)
            WorkReply reply = retry.executeSupplier(() ->
                stub.work(WorkRequest.newBuilder().setId(requestId).build())
            );

            long latency = System.currentTimeMillis() - start;
            metricsService.recordCall("Work", latency, null, null);
            metricsService.recordDownstreamCall(latency, ErrorCode.SUCCESS);
            return new WorkResult(true, "SUCCESS", latency, ErrorCode.SUCCESS);

        } catch (StatusRuntimeException e) {
            // LEARNING: This exception means all 3 retry attempts failed.
            // Latency includes all retry attempts + backoff delays.
            long latency = System.currentTimeMillis() - start;
            ErrorCode code = ErrorCode.fromGrpcStatus(e.getStatus().getCode());
            metricsService.recordCall("Work", latency, e, null);
            metricsService.recordDownstreamCall(latency, code);
            return new WorkResult(false, code.name(), latency, code);
        }
    }

    @PreDestroy
    public void shutdown() {
        channel.shutdown();
    }
}
