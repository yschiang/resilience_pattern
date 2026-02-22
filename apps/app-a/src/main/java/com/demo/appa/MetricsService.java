package com.demo.appa;

import com.demo.appa.observability.CallOutcome;
import com.demo.appa.observability.GrpcErrorClassifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrics service for tracking downstream calls to service B.
 * Exposes metrics via /actuator/prometheus endpoint.
 */
@Service
public class MetricsService {
    private final MeterRegistry registry;

    @Autowired
    private GrpcErrorClassifier classifier;
    private final Timer downstreamLatency;
    private final AtomicInteger inflightRequests;
    private final AtomicInteger breakerState;

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
        this.inflightRequests = new AtomicInteger(0);
        this.breakerState = new AtomicInteger(0);

        // Timer for latency tracking (automatically provides p95, p99 quantiles)
        this.downstreamLatency = Timer.builder("a_downstream_latency_ms")
                .description("Latency of downstream calls to service B")
                .tag("downstream", "B")
                .tag("method", "Work")
                .publishPercentiles(0.95, 0.99)
                .register(registry);

        // Gauge for inflight requests
        Gauge.builder("a_downstream_inflight", inflightRequests, AtomicInteger::get)
                .description("Number of in-flight requests to downstream B")
                .tag("downstream", "B")
                .register(registry);

        // Gauge for circuit breaker state (0=closed, 1=open, 2=half-open)
        Gauge.builder("a_breaker_state", breakerState, AtomicInteger::get)
                .description("Circuit breaker state for downstream B")
                .tag("downstream", "B")
                .register(registry);
    }

    public void setBreakerState(int state) {
        breakerState.set(state);
    }

    public void registerChannelPoolSize(int size) {
        Gauge.builder("a_channel_pool_size", () -> size)
                .description("Number of gRPC channels in the client pool")
                .tag("downstream", "B")
                .register(registry);
    }

    /**
     * Record a downstream call with its result.
     * @param durationMs duration in milliseconds
     * @param errorCode result error code
     */
    public void recordDownstreamCall(long durationMs, ErrorCode errorCode) {
        // Record latency
        downstreamLatency.record(Duration.ofMillis(durationMs));

        // Record error by code
        Counter.builder("a_downstream_errors_total")
                .description("Total errors by code for downstream B")
                .tag("downstream", "B")
                .tag("method", "Work")
                .tag("code", errorCode.name())
                .register(registry)
                .increment();
    }

    /**
     * Record a gRPC client call outcome (Workstream A standardized metrics).
     *
     * @param method gRPC method name (e.g., "Work")
     * @param latencyMs Call latency in milliseconds
     * @param error Exception thrown, or null for success
     * @param contextHint Optional hint for protection events (e.g., "CIRCUIT_OPEN")
     */
    public void recordCall(String method, long latencyMs, @Nullable Throwable error, @Nullable String contextHint) {
        CallOutcome outcome = classifier.classify(error, contextHint);

        // Counter: grpc_client_requests_total
        Counter.builder("grpc_client_requests_total")
            .description("Total gRPC client requests")
            .tag("service", "demo-service-b")
            .tag("method", method)
            .tag("result", outcome.resultLabel())
            .tag("reason", outcome.reason().name())
            .tag("retryable", String.valueOf(outcome.retryable()))
            .register(registry)
            .increment();

        // Histogram: grpc_client_latency_ms
        // Enable histogram buckets for PromQL histogram_quantile() queries
        Timer.builder("grpc_client_latency_ms")
            .description("gRPC client request latency")
            .tag("service", "demo-service-b")
            .tag("method", method)
            .serviceLevelObjectives(
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofMillis(500),
                Duration.ofMillis(1000),
                Duration.ofMillis(2000),
                Duration.ofMillis(5000)
            )
            .register(registry)
            .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Increment inflight counter (call before making downstream request).
     */
    public void incrementInflight() {
        inflightRequests.incrementAndGet();
    }

    /**
     * Decrement inflight counter (call after downstream request completes).
     */
    public void decrementInflight() {
        inflightRequests.decrementAndGet();
    }
}
