package com.example.testcase.service;

import com.example.testcase.model.JiraSearchResult;
import com.example.testcase.model.StoryDetails;
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

    @Value("${gemini.cli.timeout:300}")
    private int timeout;

    @Value("${gemini.approval.mode:yolo}")
    private String approvalMode;

    @Value("${gemini.json.output:true}")
    private boolean jsonOutput;

    private String findGeminiCli() {
        log.debug("Locating Gemini CLI in PATH");
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) pathEnv = System.getenv("Path");
        if (pathEnv == null) pathEnv = "";
        String[] dirs = pathEnv.split(Pattern.quote(File.pathSeparator));
        String ext = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) continue;
            File exe = new File(dir, "gemini" + ext);
            if (exe.exists() && exe.canExecute()) {
                log.debug("Found Gemini CLI at {}", exe.getAbsolutePath());
                return exe.getAbsolutePath();
            }
        }
        log.error("Gemini CLI not found in PATH");
        throw new IllegalStateException("Gemini CLI not found in PATH. Install: https://github.com/google-gemini/gemini-cli");
    }

    private String runGemini(String prompt, boolean useJson) throws Exception {
        String geminiPath = findGeminiCli();
        log.info("Running Gemini CLI (useJson={}), prompt length={}", useJson, prompt.length());
        List<String> cmd = new ArrayList<>(Arrays.asList(geminiPath, "--prompt", prompt));
        if (approvalMode != null && !"none".equalsIgnoreCase(approvalMode)) {
            cmd.add("--approval-mode");
            cmd.add(approvalMode);
        }
        if (useJson) {
            cmd.add("--output-format");
            cmd.add("json");
        }

        String promptPreview = prompt.length() > 80 ? prompt.substring(0, 80).replace("\n", " ") + "..." : prompt.replace("\n", " ");
        log.info("Gemini CLI command: {} --prompt \"{}\" {} {}", geminiPath, promptPreview,
            approvalMode != null && !"none".equalsIgnoreCase(approvalMode) ? "--approval-mode " + approvalMode : "",
            useJson ? "--output-format json" : "");
        log.debug("Gemini CLI full prompt:\n{}", prompt);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().putAll(System.getenv());
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append("\n");
        }

        boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            log.error("Gemini CLI timed out after {}s", timeout);
            throw new RuntimeException("Gemini CLI timed out after " + timeout + "s");
        }
        if (p.exitValue() != 0) {
            log.error("Gemini CLI failed exit={}: {}", p.exitValue(), out);
            throw new RuntimeException("Gemini CLI failed (exit " + p.exitValue() + "): " + out);
        }
        String output = out.toString();
        log.info("Gemini CLI output:\n{}", output);
        return output;
    }

    private String[] runGeminiWithStats(String prompt) throws Exception {
        if (!jsonOutput) {
            return new String[]{runGemini(prompt, false), null};
        }
        String raw = runGemini(prompt, true);
        try {
            // Gemini CLI may output extra text (YOLO mode, OAuth, etc.) before the JSON - extract JSON part
            String jsonStr = extractJsonFromOutput(raw);
            if (jsonStr == null) {
                log.warn("No JSON found in output, using raw as response");
                return new String[]{raw, null};
            }
            JsonNode data = JSON.readTree(jsonStr);
            String response = data.has("response") ? data.get("response").asText("") : "";
            return new String[]{response, raw};
        } catch (Exception e) {
            log.warn("JSON parse failed, using raw output: {}", e.getMessage());
            return new String[]{raw, null};
        }
    }

    /** Extract JSON object from output that may have extra text before/after (e.g. "YOLO mode...", OAuth messages). */
    private String extractJsonFromOutput(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        int end = -1;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        return end > start ? raw.substring(start, end) : null;
    }

    public List<JiraSearchResult> searchJiraIssues(String query, int maxResults) throws Exception {
        log.info("Search Jira: query='{}', maxResults={}", query, maxResults);
        String prompt = String.format("""
            Search Jira for issues matching: "%s".
            Return up to %d results. Output ONLY in this format, one per line, no other text:
            KEY | Title
            KEY | Title
            ...
            Example: PROJ-123 | Implement login feature
            Use actual Jira issue keys and titles from the search results.""", query, maxResults);
        String[] out = runGeminiWithStats(prompt);
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

    public StoryDetails fetchStoryDetails(String storyKey) throws Exception {
        log.info("Fetching story details for {}", storyKey);
        String prompt = String.format("""
            Get Jira story %s. Output ONLY in this exact format, no other text before or after:

            TITLE: [story title]
            DESCRIPTION: [full description]
            ACCEPTANCE_CRITERIA:
            - [first criterion]
            - [second criterion]
            - [etc.]

            Use actual content from the Jira story. If a section is empty, write "N/A".""", storyKey);
        String[] out = runGeminiWithStats(prompt);
        StoryDetails details = parseStoryDetails(out[0]);
        log.info("Fetched story {}: title='{}'", storyKey, details.getTitle());
        return details;
    }

    private StoryDetails parseStoryDetails(String text) {
        StoryDetails d = new StoryDetails();
        List<String> descLines = new ArrayList<>();
        String section = null;
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.matches("(?i)^TITLE:\\s*.*")) {
                d.setTitle(s.replaceFirst("(?i)^TITLE:\\s*", "").trim());
                section = null;
            } else if (s.matches("(?i)^DESCRIPTION:\\s*.*")) {
                section = "desc";
                descLines.add(s.replaceFirst("(?i)^DESCRIPTION:\\s*", "").trim());
            } else if (s.matches("(?i)^ACCEPTANCE_CRITERIA.*")) {
                if (!descLines.isEmpty()) d.setDescription(String.join("\n", descLines));
                section = "ac";
                String part = s.replaceFirst("(?i)^ACCEPTANCE_CRITERIA:?\\s*", "").trim();
                if (part.startsWith("-")) d.getAcceptanceCriteria().add(part.substring(1).trim());
            } else if ("desc".equals(section)) {
                descLines.add(s);
            } else if ("ac".equals(section) && s.startsWith("-")) {
                d.getAcceptanceCriteria().add(s.substring(1).trim());
            }
        }
        if (!descLines.isEmpty() && "N/A".equals(d.getDescription())) {
            d.setDescription(String.join("\n", descLines));
        }
        return d;
    }

    public String generateTestCases(String storyKey, StoryDetails details,
                                    boolean includeNegative, boolean includeBoundary,
                                    String customInstructions) throws Exception {
        String title, desc, acText;
        if (details != null) {
            title = details.getTitle();
            desc = details.getDescription();
            acText = details.getAcceptanceCriteria().stream().map(a -> "- " + a).collect(Collectors.joining("\n"));
        } else {
            StoryDetails fetched = fetchStoryDetails(storyKey);
            title = fetched.getTitle();
            desc = fetched.getDescription();
            acText = fetched.getAcceptanceCriteria().stream().map(a -> "- " + a).collect(Collectors.joining("\n"));
        }
        List<String> extras = new ArrayList<>();
        if (includeNegative) extras.add("Include negative test cases.");
        if (includeBoundary) extras.add("Include boundary analysis.");
        String extra = String.join(" ", extras);
        if (customInstructions != null && !customInstructions.isBlank()) {
            extra = (extra + " " + customInstructions.trim()).trim();
        }
        String prompt = String.format("""
            Based on this Jira story, generate comprehensive test cases.

            Story: %s
            Title: %s

            Description:
            %s

            Acceptance Criteria:
            %s

            %s

            Output ONLY a markdown table with these exact columns:
            | ID | Test Case Name | Priority | Severity | Test Type | Steps | Expected Result | Test Data |

            - ID format: TC-01, TC-02, etc.
            - Priority: High / Medium / Low (based on business impact)
            - Severity: Critical / High / Medium / Low (based on defect impact)
            - Test Type: Functional / Regression / Smoke / Negative / Boundary
            - Steps: numbered steps to execute
            - Test Data: sample inputs, values, or test data needed (e.g. valid user, invalid password)
            - Cover positive, negative, and edge cases
            - No other text before or after the table""", storyKey, title, desc, acText, extra);
        String[] out = runGeminiWithStats(prompt);
        return extractTable(out[0]);
    }

    private String extractTable(String text) {
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
        return tableLines.isEmpty() ? text : String.join("\n", tableLines);
    }

    public String createJiraSubtaskForStory(String parentKey, List<TestCase> testCases) {
        if (testCases == null || testCases.isEmpty()) return null;
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
        String prompt = String.format("""
            Create a Jira sub-task under parent issue %s.

            Summary: %s

            Description (use markdown, contains all test cases):
            %s

            Use your Jira MCP tools to create the sub-task. After creating, output ONLY the new issue key (e.g. PROJ-457) on a single line, nothing else.""",
            parentKey, summary, desc);
        try {
            String output = runGemini(prompt, false);
            Matcher m = JIRA_KEY_PATTERN.matcher(output);
            return m.find() ? m.group(1).toUpperCase() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
