package com.demo.appa.retry;

import com.demo.appa.observability.CallOutcome;
import com.demo.appa.observability.ErrorReason;
import com.demo.appa.observability.GrpcErrorClassifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Retry Decision Policy: Classifier-based retry gating (Workstream B).
 *
 * LEARNING: Why separate policy from retry configuration?
 * - Resilience4j Retry uses a predicate (boolean function) to decide retry
 * - This policy bridges GrpcErrorClassifier output → retry decision
 * - Enforces CRITICAL safety constraints that prevent retry from defeating protection
 *
 * Safety constraints:
 * - Protection events (CIRCUIT_OPEN, BULKHEAD_REJECTED) are NEVER retried
 * - Timeouts (DEADLINE_EXCEEDED) are NOT retried (already waited too long)
 * - UNKNOWN errors default to non-retryable (conservative fail-safe)
 * - Only errors classified as retryable=true are retried
 *
 * Used by: AppARetry and AppAResilient (retry.retryOnException() predicate)
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
     * LEARNING: Decision flow:
     * 1. No error? → NO retry (success)
     * 2. Protection event (CIRCUIT_OPEN, BULKHEAD_REJECTED)? → NO retry (safety)
     * 3. Other errors? → Use classifier's retryable flag
     *
     * Examples:
     *   ✅ BACKEND_ERROR (RESOURCE_EXHAUSTED) → retryable=true → RETRY
     *   ✅ CONNECTION_FAILURE (UNAVAILABLE) → retryable=true → RETRY
     *   ❌ TIMEOUT (DEADLINE_EXCEEDED) → retryable=false → NO RETRY
     *   ❌ CIRCUIT_OPEN → retryable=false → NO RETRY (double-check here for safety)
     *
     * @param throwable Exception from gRPC call
     * @param contextHint Optional context for protection events (e.g., "CIRCUIT_OPEN")
     * @return true if should retry, false otherwise
     */
    public boolean shouldRetry(@Nullable Throwable throwable, @Nullable String contextHint) {
        if (throwable == null && contextHint == null) {
            return false;  // Success, no retry needed
        }

        // LEARNING: Classify the error to get semantic ErrorReason + retryable flag
        CallOutcome outcome = classifier.classify(throwable, contextHint);

        // LEARNING: CRITICAL safety constraint (defense in depth)
        // Protection events MUST NOT be retried - double-check here even though
        // classifier already marks them retryable=false. Why double-check?
        // If classifier has a bug, this prevents retry from defeating protection.
        if (outcome.reason() == ErrorReason.CIRCUIT_OPEN ||
            outcome.reason() == ErrorReason.BULKHEAD_REJECTED) {
            return false;
        }

        // LEARNING: Trust classifier's retryable flag for all other errors
        // - BACKEND_ERROR, CONNECTION_FAILURE → retryable=true → retry
        // - TIMEOUT, CLIENT_ERROR, SERVER_ERROR, UNKNOWN → retryable=false → fail immediately
        return outcome.retryable();
    }
}
