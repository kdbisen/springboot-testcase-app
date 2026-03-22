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
        promptTemplates.put("fetchStory", "Get the full Jira issue {storyKey} using your Jira tools. Read the Summary, Description, and any Acceptance Criteria (or Definition of Done) fields.\n\nThe issue may store content as plain text, markdown, bullet lists, numbered lists, tables, panels, or headings—extract the real meaning regardless of layout.\n\nOutput ONLY a single JSON object. No markdown fences, no explanation, no text before or after the JSON.\n\nRequired shape:\n{\n  \"title\": \"<issue summary/title, or N/A if missing>\",\n  \"description\": \"<full description as markdown when useful: preserve ## headings and markdown tables converted from Jira tables; use N/A if empty>\",\n  \"acceptanceCriteria\": [\"<one criterion per string>\", \"...\"]\n}\n\nRules:\n- acceptanceCriteria: one array element per distinct criterion. If the source is bullet points, use one string per bullet. If it is a table, use one string per data row (join columns with \" — \" if multiple columns). If there are no criteria, use [].\n- description: include tables as markdown pipe tables when the story used a table; otherwise plain text or markdown is fine.\n- Use the string N/A only for title or description when that field is truly absent.");
        promptTemplates.put("generateTestCases", "Based on this Jira story, generate comprehensive test cases.\n\nStory: {storyKey}\nTitle: {title}\n\nDescription:\n{description}\n\nAcceptance Criteria:\n{acceptanceCriteria}\n\n{extra}\n\nOutput ONLY a markdown table with these exact columns:\n| ID | Test Case Name | Priority | Severity | Test Type | Steps | Expected Result | Test Data |\n\n- ID format: TC-01, TC-02, etc.\n- Priority: High / Medium / Low (based on business impact)\n- Severity: Critical / High / Medium / Low (based on defect impact)\n- Test Type: Functional / Regression / Smoke / Negative / Boundary\n- Steps: numbered steps (use semicolons to separate steps within the cell, e.g. \"1. Open app; 2. Enter email; 3. Click Login\" - keep each table row on ONE line)\n- Test Data: sample inputs, values, or test data needed (e.g. valid user, invalid password)\n- Cover positive, negative, and edge cases\n- No other text before or after the table");
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
