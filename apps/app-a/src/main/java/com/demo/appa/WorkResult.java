package com.demo.appa;

public class WorkResult {
    private final boolean ok;
    private final String code;
    private final long latencyMs;
    private final ErrorCode errorCode;

    public WorkResult(boolean ok, String code, long latencyMs, ErrorCode errorCode) {
        this.ok = ok;
        this.code = code;
        this.latencyMs = latencyMs;
        this.errorCode = errorCode;
    }

    public boolean isOk() {
        return ok;
    }

    public String getCode() {
        return code;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
