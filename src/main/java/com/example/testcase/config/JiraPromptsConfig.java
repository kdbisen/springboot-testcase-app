package com.example.testcase.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads Jira prompt templates from an external JSON file.
 * Default: classpath:jira-prompts.json
 * Override via: gemini.jira.prompts.path (e.g. file:./config/jira-prompts.json)
 */
@Component
public class JiraPromptsConfig {

    private static final Logger log = LoggerFactory.getLogger(JiraPromptsConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ResourceLoader resourceLoader;

    @Value("${gemini.jira.prompts.path:classpath:jira-prompts.json}")
    private String promptsPath;

    private final Map<String, String> promptTemplates = new HashMap<>();

    public JiraPromptsConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void load() {
        Resource resource = resourceLoader.getResource(promptsPath);
        if (!resourceExists(resource)) {
            log.warn("Jira prompts file not found: {}. Using built-in defaults.", promptsPath);
            loadDefaults();
            return;
        }
        try (InputStream is = resource.getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = JSON.readTree(content);
            if (root != null && root.isObject()) {
                root.fields().forEachRemaining(entry -> {
                    JsonNode node = entry.getValue();
                    if (node != null && node.has("promptTemplate")) {
                        promptTemplates.put(entry.getKey(), node.get("promptTemplate").asText());
                    }
                });
                log.info("Loaded Jira prompts from {} ({} templates)", promptsPath, promptTemplates.size());
            }
        } catch (Exception e) {
            log.error("Failed to load Jira prompts from {}: {}", promptsPath, e.getMessage());
            loadDefaults();
        }
    }

    private boolean resourceExists(Resource resource) {
        try {
            return resource.exists();
        } catch (Exception e) {
            return false;
        }
    }

    private void loadDefaults() {
        promptTemplates.put("search", "Search Jira for issues matching: \"{query}\".\nReturn up to {maxResults} results. Output ONLY in this format, one per line, no other text:\nKEY | Title\nKEY | Title\n...\nExample: PROJ-123 | Implement login feature\nUse actual Jira issue keys and titles from the search results.");
        promptTemplates.put("fetchStory", """
            Get the full Jira issue {storyKey} using your Jira tools. The issue may be technical or non-technical, small or large; content may use headings, tables, bullet lists, paragraphs, code blocks, or examples—preserve meaning, do not flatten carelessly.

            Use Jira tools to list **attachment file names only** (do not download or read file contents). For each attachment include optional `note` inferred from filename or issue context only (e.g. "likely test data" for .csv).

            Output ONLY one JSON object. No markdown fences, no explanation, no text before or after the JSON.

            Required shape:
            {
              "title": "<summary/title or N/A>",
              "storyType": "<short free-text label you infer, e.g. technical feature, bug, spike, UX, or N/A>",
              "description": "<same as descriptionMarkdown if you only have one body, or shorter summary; use N/A if empty>",
              "descriptionMarkdown": "<full description as markdown: keep ## headings, tables as pipe tables, lists; N/A if empty>",
              "acceptanceCriteria": ["..."],
              "keyPointsForTesting": ["<QA focus bullets, AI-extracted>"],
              "edgeCasesAndRisks": ["<optional>"],
              "examplesOrScenarios": ["<optional examples from the issue>"],
              "attachments": [{"filename": "...", "note": "<optional, filename/context only>"}]
            }

            Rules:
            - acceptanceCriteria: one string per criterion; [] if none.
            - keyPointsForTesting: short bullets; [] if nothing to add beyond AC.
            - attachments: [] if no attachments; never claim you read file contents.
            - Use N/A for title/description/descriptionMarkdown only when truly absent.
            """);
        promptTemplates.put("generateTestCases", """
            Based on this Jira story digest, generate comprehensive test cases.

            Story: {storyKey}
            Story type: {storyType}
            Title: {title}

            Primary description (markdown):
            {description}

            Acceptance criteria:
            {acceptanceCriteria}

            Key points for testing:
            {keyPointsForTesting}

            Edge cases and risks:
            {edgeCasesAndRisks}

            Examples / scenarios:
            {examplesOrScenarios}

            Attachments (metadata only — testers must open files in Jira; contents not available here):
            {attachmentsNote}

            {extra}

            When attachments are listed, include at least one test case or step that reminds testers to validate against those files in Jira where relevant.

            Output ONLY a markdown table with these exact columns:
            | ID | Test Case Name | Priority | Severity | Test Type | Steps | Expected Result | Test Data |

            - ID format: TC-01, TC-02, etc.
            - Priority: High / Medium / Low (based on business impact)
            - Severity: Critical / High / Medium / Low (based on defect impact)
            - Test Type: Functional / Regression / Smoke / Negative / Boundary
            - Steps: numbered steps (use semicolons to separate steps within the cell, e.g. "1. Open app; 2. Enter email; 3. Click Login" - keep each table row on ONE line)
            - Test Data: sample inputs, values, or test data needed (e.g. valid user, invalid password)
            - Cover positive, negative, and edge cases
            - No other text before or after the table
            """);
        promptTemplates.put("createSubtask", "Create a Jira sub-task under parent issue {parentKey}.\n\nSummary: {summary}\n\nDescription (use markdown, contains all test cases):\n{description}\n\nUse your Jira MCP tools to create the sub-task. After creating, output ONLY the new issue key (e.g. PROJ-457) on a single line, nothing else.");
    }

    /** Get prompt template by key (search, fetchStory, generateTestCases, createSubtask). */
    public String getTemplate(String key) {
        return promptTemplates.getOrDefault(key, "");
    }

    /** Replace placeholders {name} in template with values from the map. */
    public String fill(String key, Map<String, String> values) {
        String template = getTemplate(key);
        if (template.isEmpty()) return "";
        for (Map.Entry<String, String> e : values.entrySet()) {
            template = template.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
        }
        return template;
    }
}
