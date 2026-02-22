/**
 * Scenario 3 & 4: Full resilience pattern stack.
 *
 * LEARNING: Protection layers are checked in order of cost (cheapest first):
 * 1. Circuit Breaker (~1μs, in-memory state check) - CHEAPEST, shed load before network
 * 2. Bulkhead (~1μs, semaphore CAS) - Cap concurrent requests to prevent thread exhaustion
 * 3. gRPC call with Deadline + Retry - MOST EXPENSIVE, actual network I/O
 * 4. CB result recording - Update sliding window for future trip decisions
 *
 * This ordering is CRITICAL: expensive operations (network) come last, so when CB is OPEN,
 * we reject requests instantly without consuming threads or touching the network.
 *
 * Scenario 3 (slow B, pool=1): Shows CB + bulkhead + deadline containing overload
 * Scenario 4 (TCP reset, pool=4): Shows keepalive + channel pool enabling self-heal
 */
package com.demo.appa;

import com.demo.appa.retry.RetryDecisionPolicy;
import com.demo.grpc.DemoServiceGrpc;
import com.demo.grpc.WorkReply;
import com.demo.grpc.WorkRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "resilience.enabled", havingValue = "true")
public class AppAResilient implements AppAPort {
    private static final Logger logger = LoggerFactory.getLogger(AppAResilient.class);

    @Value("${b.service.url}")
    private String bServiceUrl;

    @Value("${b.deadline.ms:800}")
    private long deadlineMs;

    @Value("${b.inflight.max:10}")
    private int maxInflight;

    @Value("${b.channel.pool.size:1}")
    private int channelPoolSize;

    @Autowired
    private MetricsService metricsService;

    @Autowired
    private RetryDecisionPolicy retryPolicy;

    private List<ManagedChannel> channels;
    private List<DemoServiceGrpc.DemoServiceBlockingStub> stubs;
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private Semaphore semaphore;
    private CircuitBreaker circuitBreaker;
    private Retry retry;

