package com.demo.appa;

/**
 * Semantic error codes for downstream communication.
 * Maps gRPC status codes and resilience pattern failures to application-level codes.
 */
public enum ErrorCode {
    /** Request completed successfully */
    SUCCESS,

    /** Request exceeded configured deadline/timeout */
    DEADLINE_EXCEEDED,

    /** Downstream service unavailable (connection reset, DNS failure, CANCELLED) */
    UNAVAILABLE,

    /** Request rejected because inflight queue is full (bulkhead pattern) */
    QUEUE_FULL,

    /** Request rejected because circuit breaker is open */
    CIRCUIT_OPEN,

    /** Unknown or unexpected error */
    UNKNOWN;

    /**
     * Maps gRPC status code to semantic error code.
     * @param grpcStatusCode gRPC status code from StatusRuntimeException
     * @return corresponding ErrorCode
     */
    public static ErrorCode fromGrpcStatus(io.grpc.Status.Code grpcStatusCode) {
        switch (grpcStatusCode) {
            case OK:
                return SUCCESS;
            case DEADLINE_EXCEEDED:
                return DEADLINE_EXCEEDED;
            case UNAVAILABLE:
            case CANCELLED:
            case UNKNOWN:
                return UNAVAILABLE;
            case RESOURCE_EXHAUSTED:
                return QUEUE_FULL;
            default:
                return UNKNOWN;
        }
    }
}
