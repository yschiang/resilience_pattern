package com.demo.appa.observability;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrpcErrorClassifierTest {

    private final GrpcErrorClassifier classifier = new GrpcErrorClassifier();

    @Test
    void testSuccess() {
        CallOutcome outcome = classifier.classify(null, null);
        assertEquals(ErrorReason.SUCCESS, outcome.reason());
        assertFalse(outcome.retryable());
        assertEquals("OK", outcome.grpcStatus());
        assertTrue(outcome.isSuccess());
    }

    @Test
    void testUnavailable() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.UNAVAILABLE);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.CONNECTION_FAILURE, outcome.reason());
        assertTrue(outcome.retryable());
        assertEquals("UNAVAILABLE", outcome.grpcStatus());
    }

    @Test
    void testDeadlineExceeded() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.TIMEOUT, outcome.reason());
        assertFalse(outcome.retryable());
    }

    @Test
    void testResourceExhausted() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.RESOURCE_EXHAUSTED);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.BACKEND_ERROR, outcome.reason());
        assertTrue(outcome.retryable());
    }

    @Test
    void testInvalidArgument() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.INVALID_ARGUMENT);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.CLIENT_ERROR, outcome.reason());
        assertFalse(outcome.retryable());
    }

    @Test
    void testInternal() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.INTERNAL);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.SERVER_ERROR, outcome.reason());
        assertFalse(outcome.retryable());
    }

    @Test
    void testUnknownGrpcStatus() {
        StatusRuntimeException ex = new StatusRuntimeException(Status.UNKNOWN);
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.UNKNOWN, outcome.reason());
        assertFalse(outcome.retryable());
    }

    @Test
    void testCircuitOpen() {
        CallOutcome outcome = classifier.classify(null, "CIRCUIT_OPEN");
        assertEquals(ErrorReason.CIRCUIT_OPEN, outcome.reason());
        assertFalse(outcome.retryable());
        assertEquals("CIRCUIT_OPEN", outcome.grpcStatus());
    }

    @Test
    void testBulkheadRejected() {
        CallOutcome outcome = classifier.classify(null, "BULKHEAD_REJECTED");
        assertEquals(ErrorReason.BULKHEAD_REJECTED, outcome.reason());
        assertFalse(outcome.retryable());
    }

    @Test
    void testNonGrpcException() {
        IllegalStateException ex = new IllegalStateException("test");
        CallOutcome outcome = classifier.classify(ex, null);
        assertEquals(ErrorReason.UNKNOWN, outcome.reason());
        assertFalse(outcome.retryable());
        assertEquals("IllegalStateException", outcome.grpcStatus());
    }

    @Test
    void testResultLabel() {
        assertEquals("SUCCESS", classifier.classify(null, null).resultLabel());
        assertEquals("FAILURE", classifier.classify(
            new StatusRuntimeException(Status.UNAVAILABLE), null).resultLabel());
    }
}
