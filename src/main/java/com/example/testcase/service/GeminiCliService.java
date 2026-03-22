package com.example.testcase.service;

import com.example.testcase.config.JiraPromptsConfig;
import com.example.testcase.model.JiraSearchResult;
import com.example.testcase.model.StoryDetails;
import com.example.testcase.model.SubtaskCreateResult;
import com.example.testcase.model.TestCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemini CLI Service — Same logic as Python gemini_cli_client.py
 * Runs gemini CLI as subprocess, parses output for Jira search, story fetch, test case generation.
 */
@Service
public class GeminiCliService {

    private static final Logger log = LoggerFactory.getLogger(GeminiCliService.class);
    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();

    private enum GeminiOperation {
        SEARCH, FETCH, GENERATE, SUBTASK
    }

    @Value("${gemini.cli.timeout:300}")
    private int defaultTimeout;

    @Value("${gemini.cli.timeout.search:0}")
    private int timeoutSearch;

    @Value("${gemini.cli.timeout.fetch:0}")
    private int timeoutFetch;

    @Value("${gemini.cli.timeout.generate:0}")
    private int timeoutGenerate;

    @Value("${gemini.cli.timeout.subtask:0}")
    private int timeoutSubtask;

    @Value("${gemini.cli.retry.max-attempts:2}")
    private int retryMaxAttempts;

    @Value("${gemini.cli.retry.delay-ms:1500}")
    private long retryDelayMs;

    @Value("${gemini.cli.log.max-info-chars:8000}")
    private int logMaxInfoChars;

    @Value("${gemini.json.output:true}")
    private boolean jsonOutput;

    /** Extra env vars for Gemini CLI subprocess. Format: KEY1=value1;KEY2=value2 (semicolon-separated). */
    @Value("${gemini.env.extra:}")
    private String envExtra;

    private final JiraPromptsConfig jiraPrompts;
    private final StoryDetailsParser storyDetailsParser;

    public GeminiCliService(JiraPromptsConfig jiraPrompts, StoryDetailsParser storyDetailsParser) {
        this.jiraPrompts = jiraPrompts;
        this.storyDetailsParser = storyDetailsParser;
    }

    /** Map technical errors to short UI messages for flash attributes. */
    public static String userFriendlyMessage(Throwable t) {
        if (t == null) return "Unexpected error.";
        String m = t.getMessage();
        if (m == null) m = t.toString();
        String lower = m.toLowerCase(Locale.ROOT);
        if (t instanceof GeminiCliExecutionException g) {
            if (g.exitCode == -1) {
                return "Gemini CLI timed out. Increase the timeout in application.properties (gemini.cli.timeout.*) or try again.";
            }
            if (isNonRetryableOutput(g.cliOutput)) {
                return "MCP or tool execution was denied or blocked. Check Gemini CLI MCP approval and Jira access.";
            }
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "Gemini CLI timed out. Increase gemini.cli.timeout (or per-operation timeouts) or try again.";
        }
        if (lower.contains("gemini cli not found") || lower.contains("not found in path")) {
            return "Gemini CLI is not installed or not on PATH. Install from https://github.com/google-gemini/gemini-cli";
        }
        if (lower.contains("denied") && (lower.contains("policy") || lower.contains("mcp"))) {
            return "MCP tool execution was denied. Check Gemini CLI configuration for MCP approval.";
        }
        if (lower.contains("approval")) {
            return "Action requires approval in Gemini CLI. Approve MCP or adjust settings.";
        }
        if (m.length() > 500) return m.substring(0, 497) + "...";
        return m;
    }

    private static boolean isNonRetryableOutput(String output) {
        if (output == null || output.isBlank()) return false;
        String o = output.toLowerCase(Locale.ROOT);
        if (o.contains("denied") && (o.contains("policy") || o.contains("mcp"))) return true;
        if (o.contains("tool execution denied")) return true;
        if (o.contains("unauthorized") || o.contains("authentication failed")) return true;
        return false;
    }

    /** Result: [executable, args...] or [single path]. When args exist, run as: executable + args. */
    private static final class GeminiInvocation {
        final String executable;
        final List<String> args;

        GeminiInvocation(String executable, List<String> args) {
            this.executable = executable;
            this.args = args != null ? new ArrayList<>(args) : List.of();
        }

        List<String> toCommand() {
            List<String> cmd = new ArrayList<>();
            cmd.add(executable);
            cmd.addAll(args);
            return cmd;
        }
    }

