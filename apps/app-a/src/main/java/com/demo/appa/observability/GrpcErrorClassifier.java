package com.demo.appa.observability;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Error Classifier: Maps exceptions to semantic ErrorReason + retryability.
 *
 * LEARNING: Why semantic classification?
 * - gRPC status codes are too granular (UNAVAILABLE could be network OR backend overload)
 * - We need semantic grouping for observability AND retry decisions
 * - Separate internal taxonomy (ErrorReason) from external API (ErrorCode)
 *
 * Output: CallOutcome{reason, retryable, grpcStatus}
 * - reason: Semantic error category (9 enum values)
 * - retryable: true/false (drives retry decisions via RetryDecisionPolicy)
 * - grpcStatus: Original gRPC status for debugging
 *
 * Used by: MetricsService (reason label), RetryDecisionPolicy (retry predicate)
 */
@Component
public class GrpcErrorClassifier {

    /**
     * Classify exception or context hint into semantic error reason + retryability.
     *
     * LEARNING: Two input modes:
     * 1. Protection events (contextHint): CB_OPEN, BULKHEAD_REJECTED → retryable=false
     * 2. gRPC exceptions (throwable): StatusRuntimeException → mapped to ErrorReason
     */
    public CallOutcome classify(@Nullable Throwable throwable, @Nullable String contextHint) {
        // LEARNING: Success path (no error)
        if (throwable == null && contextHint == null) {
            return new CallOutcome(ErrorReason.SUCCESS, false, "OK");
        }

        // LEARNING: Protection events (contextHint provided by caller)
        // CRITICAL: Protection events are NEVER retryable (retryable=false)
        // Why? Retrying would defeat the protection:
        //   - CIRCUIT_OPEN: Retry would bypass CB load shedding
        //   - BULKHEAD_REJECTED: Retry when already overloaded makes it worse
        if (contextHint != null) {
            return switch (contextHint) {
                case "CIRCUIT_OPEN" -> new CallOutcome(ErrorReason.CIRCUIT_OPEN, false, "CIRCUIT_OPEN");
                case "BULKHEAD_REJECTED" -> new CallOutcome(ErrorReason.BULKHEAD_REJECTED, false, "BULKHEAD_REJECTED");
                default -> new CallOutcome(ErrorReason.UNKNOWN, false, contextHint);
            };
        }

        // LEARNING: gRPC status → semantic ErrorReason + retryability decision
        // Retryable (true): Transient failures that might succeed on retry
        //   ✅ UNAVAILABLE (CONNECTION_FAILURE): Network glitch, reconnect may work
        //   ✅ RESOURCE_EXHAUSTED (BACKEND_ERROR): Backend overloaded, may recover
        // Not retryable (false): Won't succeed on retry
        //   ❌ DEADLINE_EXCEEDED (TIMEOUT): Already waited too long, retry amplifies load
        //   ❌ CLIENT_ERROR: Bug in request, won't change on retry
        //   ❌ SERVER_ERROR: Backend bug, retry won't help
        if (throwable instanceof StatusRuntimeException sre) {
            Status.Code code = sre.getStatus().getCode();
            return switch (code) {
                case UNAVAILABLE -> new CallOutcome(ErrorReason.CONNECTION_FAILURE, true, code.name());
                case DEADLINE_EXCEEDED -> new CallOutcome(ErrorReason.TIMEOUT, false, code.name());
                case RESOURCE_EXHAUSTED -> new CallOutcome(ErrorReason.BACKEND_ERROR, true, code.name());
                case INVALID_ARGUMENT, UNAUTHENTICATED, PERMISSION_DENIED, NOT_FOUND ->
                    new CallOutcome(ErrorReason.CLIENT_ERROR, false, code.name());
                case INTERNAL, DATA_LOSS, UNIMPLEMENTED ->
                    new CallOutcome(ErrorReason.SERVER_ERROR, false, code.name());
                default -> new CallOutcome(ErrorReason.UNKNOWN, false, code.name());
            };
        }

        // Non-gRPC exceptions
        return new CallOutcome(ErrorReason.UNKNOWN, false, throwable.getClass().getSimpleName());
    }
}
