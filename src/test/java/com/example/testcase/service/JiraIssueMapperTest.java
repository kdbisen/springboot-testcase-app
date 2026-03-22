package com.example.testcase.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JiraIssueMapperTest {

    @Test
    void extractAcceptanceCriteria_underHeading() {
        String md = """
            ## Acceptance Criteria

            - First item
            - Second item

            ## Notes
            Other
            """;
        List<String> ac = JiraIssueMapper.extractAcceptanceCriteria("", md);
        assertEquals(2, ac.size());
        assertTrue(ac.get(0).contains("First"));
    }
}
