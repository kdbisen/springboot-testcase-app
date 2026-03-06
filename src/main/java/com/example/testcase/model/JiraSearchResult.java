package com.example.testcase.model;

public class JiraSearchResult {
    private String key;
    private String title;

    public JiraSearchResult(String key, String title) {
        this.key = key;
        this.title = title;
    }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}
