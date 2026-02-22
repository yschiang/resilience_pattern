package com.demo.appa.retry;

import com.demo.appa.observability.GrpcErrorClassifier;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RetryDecisionPolicy.
 *
 * Test matrix covers all error reasons and validates retry policy:
 * - RESOURCE_EXHAUSTED (BACKEND_ERROR) → retry (Scenario 2 requirement)
 * - UNAVAILABLE (CONNECTION_FAILURE) → retry (Scenario 4 requirement)
 * - Protection events (CIRCUIT_OPEN, BULKHEAD_REJECTED) → NO retry (safety constraint)
 * - TIMEOUT, CLIENT_ERROR, SERVER_ERROR, UNKNOWN → NO retry
 */
class RetryDecisionPolicyTest {

    private RetryDecisionPolicy policy;

    @BeforeEach
    void setup() {
        GrpcErrorClassifier classifier = new GrpcErrorClassifier();
        policy = new RetryDecisionPolicy(classifier);
    }

    @Test
    void testSuccess_NoRetry() {
        boolean shouldRetry = policy.shouldRetry(null, null);
        assertFalse(shouldRetry, "Success case should not retry");
    }

    @Test
    void testUnavailable_Retryable_Scenario4() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.UNAVAILABLE);
        boolean shouldRetry = policy.shouldRetry(ex, null);

        assertTrue(shouldRetry, "UNAVAILABLE (CONNECTION_FAILURE) should be retryable for Scenario 4 (selfheal)");
    }

    @Test
    void testDeadlineExceeded_NotRetryable_Scenario3() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
        boolean shouldRetry = policy.shouldRetry(ex, null);

        assertFalse(shouldRetry, "DEADLINE_EXCEEDED (TIMEOUT) should NOT be retryable (prevents load amplification)");
    }

    @Test
    void testResourceExhausted_Retryable_Scenario2() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
        boolean shouldRetry = policy.shouldRetry(ex, null);

        assertTrue(shouldRetry, "RESOURCE_EXHAUSTED (BACKEND_ERROR) MUST be retryable for Scenario 2 teaching requirement");
    }

    @Test
    void testInvalidArgument_NotRetryable() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.INVALID_ARGUMENT);
        boolean shouldRetry = policy.shouldRetry(ex, null);

        assertFalse(shouldRetry, "INVALID_ARGUMENT (CLIENT_ERROR) should NOT be retryable");
    }

    @Test
    void testInternal_NotRetryable() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.INTERNAL);
        boolean shouldRetry = policy.shouldRetry(ex, null);

        assertFalse(shouldRetry, "INTERNAL (SERVER_ERROR) should NOT be retryable");
    }

    @Test
    void testUnknownStatus_NotRetryable() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.UNKNOWN);
        boolean shouldRetry = policy.shouldRetry(ex, null);

        assertFalse(shouldRetry, "UNKNOWN status should default to non-retryable (conservative)");
    }

    @Test
    void testCircuitOpen_NotRetryable_CriticalSafetyConstraint() {
        boolean shouldRetry = policy.shouldRetry(null, "CIRCUIT_OPEN");

        assertFalse(shouldRetry, "CIRCUIT_OPEN MUST NOT be retryable (safety constraint - defeats circuit breaker)");
    }

    @Test
    void testBulkheadRejected_NotRetryable_CriticalSafetyConstraint() {
        boolean shouldRetry = policy.shouldRetry(null, "BULKHEAD_REJECTED");

        assertFalse(shouldRetry, "BULKHEAD_REJECTED MUST NOT be retryable (safety constraint - defeats bulkhead)");
    }

    @Test
    void testNonGrpcException_NotRetryable() {
        IllegalStateException ex = new IllegalStateException("test");
        boolean shouldRetry = policy.shouldRetry(ex, null);

        assertFalse(shouldRetry, "Non-gRPC exceptions should map to UNKNOWN and be non-retryable");
    }

    @Test
    void testResourceExhausted_WithCircuitOpenHint_NotRetryable() {
        // Edge case: Even if exception is RESOURCE_EXHAUSTED, if contextHint is CIRCUIT_OPEN, don't retry
        StatusRuntimeException ex = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
        boolean shouldRetry = policy.shouldRetry(ex, "CIRCUIT_OPEN");

        assertFalse(shouldRetry, "Protection events override exception type (safety takes precedence)");
    }
}
