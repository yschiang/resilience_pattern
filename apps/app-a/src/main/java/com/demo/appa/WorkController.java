package com.demo.appa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class WorkController {
    private static final Logger logger = LoggerFactory.getLogger(WorkController.class);

    @Autowired
    private BClientPort bClient;

    @GetMapping("/work")
    public WorkResponse work() {
        String requestId = UUID.randomUUID().toString();
        logger.info("Handling /api/work request: {}", requestId);

        WorkResult result = bClient.callWork(requestId);

        return new WorkResponse(
                result.isOk(),
                result.getCode(),
                result.getLatencyMs()
        );
    }

    public static class WorkResponse {
        private final boolean ok;
        private final String code;
        private final long latencyMs;

        public WorkResponse(boolean ok, String code, long latencyMs) {
            this.ok = ok;
            this.code = code;
            this.latencyMs = latencyMs;
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
    }
}
