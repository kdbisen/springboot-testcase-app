package com.example.testcase.model;

import java.util.ArrayList;
import java.util.List;

public class StoryDetails {
    private String title = "N/A";
    private String description = "N/A";
    private List<String> acceptanceCriteria = new ArrayList<>();

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getAcceptanceCriteria() { return acceptanceCriteria; }
    public void setAcceptanceCriteria(List<String> acceptanceCriteria) { this.acceptanceCriteria = acceptanceCriteria; }
}
