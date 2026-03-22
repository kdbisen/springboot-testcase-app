package com.example.testcase.service;

import com.example.testcase.model.JiraSearchResult;
import com.example.testcase.model.StoryDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JiraStoryService {

    private static final Logger log = LoggerFactory.getLogger(JiraStoryService.class);

    private final JiraRestClient jiraRestClient;
    private final GeminiCliService geminiCliService;

    public JiraStoryService(JiraRestClient jiraRestClient, GeminiCliService geminiCliService) {
        this.jiraRestClient = jiraRestClient;
        this.geminiCliService = geminiCliService;
    }

    public List<JiraSearchResult> searchIssues(String query, int maxResults) throws Exception {
        if (jiraRestClient.isAvailable()) {
            log.debug("Jira search via REST API");
            return jiraRestClient.searchIssues(query, maxResults);
        }
        log.debug("Jira search via Gemini CLI (configure jira.* for REST API)");
        return geminiCliService.searchJiraIssues(query, maxResults);
    }

    public StoryDetails fetchStoryDetails(String storyKey) throws Exception {
        if (jiraRestClient.isAvailable()) {
            log.info("Fetch story {} via Jira REST API", storyKey);
            return jiraRestClient.fetchIssue(storyKey);
        }
        log.info("Fetch story {} via Gemini CLI", storyKey);
        return geminiCliService.fetchStoryDetails(storyKey);
    }
}
