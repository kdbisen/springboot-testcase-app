package com.example.testcase.service;

import com.example.testcase.model.StoryDetails;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoryDetailsContentSanitizerTest {

    @Test
    void sanitize_dropsCredentialLeakFromTitle() {
        StoryDetails d = new StoryDetails();
        d.setTitle("Loaded cached credentials for MCP");
        d.setDescription("Real Jira body");
        StoryDetailsContentSanitizer.sanitize(d);
        assertEquals("N/A", d.getTitle());
        assertTrue(d.getDescription().contains("Real Jira"));
    }

    @Test
    void sanitize_filtersAcListNoise() {
        StoryDetails d = new StoryDetails();
        d.setTitle("PROJ-1 Login");
        d.getAcceptanceCriteria().add("User can sign in");
        d.getAcceptanceCriteria().add("MCP server details connection established");
        StoryDetailsContentSanitizer.sanitize(d);
        assertEquals(1, d.getAcceptanceCriteria().size());
        assertTrue(d.getAcceptanceCriteria().get(0).contains("sign in"));
    }
}
