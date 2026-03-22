package com.example.testcase.service;

import com.example.testcase.service.GeminiCliService.GeminiCliExecutionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeminiCliServiceStabilityTest {

    @Test
    void extractJsonFromOutput_respectsBracesInsideJsonStrings() {
        String raw = "prefix\n{\"response\":\"Use {literal} braces\",\"stats\":{\"t\":1}}";
        String json = GeminiCliService.extractJsonFromOutput(raw);
        assertNotNull(json);
        assertTrue(json.contains("{literal}"));
        assertTrue(json.endsWith("}"));
    }

    @Test
    void extractJsonFromOutput_returnsNullWhenNoObject() {
        assertNull(GeminiCliService.extractJsonFromOutput("no json here"));
    }

    @Test
    void shouldRetry_onTimeout() {
        GeminiCliExecutionException e = new GeminiCliExecutionException(-1, "partial", "Gemini CLI timed out after 30s");
        assertTrue(GeminiCliService.shouldRetryGeminiFailure(1, 3, e));
    }

    @Test
    void shouldNotRetry_whenMaxAttemptsReached() {
        GeminiCliExecutionException e = new GeminiCliExecutionException(1, "err", "exit");
        assertFalse(GeminiCliService.shouldRetryGeminiFailure(2, 2, e));
    }

    @Test
    void shouldNotRetry_onMcpDenied() {
        GeminiCliExecutionException e = new GeminiCliExecutionException(1,
            "MCP tool execution denied by policy. Approve in settings.", "failed");
        assertFalse(GeminiCliService.shouldRetryGeminiFailure(1, 3, e));
    }

    @Test
    void shouldRetry_onRateLimitInOutput() {
        GeminiCliExecutionException e = new GeminiCliExecutionException(1,
            "Error 429 rate limit exceeded", "failed");
        assertTrue(GeminiCliService.shouldRetryGeminiFailure(1, 3, e));
    }

    @Test
    void userFriendlyMessage_timeout() {
        GeminiCliExecutionException e = new GeminiCliExecutionException(-1, "", "Gemini CLI timed out after 60s");
        String msg = GeminiCliService.userFriendlyMessage(e);
        assertTrue(msg.toLowerCase().contains("timed out") || msg.toLowerCase().contains("timeout"));
    }
}