    /** Unchecked exception carrying CLI exit code and output for retry and user messaging. */
    public static final class GeminiCliExecutionException extends RuntimeException {
        private final int exitCode;
        private final String cliOutput;

        public GeminiCliExecutionException(int exitCode, String cliOutput, String message) {
            super(message);
            this.exitCode = exitCode;
            this.cliOutput = cliOutput != null ? cliOutput : "";
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getCliOutput() {
            return cliOutput;
        }
    }

    private GeminiInvocation findGeminiInvocation() {
        log.debug("Locating Gemini CLI");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) pathEnv = System.getenv("Path");
        if (pathEnv == null) pathEnv = "";
        String[] dirs = pathEnv.split(Pattern.quote(File.pathSeparator));

        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) continue;
            File exe = new File(dir, isWindows ? "gemini.exe" : "gemini");
            if (exe.exists() && exe.canExecute()) {
                log.debug("Found Gemini CLI (native) at {}", exe.getAbsolutePath());
                return new GeminiInvocation(exe.getAbsolutePath(), null);
            }
        }
        if (!isWindows) {
            for (String dir : dirs) {
                if (dir == null || dir.isBlank()) continue;
                File exe = new File(dir, "gemini");
                if (exe.exists() && exe.canRead()) {
                    log.debug("Found Gemini CLI at {}", exe.getAbsolutePath());
                    return new GeminiInvocation(exe.getAbsolutePath(), null);
                }
            }
        }

        if (isWindows) {
            String appdata = System.getenv("APPDATA");
            String localappdata = System.getenv("LOCALAPPDATA");
            for (String base : new String[]{appdata, localappdata}) {
                if (base == null || base.isBlank()) continue;
                File script = new File(base, "npm" + File.separator + "node_modules" + File.separator + "@google" + File.separator + "gemini-cli" + File.separator + "dist" + File.separator + "index.js");
                if (script.isFile()) {
                    String node = findNodeExecutable();
                    if (node != null) {
                        log.debug("Found Gemini CLI (node) at {} via node {}", script.getAbsolutePath(), node);
                        return new GeminiInvocation(node, List.of(script.getAbsolutePath()));
                    }
                }
            }
        }

        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) continue;
            File exe = new File(dir, "gemini.cmd");
            if (exe.isFile()) {
                log.debug("Found Gemini CLI (.cmd) at {} (fallback)", exe.getAbsolutePath());
                return new GeminiInvocation(exe.getAbsolutePath(), null);
            }
        }
        if (isWindows) {
            String appdata = System.getenv("APPDATA");
            String localappdata = System.getenv("LOCALAPPDATA");
            for (String base : new String[]{appdata, localappdata}) {
                if (base == null || base.isBlank()) continue;
                File f = new File(base, "npm" + File.separator + "gemini.cmd");
                if (f.isFile()) {
                    log.debug("Found Gemini CLI (.cmd) at {} (fallback)", f.getAbsolutePath());
                    return new GeminiInvocation(f.getAbsolutePath(), null);
                }
            }
        }

        log.error("Gemini CLI not found in PATH");
        throw new IllegalStateException("Gemini CLI not found in PATH. Install: https://github.com/google-gemini/gemini-cli. " +
            "On Windows (npm): npm install -g @google/gemini-cli");
    }

    private List<String> buildGeminiCommand(GeminiInvocation inv, String prompt, boolean useJson) {
        List<String> cmd = new ArrayList<>(inv.toCommand());
        cmd.add("--prompt");
        cmd.add(prompt);
        if (useJson) {
            cmd.add("--output-format");
            cmd.add("json");
        }
        return cmd;
    }

    private String findNodeExecutable() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) pathEnv = System.getenv("Path");
        if (pathEnv == null) pathEnv = "";
        for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
            if (dir == null || dir.isBlank()) continue;
            File node = new File(dir, "node.exe");
            if (node.exists() && node.canExecute()) return node.getAbsolutePath();
        }
        return null;
    }

    private int timeoutSeconds(GeminiOperation op) {
        int override = switch (op) {
            case SEARCH -> timeoutSearch;
            case FETCH -> timeoutFetch;
            case GENERATE -> timeoutGenerate;
            case SUBTASK -> timeoutSubtask;
        };
        return override > 0 ? override : defaultTimeout;
    }

    private String runGeminiOnce(String prompt, boolean useJson, int timeoutSeconds) {
        GeminiInvocation inv = findGeminiInvocation();
        List<String> cmd = buildGeminiCommand(inv, prompt, useJson);
        String promptPreview = prompt.length() > 80 ? prompt.substring(0, 80).replace("\n", " ") + "..." : prompt.replace("\n", " ");
        log.info("Running Gemini CLI (useJson={}), prompt length={}, timeout={}s", useJson, prompt.length(), timeoutSeconds);
        log.info("Gemini CLI command: {} --prompt \"{}\" {}", inv.executable, promptPreview,
            useJson ? "--output-format json" : "");
        log.debug("Gemini CLI full prompt:\n{}", prompt);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(getGeminiEnv());
        Process p;
        try {
            p = pb.start();
        } catch (Exception e) {
            throw new GeminiCliExecutionException(-2, "", "Failed to start Gemini CLI: " + e.getMessage());
        }

        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append("\n");
        } catch (Exception e) {
            p.destroyForcibly();
            throw new GeminiCliExecutionException(-2, out.toString(), "Error reading Gemini CLI output: " + e.getMessage());
        }

        boolean finished;
        try {
            finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new GeminiCliExecutionException(-1, out.toString(), "Interrupted waiting for Gemini CLI");
        }
        if (!finished) {
            p.destroyForcibly();
            log.error("Gemini CLI timed out after {}s", timeoutSeconds);
            throw new GeminiCliExecutionException(-1, out.toString(), "Gemini CLI timed out after " + timeoutSeconds + "s");
        }
        String output = out.toString();
        logCliOutputSummary(output);
        int ev = p.exitValue();
        if (ev != 0) {
            log.error("Gemini CLI failed exit={}", ev);
            throw new GeminiCliExecutionException(ev, output, "Gemini CLI failed (exit " + ev + "): " + truncateForMessage(output));
        }
        return output;
    }

    private void logCliOutputSummary(String output) {
        if (log.isDebugEnabled()) {
            log.debug("Gemini CLI full output:\n{}", output);
            return;
        }
        if (output == null) return;
        if (output.length() <= logMaxInfoChars) {
            log.info("Gemini CLI output:\n{}", output);
        } else {
            log.info("Gemini CLI output (truncated for INFO, {} chars total; full at DEBUG):\n{}...",
                output.length(), output.substring(0, logMaxInfoChars));
        }
    }

    private static String truncateForMessage(String output) {
        if (output == null) return "";
        String t = output.trim();
        return t.length() > 1200 ? t.substring(0, 1197) + "..." : t;
    }

    private String runGemini(String prompt, boolean useJson, GeminiOperation op) {
        int max = Math.max(1, retryMaxAttempts);
        GeminiCliExecutionException last = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                return runGeminiOnce(prompt, useJson, timeoutSeconds(op));
            } catch (GeminiCliExecutionException e) {
                last = e;
                if (attempt >= max || !shouldRetry(attempt, max, e)) {
                    throw e;
                }
                log.warn("Gemini CLI attempt {}/{} for {} failed: {}; retrying in {}ms",
                    attempt, max, op, e.getMessage(), retryDelayMs);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new GeminiCliExecutionException(-2, e.getCliOutput(), "Interrupted during retry wait");
                }
            }
        }
        throw last != null ? last : new GeminiCliExecutionException(-2, "", "Gemini CLI failed with no attempts");
    }

    /** Package-visible for unit tests. */
    static boolean shouldRetryGeminiFailure(int attempt, int maxAttempts, GeminiCliExecutionException e) {
        return shouldRetry(attempt, maxAttempts, e);
    }

    private static boolean shouldRetry(int attempt, int maxAttempts, GeminiCliExecutionException e) {
        if (attempt >= maxAttempts) return false;
        String out = e.getCliOutput() != null ? e.getCliOutput() : "";
        if (isNonRetryableOutput(out)) return false;
        if (e.getExitCode() == -1) return true;
        String combined = (e.getMessage() != null ? e.getMessage() : "") + "\n" + out;
        String lower = combined.toLowerCase(Locale.ROOT);
        if (lower.contains("rate") || lower.contains("429") || lower.contains("temporar")
            || lower.contains("503") || lower.contains("unavailable")) return true;
        if (lower.contains("econnreset") || lower.contains("connection reset") || lower.contains("broken pipe")) return true;
        if (e.getExitCode() > 0 && !isNonRetryableOutput(out)) return true;
        return false;
    }

    /** Build env for Gemini CLI subprocess, using existing .gemini config if present. */
    private Map<String, String> getGeminiEnv() {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (envExtra != null && !envExtra.isBlank()) {
            for (String pair : envExtra.split(";")) {
                pair = pair.trim();
                if (pair.contains("=")) {
                    int idx = pair.indexOf("=");
                    String key = pair.substring(0, idx).trim();
                    String value = pair.substring(idx + 1).trim();
                    if (!key.isEmpty()) {
                        env.put(key, value);
                        log.debug("Gemini env: {}={}", key, key.toLowerCase().contains("path") ? "..." : value);
                    }
                }
            }
        }
        String existing = env.get("GEMINI_CONFIG_DIR");
        if (existing != null && !existing.isBlank()) {
            File f = new File(existing);
            if (f.isDirectory()) {
                env.put("GEMINI_CONFIG_DIR", f.getAbsolutePath());
                return env;
            }
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            File homeGemini = new File(home, ".gemini");
            if (homeGemini.isDirectory()) {
                env.put("GEMINI_CONFIG_DIR", homeGemini.getAbsolutePath());
                log.debug("Using Gemini config: {}", homeGemini.getAbsolutePath());
                return env;
            }
        }
        File cwdGemini = new File(System.getProperty("user.dir", ""), ".gemini");
        if (cwdGemini.isDirectory()) {
            env.put("GEMINI_CONFIG_DIR", cwdGemini.getAbsolutePath());
            log.debug("Using Gemini config: {}", cwdGemini.getAbsolutePath());
        }
        return env;
    }

    /**
     * Check if Gemini CLI is available and MCP tools can run.
     * Returns: {ok, gemini_path, message}
     */
    public Map<String, Object> checkGeminiAvailability() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("gemini_path", null);
        result.put("message", "");
        try {
            GeminiInvocation inv = findGeminiInvocation();
            result.put("gemini_path", inv.executable);
            List<String> cmd = buildGeminiCommand(inv, "Reply with exactly: OK", false);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().putAll(getGeminiEnv());
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append("\n");
            }
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                result.put("message", "Gemini CLI timed out. Increase gemini.cli.timeout or check network.");
                return result;
            }
            if (p.exitValue() != 0) {
                String err = out.toString().trim();
                if (err.toLowerCase().contains("denied") || err.toLowerCase().contains("policy") || err.toLowerCase().contains("approval")) {
                    result.put("message", "MCP tool execution denied. Check Gemini CLI config for MCP approval settings.");
                } else {
                    result.put("message", "Gemini CLI failed (exit " + p.exitValue() + "): " + (err.length() > 200 ? err.substring(0, 200) : err));
                }
                return result;
            }
            result.put("ok", true);
            result.put("message", "Gemini CLI ready (MCP tools available)");
            return result;
        } catch (Exception e) {
            result.put("message", e.getMessage());
            return result;
        }
    }

    private String[] runGeminiWithStats(String prompt, GeminiOperation op) {
        if (!jsonOutput) {
            String plain = runGemini(prompt, false, op);
            plain = GeminiCliOutputCleaner.stripAnsiEscapeSequences(plain);
            plain = GeminiCliOutputCleaner.cleanModelResponse(plain);
            return new String[]{plain, null};
        }
        String raw = runGemini(prompt, true, op);
        try {
            String jsonStr = extractJsonFromOutput(raw);
            if (jsonStr == null) {
                log.warn("No JSON wrapper in Gemini output after cleaning (len={}); using noise-stripped text", raw.length());
                String fallback = fallbackWhenNoWrapperJson(raw);
                return new String[]{fallback, raw};
            }
            JsonNode data = JSON.readTree(jsonStr);
            String response = data.has("response") ? data.get("response").asText("") : "";
            response = GeminiCliOutputCleaner.cleanModelResponse(response);
            return new String[]{response, raw};
        } catch (Exception e) {
            log.warn("JSON parse failed ({}), using noise-stripped text", e.getMessage());
            return new String[]{fallbackWhenNoWrapperJson(raw), raw};
        }
    }

    /** When --output-format json wrapper is missing, strip ANSI/MCP lines and return rest for table/story parsing. */
    private static String fallbackWhenNoWrapperJson(String raw) {
        String s = GeminiCliOutputCleaner.stripAnsiEscapeSequences(raw);
        s = GeminiCliOutputCleaner.prepareForWrapperJsonParse(s);
        return GeminiCliOutputCleaner.cleanModelResponse(s);
    }

    /**
     * Extract wrapper JSON from CLI stdout: skip leading log lines, optional anchor at "response",
     * brace-aware (strings) via StoryDetailsParser.extractJsonObject.
     */
    static String extractJsonFromOutput(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String prepared = GeminiCliOutputCleaner.prepareForWrapperJsonParse(raw);
        String json = StoryDetailsParser.extractJsonObject(prepared);
        if (json != null) return json;
        json = StoryDetailsParser.extractJsonObject(GeminiCliOutputCleaner.stripAnsiEscapeSequences(raw));
        if (json != null) return json;
        json = extractJsonAnchoredAtResponse(prepared);
        if (json != null) return json;
        return extractJsonAnchoredAtResponse(GeminiCliOutputCleaner.stripAnsiEscapeSequences(raw));
    }

    /** Find {@code {... "response": ...}} when logs prepend garbage and the first {@code { is not the wrapper. */
    private static String extractJsonAnchoredAtResponse(String text) {
        if (text == null) return null;
        int key = text.indexOf("\"response\"");
        if (key < 0) return null;
        int brace = text.lastIndexOf('{', key);
        if (brace < 0) return null;
        return StoryDetailsParser.extractJsonObject(text.substring(brace));
    }

    public List<JiraSearchResult> searchJiraIssues(String query, int maxResults) {
        log.info("Search Jira: query='{}', maxResults={}", query, maxResults);
        String prompt = jiraPrompts.fill("search", Map.of("query", query, "maxResults", String.valueOf(maxResults)));
        if (prompt.isEmpty()) prompt = String.format("Search Jira for issues matching: \"%s\". Return up to %d results. Output ONLY in this format, one per line: KEY | Title", query, maxResults);
        String[] out = runGeminiWithStats(prompt, GeminiOperation.SEARCH);
        List<JiraSearchResult> results = parseSearchResults(out[0]);
        log.info("Search returned {} results", results.size());
        return results;
    }

    private List<JiraSearchResult> parseSearchResults(String text) {
        List<JiraSearchResult> results = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.contains("|")) {
                String[] parts = line.split("\\|", 2);
                String key = parts[0].trim();
                String title = parts.length > 1 ? parts[1].trim() : "";
                Matcher m = JIRA_KEY_PATTERN.matcher(key);
                if (m.matches()) results.add(new JiraSearchResult(key.toUpperCase(), title));
            }
        }
        return results;
    }

    public StoryDetails fetchStoryDetails(String storyKey) {
        log.info("Fetching story details for {}", storyKey);
        String prompt = jiraPrompts.fill("fetchStory", Map.of("storyKey", storyKey));
        if (prompt.isEmpty()) {
            prompt = "Get Jira issue " + storyKey + " and output ONLY one JSON object with keys: title, storyType, description, descriptionMarkdown, acceptanceCriteria, keyPointsForTesting, edgeCasesAndRisks, examplesOrScenarios, attachments (filename+optional note; no file content).";
        }
        String[] out = runGeminiWithStats(prompt, GeminiOperation.FETCH);
        StoryDetails details = storyDetailsParser.parse(out[0]);
        log.info("Fetched story {}: title='{}'", storyKey, details.getTitle());
        return details;
    }

    public String generateTestCases(String storyKey, StoryDetails details,
                                    boolean includeNegative, boolean includeBoundary,
                                    String customInstructions) {
        StoryDetails d;
        if (details != null) {
            d = details;
        } else {
            d = fetchStoryDetails(storyKey);
        }
        String title = d.getTitle();
        String desc = d.getPrimaryDescription();
        String acText = d.getAcceptanceCriteria().stream().map(a -> "- " + a).collect(Collectors.joining("\n"));
        String storyType = d.getStoryType() != null && !d.getStoryType().isBlank() ? d.getStoryType() : "N/A";

        List<String> extras = new ArrayList<>();
        if (includeNegative) extras.add("Include negative test cases.");
        if (includeBoundary) extras.add("Include boundary analysis.");
        String extra = String.join(" ", extras);
        if (customInstructions != null && !customInstructions.isBlank()) {
            extra = (extra + " " + customInstructions.trim()).trim();
        }

        Map<String, String> fill = new LinkedHashMap<>();
        fill.put("storyKey", storyKey);
        fill.put("storyType", storyType);
        fill.put("title", title);
        fill.put("description", desc != null ? desc : "");
        fill.put("acceptanceCriteria", acText);
        fill.put("keyPointsForTesting", joinBulletLines(d.getKeyPointsForTesting()));
        fill.put("edgeCasesAndRisks", joinBulletLines(d.getEdgeCasesAndRisks()));
        fill.put("examplesOrScenarios", joinBulletLines(d.getExamplesOrScenarios()));
        fill.put("attachmentsNote", d.formatAttachmentsForPrompt());
        fill.put("extra", extra);

        String prompt = jiraPrompts.fill("generateTestCases", fill);
        if (prompt.isEmpty()) {
            prompt = String.format(
                "Based on Jira story %s (Title: %s), generate test cases. Output a markdown table with columns: ID | Test Case Name | Priority | Severity | Test Type | Steps | Expected Result | Test Data",
                storyKey, title);
        }
        String[] out = runGeminiWithStats(prompt, GeminiOperation.GENERATE);
        return extractTable(out[0]);
    }

    private static String joinBulletLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "(none)";
        return lines.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(s -> "- " + s.trim())
            .collect(Collectors.joining("\n"));
    }

    private String extractTable(String text) {
        String cleaned = GeminiCliOutputCleaner.cleanModelResponse(text);
        String table = extractTableLines(cleaned);
        if (!table.isEmpty()) return table;
        table = extractTableLines(text);
        if (!table.isEmpty()) return table;
        return text;
    }

    private static String extractTableLines(String text) {
        List<String> tableLines = new ArrayList<>();
        boolean inTable = false;
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.contains("|")) {
                inTable = true;
                tableLines.add(line);
            } else if (inTable && s.isEmpty()) {
                if (!tableLines.isEmpty() && tableLines.get(tableLines.size() - 1).contains("|")) break;
            } else if (inTable && !s.contains("|")) break;
        }
        return tableLines.isEmpty() ? "" : String.join("\n", tableLines);
    }

    public SubtaskCreateResult createJiraSubtaskForStory(String parentKey, List<TestCase> testCases) {
        if (testCases == null || testCases.isEmpty()) return SubtaskCreateResult.fail("No test cases.");
        StringBuilder desc = new StringBuilder();
        for (int i = 0; i < testCases.size(); i++) {
            TestCase tc = testCases.get(i);
            String tid = tc.getId() != null ? tc.getId() : "TC-" + String.format("%02d", i + 1);
            String t = tc.getTitle() != null ? tc.getTitle() : "Test Case";
            desc.append("### ").append(tid).append(": ").append(t).append("\n");
            if (tc.getSteps() != null && !tc.getSteps().isEmpty())
                desc.append("**Steps:**\n").append(tc.getSteps()).append("\n");
            if (tc.getExpected() != null && !tc.getExpected().isEmpty())
                desc.append("**Expected:** ").append(tc.getExpected()).append("\n");
            if (tc.getData() != null && !tc.getData().isEmpty())
                desc.append("**Test Data:** ").append(tc.getData()).append("\n");
            desc.append("*Priority: ").append(tc.getPriority() != null ? tc.getPriority() : "")
               .append(" | Severity: ").append(tc.getSeverity() != null ? tc.getSeverity() : "")
               .append(" | Type: ").append(tc.getTestType() != null ? tc.getTestType() : "").append("*\n");
            if (i < testCases.size() - 1) desc.append("\n---\n\n");
        }
        String summary = ("Test Cases (" + testCases.size() + " cases)").substring(0, Math.min(255, ("Test Cases (" + testCases.size() + " cases)").length()));
        String prompt = jiraPrompts.fill("createSubtask", Map.of("parentKey", parentKey, "summary", summary, "description", desc.toString()));
        if (prompt.isEmpty()) prompt = String.format("Create a Jira sub-task under parent issue %s. Summary: %s. Description: %s. Output ONLY the new issue key.", parentKey, summary, desc);
        try {
            String output = runGemini(prompt, false, GeminiOperation.SUBTASK);
            output = GeminiCliOutputCleaner.stripAnsiEscapeSequences(output);
            output = GeminiCliOutputCleaner.cleanModelResponse(output);
            Matcher m = JIRA_KEY_PATTERN.matcher(output);
            if (m.find()) return SubtaskCreateResult.ok(m.group(1).toUpperCase());
            return SubtaskCreateResult.fail("No Jira issue key found in Gemini response. Check MCP and Jira permissions.");
        } catch (GeminiCliExecutionException e) {
            return SubtaskCreateResult.fail(userFriendlyMessage(e));
        } catch (Exception e) {
            return SubtaskCreateResult.fail(userFriendlyMessage(e));
        }
    }
}
