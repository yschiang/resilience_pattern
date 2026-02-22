package com.demo.appa;

import com.demo.grpc.DemoServiceGrpc;
import com.demo.grpc.WorkRequest;
import com.demo.grpc.WorkReply;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * Scenario 1: Baseline - No resilience patterns.
 *
 * LEARNING: This shows what happens WITHOUT any protection:
 * - Transient failures propagate directly to caller (no retry)
 * - Slow backends cause cascading delays (no timeout)
 * - Overload spreads to all threads (no bulkhead)
 * - Connection failures take minutes to detect (no keepalive)
 *
 * Use this to understand the COST of not having resilience patterns.
 */
@Component
@ConditionalOnExpression("'${resilience.enabled:false}' == 'false' && '${retry.enabled:false}' == 'false'")
public class AppABaseline implements AppAPort {
    private static final Logger logger = LoggerFactory.getLogger(AppABaseline.class);

    @Value("${b.service.url}")
    private String bServiceUrl;

    @Autowired
    private MetricsService metricsService;

    private ManagedChannel channel;
    private DemoServiceGrpc.DemoServiceBlockingStub blockingStub;

    @PostConstruct
    public void init() {
        logger.info("Initializing gRPC channel to B service: {}", bServiceUrl);
        // LEARNING: Plain gRPC channel with NO resilience configuration:
        // - No retry (transient failures are terminal)
        // - No timeout (can wait indefinitely)
        // - No keepalive (dead connections take ~11 minutes to detect via OS TCP)
        // - Single channel (connection reset affects all inflight requests)
        channel = ManagedChannelBuilder.forTarget(bServiceUrl)
                .usePlaintext()
                .build();
        blockingStub = DemoServiceGrpc.newBlockingStub(channel);
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null) {
            logger.info("Shutting down gRPC channel");
            channel.shutdown();
        }
    }

    public WorkResult callWork(String requestId) {
        long startTime = System.currentTimeMillis();
        ErrorCode errorCode = ErrorCode.UNKNOWN;

        // LEARNING: Inflight tracking shows thread blocking but does NOT limit concurrency.
        // Without a bulkhead, all client threads can block waiting for slow B.
        metricsService.incrementInflight();
        try {
            WorkRequest request = WorkRequest.newBuilder()
                    .setId(requestId)
                    .build();

            // LEARNING: Synchronous blocking call with NO timeout.
            // If B is slow (200ms in Scenario 3), this thread waits.
            // If B is unresponsive, this thread waits indefinitely.
            WorkReply reply = blockingStub.work(request);
            long latency = System.currentTimeMillis() - startTime;

            errorCode = ErrorCode.SUCCESS;
            metricsService.recordCall("Work", latency, null, null);
            metricsService.recordDownstreamCall(latency, errorCode);

            return new WorkResult(
                    reply.getOk(),
                    reply.getCode(),
                    latency,
                    errorCode
            );
        } catch (StatusRuntimeException e) {
            // LEARNING: Failures propagate directly to caller - no retry, no recovery.
            // In Scenario 1 (FAIL_RATE=0.3), 30% of calls fail here and return immediately.
            // In Scenario 2 (retry enabled), these same errors get retried → 30% drops to ~3%.
            long latency = System.currentTimeMillis() - startTime;
            errorCode = ErrorCode.fromGrpcStatus(e.getStatus().getCode());
            logger.error("gRPC call failed: {} -> {}", e.getStatus(), errorCode, e);

            metricsService.recordCall("Work", latency, e, null);
            metricsService.recordDownstreamCall(latency, errorCode);

            return new WorkResult(
                    false,
                    errorCode.name(),
                    latency,
                    errorCode
            );
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            errorCode = ErrorCode.UNKNOWN;
            logger.error("Unexpected error calling B service: {}", errorCode, e);

            metricsService.recordCall("Work", latency, e, null);
            metricsService.recordDownstreamCall(latency, errorCode);

            return new WorkResult(
                    false,
                    errorCode.name(),
                    latency,
                    errorCode
            );
        } finally {
            metricsService.decrementInflight();
        }
    }

}
