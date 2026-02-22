package com.demo.appa.observability;

/**
 * Fixed error taxonomy for gRPC client outcomes.
 * Used as the 'reason' label in grpc_client_requests_total metric.
 */
public enum ErrorReason {
    SUCCESS,                 // Call succeeded
    CONNECTION_FAILURE,      // UNAVAILABLE, connection reset
    TIMEOUT,                 // DEADLINE_EXCEEDED
    BACKEND_ERROR,           // RESOURCE_EXHAUSTED
    CLIENT_ERROR,            // INVALID_ARGUMENT, UNAUTHENTICATED, etc.
    SERVER_ERROR,            // INTERNAL, DATA_LOSS, UNIMPLEMENTED
    CIRCUIT_OPEN,            // Circuit breaker rejected
    BULKHEAD_REJECTED,       // Semaphore full
    UNKNOWN                  // Fallback
}
