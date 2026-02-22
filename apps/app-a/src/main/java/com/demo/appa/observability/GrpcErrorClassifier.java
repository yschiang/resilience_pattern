package com.demo.appa.observability;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class GrpcErrorClassifier {

    public CallOutcome classify(@Nullable Throwable throwable, @Nullable String contextHint) {
        // Success path
        if (throwable == null && contextHint == null) {
            return new CallOutcome(ErrorReason.SUCCESS, false, "OK");
        }

        // Protection events
        if (contextHint != null) {
            return switch (contextHint) {
                case "CIRCUIT_OPEN" -> new CallOutcome(ErrorReason.CIRCUIT_OPEN, false, "CIRCUIT_OPEN");
                case "BULKHEAD_REJECTED" -> new CallOutcome(ErrorReason.BULKHEAD_REJECTED, false, "BULKHEAD_REJECTED");
                default -> new CallOutcome(ErrorReason.UNKNOWN, false, contextHint);
            };
        }

        // gRPC status-based failures
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
