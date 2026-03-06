package com.example.testcase.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Session state for the 3-step workflow (same as Streamlit session_state).
 */
public class AppSession {
    private String storyKey;
    private List<String> storyKeys = new ArrayList<>();
    private StoryDetails storyDetails;
    private Map<String, StoryDetails> storyDetailsBatch = new HashMap<>();
    private List<TestCase> testCases = new ArrayList<>();
    private List<TestCase> customCases = new ArrayList<>();
    private List<JiraSearchResult> jiraSearchResults = new ArrayList<>();
    private boolean jiraSearchDone;
    private int activeStep;
    private boolean batchMode;

    public void reset() {
        storyKey = null;
        storyKeys.clear();
        storyDetails = null;
        storyDetailsBatch.clear();
        testCases.clear();
        customCases.clear();
        jiraSearchResults.clear();
        jiraSearchDone = false;
        activeStep = 0;
    }

    public String getStoryKey() { return storyKey; }
    public void setStoryKey(String storyKey) { this.storyKey = storyKey; }
    public List<String> getStoryKeys() { return storyKeys; }
    public void setStoryKeys(List<String> storyKeys) { this.storyKeys = storyKeys; }
    public StoryDetails getStoryDetails() { return storyDetails; }
    public void setStoryDetails(StoryDetails storyDetails) { this.storyDetails = storyDetails; }
    public Map<String, StoryDetails> getStoryDetailsBatch() { return storyDetailsBatch; }
    public void setStoryDetailsBatch(Map<String, StoryDetails> storyDetailsBatch) { this.storyDetailsBatch = storyDetailsBatch; }
    public List<TestCase> getTestCases() { return testCases; }
    public void setTestCases(List<TestCase> testCases) { this.testCases = testCases; }
    public List<TestCase> getCustomCases() { return customCases; }
    public void setCustomCases(List<TestCase> customCases) { this.customCases = customCases; }
    public List<JiraSearchResult> getJiraSearchResults() { return jiraSearchResults; }
    public void setJiraSearchResults(List<JiraSearchResult> jiraSearchResults) { this.jiraSearchResults = jiraSearchResults; }
    public boolean isJiraSearchDone() { return jiraSearchDone; }
    public void setJiraSearchDone(boolean jiraSearchDone) { this.jiraSearchDone = jiraSearchDone; }
    public int getActiveStep() { return activeStep; }
    public void setActiveStep(int activeStep) { this.activeStep = activeStep; }
    public boolean isBatchMode() { return batchMode; }
    public void setBatchMode(boolean batchMode) { this.batchMode = batchMode; }

    public boolean hasStory() {
        return (storyKey != null && storyDetails != null) || (!storyKeys.isEmpty() && !storyDetailsBatch.isEmpty());
    }

    public boolean hasTestCases() {
        return !testCases.isEmpty() || !customCases.isEmpty();
    }

    public List<TestCase> getVisibleTestCases() {
        List<TestCase> out = new ArrayList<>();
        for (TestCase tc : testCases) if (!tc.isRejected()) out.add(tc);
        out.addAll(customCases);
        return out;
    }
}
