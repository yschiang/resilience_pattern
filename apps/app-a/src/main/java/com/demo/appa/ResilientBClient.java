package com.demo.appa;

import com.demo.grpc.DemoServiceGrpc;
import com.demo.grpc.WorkReply;
import com.demo.grpc.WorkRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "resilience.enabled", havingValue = "true")
public class ResilientBClient implements BClientPort {
    private static final Logger logger = LoggerFactory.getLogger(ResilientBClient.class);

    @Value("${b.service.url}")
    private String bServiceUrl;

    @Value("${b.deadline.ms:800}")
    private long deadlineMs;

    @Value("${b.inflight.max:10}")
    private int maxInflight;

    @Autowired
    private MetricsService metricsService;

    private ManagedChannel channel;
    private DemoServiceGrpc.DemoServiceBlockingStub blockingStub;
    private Semaphore semaphore;
    private CircuitBreaker circuitBreaker;

    @PostConstruct
    public void init() {
        logger.info("ResilientBClient initialized: url={}, deadlineMs={}, maxInflight={}",
                bServiceUrl, deadlineMs, maxInflight);

        channel = ManagedChannelBuilder.forTarget(bServiceUrl)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
        blockingStub = DemoServiceGrpc.newBlockingStub(channel);

        semaphore = new Semaphore(maxInflight);

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
                case CLOSED -> 0;
                case OPEN -> 1;
                case HALF_OPEN -> 2;
                default -> 0;
            };
            metricsService.setBreakerState(stateCode);
            logger.info("Circuit breaker B state -> {} ({})", state, stateCode);
        });
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null) {
            logger.info("Shutting down ResilientBClient gRPC channel");
            channel.shutdown();
        }
    }

    @Override
    public WorkResult callWork(String requestId) {
        // Check circuit breaker first
        if (!circuitBreaker.tryAcquirePermission()) {
            logger.warn("Circuit breaker OPEN for request {}", requestId);
            metricsService.recordDownstreamCall(0, ErrorCode.CIRCUIT_OPEN);
            return new WorkResult(false, ErrorCode.CIRCUIT_OPEN.name(), 0, ErrorCode.CIRCUIT_OPEN);
        }

        // Check bulkhead (semaphore)
        if (!semaphore.tryAcquire()) {
            circuitBreaker.releasePermission();
            logger.warn("Bulkhead full (QUEUE_FULL) for request {}", requestId);
            metricsService.recordDownstreamCall(0, ErrorCode.QUEUE_FULL);
            return new WorkResult(false, ErrorCode.QUEUE_FULL.name(), 0, ErrorCode.QUEUE_FULL);
        }

        long startTime = System.currentTimeMillis();
        metricsService.incrementInflight();
        ErrorCode errorCode = ErrorCode.UNKNOWN;

        try {
            WorkRequest request = WorkRequest.newBuilder()
                    .setId(requestId)
                    .build();

            WorkReply reply = blockingStub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .work(request);

            long latency = System.currentTimeMillis() - startTime;
            errorCode = ErrorCode.SUCCESS;
            circuitBreaker.onSuccess(latency, TimeUnit.MILLISECONDS);
            metricsService.recordDownstreamCall(latency, errorCode);

            return new WorkResult(reply.getOk(), reply.getCode(), latency, errorCode);

        } catch (StatusRuntimeException e) {
            long latency = System.currentTimeMillis() - startTime;
            errorCode = ErrorCode.fromGrpcStatus(e.getStatus().getCode());
            logger.error("gRPC call failed: {} -> {}, requestId={}", e.getStatus(), errorCode, requestId);
            circuitBreaker.onError(latency, TimeUnit.MILLISECONDS, e);
            metricsService.recordDownstreamCall(latency, errorCode);

            return new WorkResult(false, errorCode.name(), latency, errorCode);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            errorCode = ErrorCode.UNKNOWN;
            logger.error("Unexpected error calling B service, requestId={}", requestId, e);
            circuitBreaker.onError(latency, TimeUnit.MILLISECONDS, e);
            metricsService.recordDownstreamCall(latency, errorCode);

            return new WorkResult(false, errorCode.name(), latency, errorCode);

        } finally {
            semaphore.release();
            metricsService.decrementInflight();
        }
    }
}
