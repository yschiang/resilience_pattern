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
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Component
public class BClient {
    private static final Logger logger = LoggerFactory.getLogger(BClient.class);

    @Value("${b.service.url}")
    private String bServiceUrl;

    @Autowired
    private MetricsService metricsService;

    private ManagedChannel channel;
    private DemoServiceGrpc.DemoServiceBlockingStub blockingStub;

    @PostConstruct
    public void init() {
        logger.info("Initializing gRPC channel to B service: {}", bServiceUrl);
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

        metricsService.incrementInflight();
        try {
            WorkRequest request = WorkRequest.newBuilder()
                    .setId(requestId)
                    .build();

            WorkReply reply = blockingStub.work(request);
            long latency = System.currentTimeMillis() - startTime;

            errorCode = ErrorCode.SUCCESS;
            metricsService.recordDownstreamCall(latency, errorCode);

            return new WorkResult(
                    reply.getOk(),
                    reply.getCode(),
                    latency,
                    errorCode
            );
        } catch (StatusRuntimeException e) {
            long latency = System.currentTimeMillis() - startTime;
            errorCode = ErrorCode.fromGrpcStatus(e.getStatus().getCode());
            logger.error("gRPC call failed: {} -> {}", e.getStatus(), errorCode, e);

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

    public static class WorkResult {
        private final boolean ok;
        private final String code;
        private final long latencyMs;
        private final ErrorCode errorCode;

        public WorkResult(boolean ok, String code, long latencyMs, ErrorCode errorCode) {
            this.ok = ok;
            this.code = code;
            this.latencyMs = latencyMs;
            this.errorCode = errorCode;
        }

        public boolean isOk() {
            return ok;
        }

        public String getCode() {
            return code;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public ErrorCode getErrorCode() {
            return errorCode;
        }
    }
}
