package com.example.testcase.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeminiCliOutputCleanerTest {

    @Test
    void prepareForWrapperJsonParse_skipsLinesBeforeJson() {
        String raw = "MCP server started\n[INFO] connecting\n{\"response\":\"ok\"}\n";
        String p = GeminiCliOutputCleaner.prepareForWrapperJsonParse(raw);
        assertTrue(p.startsWith("{"));
        assertTrue(p.contains("\"response\""));
    }

    @Test
    void cleanModelResponse_dropsMcpStatusLines() {
        String in = "Line one\nMCP plugin started\nReal content\n";
        String out = GeminiCliOutputCleaner.cleanModelResponse(in);
        assertTrue(out.contains("Line one"));
        assertTrue(out.contains("Real content"));
        assertFalse(out.toLowerCase().contains("mcp plugin started"));
    }

    @Test
    void extractJsonFromOutput_withLeadingNoise() {
        String raw = "some log\nMCP started\n{\"response\":\"Text with {brace} inside\"}";
        String json = GeminiCliService.extractJsonFromOutput(raw);
        assertNotNull(json);
        assertTrue(json.contains("brace"));
    }
}
