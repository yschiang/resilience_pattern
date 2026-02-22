package com.demo.appa.observability;

/**
 * Classification result for a gRPC call outcome.
 */
public record CallOutcome(
    ErrorReason reason,
    boolean retryable,
    String grpcStatus
) {
    public boolean isSuccess() {
        return reason == ErrorReason.SUCCESS;
    }

    public String resultLabel() {
        return isSuccess() ? "SUCCESS" : "FAILURE";
    }
}