    @PostConstruct
    public void init() {
        logger.info("ResilientBClient initialized: url={}, deadlineMs={}, maxInflight={}, channelPoolSize={}",
                bServiceUrl, deadlineMs, maxInflight, channelPoolSize);
        logger.info("gRPC keepalive: keepAliveTime=30s, keepAliveTimeout=10s, keepAliveWithoutCalls=true");

        // LEARNING: gRPC keepalive configuration (Scenario 4: selfheal)
        // Why keepalive? Without it, dead TCP connections take ~11 minutes to detect (OS keepalive).
        // With gRPC keepalive, detection happens in 10-40s:
        //   - keepAliveTime=30s: Send HTTP/2 PING every 30s
        //   - keepAliveTimeout=10s: If no PONG in 10s, declare connection GOAWAY
        //   - keepAliveWithoutCalls=true: PING even when idle (detects RST while idle)
        //
        // Why channel pool? Blast radius containment:
        //   - pool=1 (Scenario 3): TCP RST kills ALL inflight RPCs → spike of 100s errors
        //   - pool=4 (Scenario 4): TCP RST kills only 1/4 of channels → smaller bursts
        //   - Each channel reconnects independently → graceful degradation
        channels = new ArrayList<>(channelPoolSize);
        stubs = new ArrayList<>(channelPoolSize);
        for (int i = 0; i < channelPoolSize; i++) {
            ManagedChannel ch = ManagedChannelBuilder.forTarget(bServiceUrl)
                    .usePlaintext()
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .build();  // Retry is handled by Resilience4j (below)
            channels.add(ch);
            stubs.add(DemoServiceGrpc.newBlockingStub(ch));
        }

        metricsService.registerChannelPoolSize(channelPoolSize);

        // LEARNING: Bulkhead (semaphore) limits concurrent inflight requests.
        // Why? Without it, all client threads can block waiting for slow B → thread starvation.
        // With maxInflight=10: Only 10 requests wait for B; others rejected with QUEUE_FULL.
        // This CAPS the blast radius: slow downstream cannot consume all client threads.
        semaphore = new Semaphore(maxInflight);

        // LEARNING: Circuit Breaker prevents cascading failure and retry amplification.
        // Configuration:
        //   - slidingWindowSize=10: Track last 10 call results
        //   - failureRateThreshold=50%: If ≥5 of 10 fail → trip OPEN
        //   - waitDurationInOpenState=5s: Stay OPEN for 5s (shed all load)
        //   - permittedNumberOfCallsInHalfOpenState=3: After 5s, allow 3 probe calls
        //
        // State machine:
        //   CLOSED (normal) → OPEN (shedding) → HALF_OPEN (probing) → CLOSED or OPEN
        //
        // Why this matters in Scenario 3:
        //   - B is slow (200ms) + 30% fail rate
        //   - Retry amplifies load up to 3× → accelerates saturation
        //   - CB trips OPEN → sheds load BEFORE retry happens → prevents amplification
        //   - Result: 83% of traffic returns CIRCUIT_OPEN (instant, no network) instead of
        //     waiting for slow B and retrying, which would make the problem worse
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        circuitBreaker = registry.circuitBreaker("B");

        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State state = event.getStateTransition().getToState();
            int stateCode = switch (state) {
                case CLOSED -> 0;   // Normal operation
                case OPEN -> 1;     // Shedding load (fast-fail)
                case HALF_OPEN -> 2; // Probing recovery
                default -> 0;
            };
            metricsService.setBreakerState(stateCode);
            logger.info("Circuit breaker B state -> {} ({})", state, stateCode);
        });

        // LEARNING: Retry with CRITICAL safety constraints (retry gating).
        // Configuration same as Scenario 2 (maxAttempts=3, 50ms backoff), BUT:
        //
        // CRITICAL: Protection events are NEVER retried:
        //   ❌ CIRCUIT_OPEN (CallNotPermittedException) → NO retry
        //   ❌ BULKHEAD_REJECTED → NO retry (checked via RetryDecisionPolicy)
        //   ❌ TIMEOUT (DEADLINE_EXCEEDED) → NO retry
        //
        // Why this matters:
        //   - Without this constraint, retry would DEFEAT the circuit breaker:
        //     CB opens → retry anyway → infinite loop
        //   - Bulkhead rejection means "too much load" → retrying makes it worse
        //   - Timeouts already exceeded deadline → retrying amplifies load
        //
        // What IS retried:
        //   ✅ BACKEND_ERROR (RESOURCE_EXHAUSTED) → transient B failure
        //   ✅ CONNECTION_FAILURE (UNAVAILABLE) → network glitch
        //
        // Result: Retry helps with transient errors but respects protection boundaries.
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(50))
                .retryOnException(e -> {
                    // Do NOT retry CallNotPermittedException (circuit breaker rejection)
                    if (e instanceof CallNotPermittedException) {
                        return false;
                    }
                    return retryPolicy.shouldRetry(e, null);
                })
                .build();
        retry = Retry.of("app-a-resilient-retry", retryConfig);
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down ResilientBClient gRPC channel pool (size={})", channelPoolSize);
        if (channels != null) {
            channels.forEach(ManagedChannel::shutdown);
        }
    }

    @Override
    public WorkResult callWork(String requestId) {
        // LEARNING: Protection layers checked in order of cost (CHEAPEST FIRST).
        // This ordering is CRITICAL for efficiency under overload.

        // LAYER 1: Circuit Breaker check (~1μs, in-memory)
        // Why first? Cheapest operation. When CB is OPEN (shedding load), we reject
        // requests instantly without touching semaphore, network, or any other resource.
        // In Scenario 3, CB sheds 83% of traffic here → saves thread pool exhaustion.
        if (!circuitBreaker.tryAcquirePermission()) {
            logger.warn("Circuit breaker OPEN for request {}", requestId);
            metricsService.recordCall("Work", 0, null, "CIRCUIT_OPEN");
            metricsService.recordDownstreamCall(0, ErrorCode.CIRCUIT_OPEN);
            return new WorkResult(false, ErrorCode.CIRCUIT_OPEN.name(), 0, ErrorCode.CIRCUIT_OPEN);
        }

        // LAYER 2: Bulkhead check (~1μs, semaphore CAS)
        // Why second? Still cheap (compare-and-swap), but comes after CB so we don't
        // waste semaphore permits on requests that would be CB-rejected anyway.
        // If bulkhead is full (10 concurrent requests already inflight), reject immediately.
        if (!semaphore.tryAcquire()) {
            circuitBreaker.releasePermission();
            logger.warn("Bulkhead full (QUEUE_FULL) for request {}", requestId);

            // NEW: Use standard "BULKHEAD_REJECTED" reason for new metrics
            metricsService.recordCall("Work", 0, null, "BULKHEAD_REJECTED");

            // LEGACY: Keep QUEUE_FULL for backward compatibility
            metricsService.recordDownstreamCall(0, ErrorCode.QUEUE_FULL);

            return new WorkResult(false, ErrorCode.QUEUE_FULL.name(), 0, ErrorCode.QUEUE_FULL);
        }

        // LAYER 3: Actual gRPC call (MOST EXPENSIVE - network I/O)
        // Now that CB and bulkhead passed, we've acquired a semaphore permit
        // and can proceed with the expensive network operation.
        long startTime = System.currentTimeMillis();
        metricsService.incrementInflight();
        ErrorCode errorCode = ErrorCode.UNKNOWN;

        // LEARNING: Round-robin channel selection (Scenario 4: channel pool)
        // With pool=4, requests distribute across 4 independent gRPC channels.
        // If one channel's TCP connection RST, only ~25% of concurrent requests fail.
        // Each channel reconnects independently → blast radius contained.
        DemoServiceGrpc.DemoServiceBlockingStub stub =
                stubs.get(Math.abs(roundRobin.getAndIncrement() % channelPoolSize));

        try {
            WorkRequest request = WorkRequest.newBuilder()
                    .setId(requestId)
                    .build();

            // LEARNING: Deadline (timeout) + Retry inside bulkhead protection
            // - withDeadlineAfter(800ms): Cap max wait time. If B is slow (200ms in S3)
            //   and queue wait is high, request times out instead of waiting indefinitely.
            // - retry.executeSupplier(): Wraps call with retry logic (up to 3 attempts)
            //
            // CRITICAL ORDERING: Retry happens INSIDE semaphore protection.
            // This means retry attempts count against the bulkhead limit (good!).
            // If retry happened OUTSIDE semaphore, retries could bypass bulkhead → defeats it.
            WorkReply reply = retry.executeSupplier(() ->
                stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .work(request)
            );

            long latency = System.currentTimeMillis() - startTime;
            errorCode = ErrorCode.SUCCESS;
            circuitBreaker.onSuccess(latency, TimeUnit.MILLISECONDS);
            metricsService.recordCall("Work", latency, null, null);
            metricsService.recordDownstreamCall(latency, errorCode);

            return new WorkResult(reply.getOk(), reply.getCode(), latency, errorCode);

        } catch (StatusRuntimeException e) {
            long latency = System.currentTimeMillis() - startTime;
            errorCode = ErrorCode.fromGrpcStatus(e.getStatus().getCode());
            logger.error("gRPC call failed: {} -> {}, requestId={}", e.getStatus(), errorCode, requestId);
            circuitBreaker.onError(latency, TimeUnit.MILLISECONDS, e);
            metricsService.recordCall("Work", latency, e, null);
            metricsService.recordDownstreamCall(latency, errorCode);

            return new WorkResult(false, errorCode.name(), latency, errorCode);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            errorCode = ErrorCode.UNKNOWN;
            logger.error("Unexpected error calling B service, requestId={}", requestId, e);
            circuitBreaker.onError(latency, TimeUnit.MILLISECONDS, e);
            metricsService.recordCall("Work", latency, e, null);
            metricsService.recordDownstreamCall(latency, errorCode);

            return new WorkResult(false, errorCode.name(), latency, errorCode);

        } finally {
            semaphore.release();
            metricsService.decrementInflight();
        }
    }
}
