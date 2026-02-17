package com.demo.appa;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrics service for tracking downstream calls to service B.
 * Exposes metrics via /actuator/prometheus endpoint.
 */
@Service
public class MetricsService {
    private final MeterRegistry registry;
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
