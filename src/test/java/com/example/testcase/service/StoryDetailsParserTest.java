package com.example.testcase.service;

import com.example.testcase.model.StoryDetails;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StoryDetailsParserTest {

    private final StoryDetailsParser parser = new StoryDetailsParser();

    @Test
    void parse_jsonObject_extractsFields() {
        String raw = """
            Here is the story:
            {"title":"Login page","description":"User logs in.","acceptanceCriteria":["User can submit","Errors show"]}
            """;
        StoryDetails d = parser.parse(raw);
        assertEquals("Login page", d.getTitle());
        assertEquals("User logs in.", d.getDescription());
        assertEquals(List.of("User can submit", "Errors show"), d.getAcceptanceCriteria());
    }

    @Test
    void parse_jsonInMarkdownFence() {
        String raw = """
            ```json
            {"title":"T","description":"D","acceptanceCriteria":[]}
            ```
            """;
        StoryDetails d = parser.parse(raw);
        assertEquals("T", d.getTitle());
        assertEquals("D", d.getDescription());
        assertTrue(d.getAcceptanceCriteria().isEmpty());
    }

    @Test
    void parse_jsonBracesInStringValues() {
        String raw = "{\"title\":\"Use {braces}\",\"description\":\"OK\",\"acceptanceCriteria\":[]}";
        StoryDetails d = parser.parse(raw);
        assertEquals("Use {braces}", d.getTitle());
    }

    @Test
    void parse_markdownHeadings_descriptionAndAc() {
        String raw = """
            # Payment flow

            ## Description
            Process payment via API.

            | Step | Action |
            |------|--------|
            | 1 | Call API |

            ## Acceptance Criteria
            - Payment succeeds
            * Refund allowed
            1. Idempotent requests
            """;
        StoryDetails d = parser.parse(raw);
        assertEquals("Payment flow", d.getTitle());
        assertTrue(d.getDescription().contains("Process payment"));
        assertTrue(d.getDescription().contains("| Step |"));
        assertEquals(List.of("Payment succeeds", "Refund allowed", "Idempotent requests"), d.getAcceptanceCriteria());
    }

    @Test
    void parse_acAsMarkdownTable() {
        String raw = """
            # Story

            ## Acceptance Criteria
            | Given | Then |
            |-------|------|
            | User logged in | Show dashboard |
            | Guest | Show login |
            """;
        StoryDetails d = parser.parse(raw);
        assertEquals(2, d.getAcceptanceCriteria().size());
        assertTrue(d.getAcceptanceCriteria().get(0).contains("User logged in"));
        assertTrue(d.getAcceptanceCriteria().get(0).contains("Show dashboard"));
        assertTrue(d.getAcceptanceCriteria().get(1).contains("Guest"));
    }

    @Test
    void parse_legacyTitleDescriptionAc() {
        String raw = """
            TITLE: Old style
            DESCRIPTION: Line one
            Line two
            ACCEPTANCE_CRITERIA:
            - First
            - Second
            """;
        StoryDetails d = parser.parse(raw);
        assertEquals("Old style", d.getTitle());
        assertTrue(d.getDescription().contains("Line one"));
        assertTrue(d.getDescription().contains("Line two"));
        assertEquals(List.of("First", "Second"), d.getAcceptanceCriteria());
    }

    @Test
    void parse_legacy_flushesDescriptionWithoutAcSection() {
        String raw = """
            TITLE: Only desc
            DESCRIPTION: Body only
            no trailing AC
            """;
        StoryDetails d = parser.parse(raw);
        assertEquals("Only desc", d.getTitle());
        assertTrue(d.getDescription().contains("Body only"));
        assertTrue(d.getDescription().contains("no trailing AC"));
    }

    @Test
    void parse_empty_returnsNa() {
        StoryDetails d = parser.parse("   ");
        assertEquals("N/A", d.getTitle());
        assertEquals("N/A", d.getDescription());
    }

    @Test
    void extractJsonObject_nestedObject() {
        String raw = "prefix {\"title\":\"x\",\"meta\":{\"a\":1},\"description\":\"d\",\"acceptanceCriteria\":[]}";
        String json = StoryDetailsParser.extractJsonObject(raw);
        assertNotNull(json);
        assertTrue(json.contains("\"meta\""));
    }

    @Test
    void parse_jsonRichDigest_attachmentsAndKeyPoints() {
        String raw = "{\"title\":\"Feat\",\"storyType\":\"technical\",\"description\":\"N/A\",\"descriptionMarkdown\":\"# Body\",\"acceptanceCriteria\":[\"AC1\"],\"keyPointsForTesting\":[\"kp1\"],\"edgeCasesAndRisks\":[],\"examplesOrScenarios\":[\"ex1\"],\"attachments\":[{\"filename\":\"a.csv\",\"note\":\"likely data\"}]}";
        StoryDetails d = parser.parse(raw);
        assertEquals("Feat", d.getTitle());
        assertEquals("technical", d.getStoryType());
        assertEquals("# Body", d.getPrimaryDescription());
        assertEquals("kp1", d.getKeyPointsForTesting().get(0));
        assertEquals("ex1", d.getExamplesOrScenarios().get(0));
        assertEquals(1, d.getAttachments().size());
        assertEquals("a.csv", d.getAttachments().get(0).getFilename());
        assertTrue(d.getAttachmentsSummaryLine().contains("a.csv"));
    }

    @Test
    void parse_fixtureFromClasspath() throws Exception {
        try (var is = getClass().getResourceAsStream("/fixtures/story-details-heading-sample.txt")) {
            assertNotNull(is);
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            StoryDetails d = parser.parse(text);
            assertEquals("Order checkout", d.getTitle());
            assertFalse(d.getAcceptanceCriteria().isEmpty());
        }
    }
}
