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
public class AppARetry implements AppAPort {

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
    public void shutdown() { channel.shutdown(); }
}
