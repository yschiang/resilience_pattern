package com.demo.appa.retry;

import com.demo.appa.observability.CallOutcome;
import com.demo.appa.observability.ErrorReason;
import com.demo.appa.observability.GrpcErrorClassifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Retry decision policy that uses GrpcErrorClassifier output to determine
 * if an error should be retried.
 *
 * Safety constraints:
 * - Protection events (CIRCUIT_OPEN, BULKHEAD_REJECTED) are NEVER retried
 * - UNKNOWN errors default to non-retryable
 * - Only errors classified as retryable=true are retried
 */
@Component
public class RetryDecisionPolicy {

    private final GrpcErrorClassifier classifier;

    @Autowired
    public RetryDecisionPolicy(GrpcErrorClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * Determine if an exception should be retried based on classifier output.
     *
     * @param throwable Exception from gRPC call
     * @param contextHint Optional context for protection events (e.g., "CIRCUIT_OPEN")
     * @return true if should retry, false otherwise
     */
    public boolean shouldRetry(@Nullable Throwable throwable, @Nullable String contextHint) {
        if (throwable == null && contextHint == null) {
            return false;  // Success, no retry
        }

        // Classify the error using existing classifier
        CallOutcome outcome = classifier.classify(throwable, contextHint);

        // CRITICAL: Protection events MUST NOT be retried (safety constraint)
        // Retrying would defeat the purpose of circuit breaker and bulkhead
        if (outcome.reason() == ErrorReason.CIRCUIT_OPEN ||
            outcome.reason() == ErrorReason.BULKHEAD_REJECTED) {
            return false;
        }

        // Use classifier's retryable flag for all other errors
        return outcome.retryable();
    }
}
