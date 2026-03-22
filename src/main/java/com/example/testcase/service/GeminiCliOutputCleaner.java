package com.example.testcase.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Strips Gemini CLI noise (MCP status, ANSI, log lines) from stdout so JSON parsing and UI text stay clean.
 */
public final class GeminiCliOutputCleaner {

    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001b\\[[0-9;]*[a-zA-Z]|\u001b\\][0-9;]*[a-zA-Z]?");

    /** Lines that look like tooling/MCP status, not user/model content. */
    private static final Pattern NOISE_LINE = Pattern.compile(
        "(?i)^(.*\\b(?:"
            + "mcp\\s*(?:server|plugin|client|session|tool|tools|started|starting|connected|ready|listening|details?)"
            + "|oauth\\s*(?:flow|token|complete|pending|credentials?)"
            + "|yolo\\s+mode"
            + "|gemini\\s+cli\\s+(?:plugin|config)"
            + "|server\\s+(?:listening|started)"
            + "|plugin\\s+(?:loaded|started)"
            + "|cached\\s+credentials?"
            + "|loaded\\s+cached"
            + "|credential(s)?\\s+(?:loaded|cached|from\\s+cache)"
            + ")\\b.*)$");

    /** Short lines that are clearly CLI/MCP bootstrap (avoid dropping real Jira paragraphs). */
    private static final Pattern NOISE_CLI_LEAK = Pattern.compile(
        "(?i).{0,400}(?:"
            + "loaded\\s+cached\\s+credentials"
            + "|cached\\s+credentials"
            + "|mcp\\s+server\\s+details"
            + "|mcp\\s+connection\\s+details"
            + "|connection\\s+details\\s+for\\s+mcp"
            + "|connecting\\s+to\\s+mcp"
            + "|oauth\\s+token\\s+refreshed"
            + "|refreshing\\s+oauth"
            + ").{0,400}");

    private static final Pattern LOG_LEVEL_LINE = Pattern.compile(
        "(?i)^\\s*\\[?(?:INFO|DEBUG|WARN|ERROR|TRACE)\\]?(?:\\s|$).*");

    private GeminiCliOutputCleaner() {}

    public static String stripAnsiEscapeSequences(String s) {
        if (s == null || s.isEmpty()) return s;
        return ANSI_ESCAPE.matcher(s).replaceAll("");
    }

    /**
     * Prepare raw CLI stdout for extracting the wrapper JSON object: remove ANSI and skip lines before the first
     * line that looks like the wrapper object (starts with {@code {).
     */
    public static String prepareForWrapperJsonParse(String raw) {
        if (raw == null) return null;
        String s = stripAnsiEscapeSequences(raw);
        return stripLeadingLinesUntilJsonStart(s).trim();
    }

    static String stripLeadingLinesUntilJsonStart(String s) {
        if (s == null || s.isEmpty()) return s;
        int len = s.length();
        int i = 0;
        while (i < len) {
            int nl = s.indexOf('\n', i);
            String line = (nl < 0 ? s.substring(i) : s.substring(i, nl));
            String tr = line.trim();
            // Only `{` marks the wrapper object. A line like `[INFO] ...` starts with `[` but is a log line, not JSON.
            if (tr.startsWith("{")) {
                return s.substring(i);
            }
            if (nl < 0) break;
            i = nl + 1;
        }
        return s;
    }

    /**
     * Remove MCP/tool/oauth status lines from model text (story JSON body or table) so Step 2 does not show logs.
     */
    public static String cleanModelResponse(String text) {
        if (text == null || text.isBlank()) return text != null ? text : "";
        String[] lines = text.split("\r?\n");
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (shouldDropResponseLine(line)) continue;
            kept.add(line);
        }
        return String.join("\n", kept).trim();
    }

    static boolean shouldDropResponseLine(String line) {
        if (line == null) return true;
        String tr = line.trim();
        if (tr.isEmpty()) return false;
        if (LOG_LEVEL_LINE.matcher(tr).matches()) return true;
        if (NOISE_LINE.matcher(tr).matches()) return true;
        if (tr.length() <= 500 && NOISE_CLI_LEAK.matcher(tr).matches()) return true;
        return false;
    }

    /** Public for story field / list item filtering. */
    public static boolean isNoiseLine(String line) {
        return shouldDropResponseLine(line);
    }
}
