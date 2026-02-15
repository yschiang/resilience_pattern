package com.demo.appa;

import com.demo.grpc.DemoServiceGrpc;
import com.demo.grpc.WorkRequest;
import com.demo.grpc.WorkReply;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Component
public class BClient {
    private static final Logger logger = LoggerFactory.getLogger(BClient.class);

    @Value("${b.service.url}")
    private String bServiceUrl;

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

        try {
            WorkRequest request = WorkRequest.newBuilder()
                    .setId(requestId)
                    .build();

            WorkReply reply = blockingStub.work(request);
            long latency = System.currentTimeMillis() - startTime;

            return new WorkResult(
                    reply.getOk(),
                    reply.getCode(),
                    latency
            );
        } catch (StatusRuntimeException e) {
            long latency = System.currentTimeMillis() - startTime;
            logger.error("gRPC call failed: {}", e.getStatus(), e);
            return new WorkResult(
                    false,
                    "ERROR",
                    latency
            );
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            logger.error("Unexpected error calling B service", e);
            return new WorkResult(
                    false,
                    "ERROR",
                    latency
            );
        }
    }

    public static class WorkResult {
        private final boolean ok;
        private final String code;
        private final long latencyMs;

        public WorkResult(boolean ok, String code, long latencyMs) {
            this.ok = ok;
            this.code = code;
            this.latencyMs = latencyMs;
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
    }
}
