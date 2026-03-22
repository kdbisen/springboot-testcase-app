package com.example.testcase.model;

/**
 * Outcome of creating a Jira sub-task via Gemini CLI (MCP).
 */
public record SubtaskCreateResult(String issueKey, String errorMessage) {

    public boolean success() {
        return issueKey != null && !issueKey.isBlank();
    }

    public static SubtaskCreateResult ok(String issueKey) {
        return new SubtaskCreateResult(issueKey, null);
    }

    public static SubtaskCreateResult fail(String errorMessage) {
        return new SubtaskCreateResult(null, errorMessage != null ? errorMessage : "Unknown error");
    }
}
