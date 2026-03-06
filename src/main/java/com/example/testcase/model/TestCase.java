package com.example.testcase.model;

public class TestCase {
    private String id;
    private String title;
    private String priority;
    private String severity;
    private String testType;
    private String steps;
    private String expected;
    private String data;
    private String storyKey;
    private boolean rejected;

    public TestCase() {}
    public TestCase(String id, String title, String priority, String severity, String testType,
                    String steps, String expected, String data, String storyKey) {
        this.id = id;
        this.title = title;
        this.priority = priority;
        this.severity = severity;
        this.testType = testType;
        this.steps = steps;
        this.expected = expected;
        this.data = data;
        this.storyKey = storyKey;
        this.rejected = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getTestType() { return testType; }
    public void setTestType(String testType) { this.testType = testType; }
    public String getSteps() { return steps; }
    public void setSteps(String steps) { this.steps = steps; }
    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
    public String getStoryKey() { return storyKey; }
    public void setStoryKey(String storyKey) { this.storyKey = storyKey; }
    public boolean isRejected() { return rejected; }
    public void setRejected(boolean rejected) { this.rejected = rejected; }
}
