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
        // Plain gRPC channel without retry config (retry handled by Resilience4j)
        channel = ManagedChannelBuilder.forTarget(bServiceUrl)
            .usePlaintext()
            .build();
        stub = DemoServiceGrpc.newBlockingStub(channel);

        // Resilience4j Retry with classifier-based predicate
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
            // Wrap gRPC call with Resilience4j retry
            WorkReply reply = retry.executeSupplier(() ->
                stub.work(WorkRequest.newBuilder().setId(requestId).build())
            );

            long latency = System.currentTimeMillis() - start;
            metricsService.recordCall("Work", latency, null, null);
            metricsService.recordDownstreamCall(latency, ErrorCode.SUCCESS);
            return new WorkResult(true, "SUCCESS", latency, ErrorCode.SUCCESS);

        } catch (StatusRuntimeException e) {
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
