package com.example.testcase.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JiraRestClientSearchJqlTest {

    @Test
    void buildSearchJql_issueKey() {
        assertEquals("key = \"PROJ-123\" ORDER BY updated DESC", JiraRestClient.buildSearchJql("PROJ-123"));
    }

    @Test
    void buildSearchJql_freeText() {
        String jql = JiraRestClient.buildSearchJql("login bug");
        assertTrue(jql.contains("text ~"));
        assertTrue(jql.contains("login bug"));
    }

    @Test
    void buildSearchJql_escapesQuotes() {
        String jql = JiraRestClient.buildSearchJql("say \"hello\"");
        assertTrue(jql.contains("\\\""));
    }
}
