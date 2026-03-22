package com.example.testcase.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Story content from Jira: prefer REST API when configured; otherwise from Gemini CLI. Extended fields are optional for cache and legacy JSON.
 */
public class StoryDetails {
    private String title = "N/A";
    /** Primary description (plain or markdown). */
    private String description = "N/A";
    /** Rich body when AI returns it separately; display prefers this when set. */
    private String descriptionMarkdown = "";
    private String storyType = "";
    private List<String> acceptanceCriteria = new ArrayList<>();
    private List<String> keyPointsForTesting = new ArrayList<>();
    private List<String> edgeCasesAndRisks = new ArrayList<>();
    private List<String> examplesOrScenarios = new ArrayList<>();
    private List<StoryAttachment> attachments = new ArrayList<>();

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDescriptionMarkdown() {
        return descriptionMarkdown;
    }

    public void setDescriptionMarkdown(String descriptionMarkdown) {
        this.descriptionMarkdown = descriptionMarkdown != null ? descriptionMarkdown : "";
    }

    public String getStoryType() {
        return storyType;
    }

    public void setStoryType(String storyType) {
        this.storyType = storyType != null ? storyType : "";
    }

    public List<String> getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public void setAcceptanceCriteria(List<String> acceptanceCriteria) {
        this.acceptanceCriteria = acceptanceCriteria != null ? acceptanceCriteria : new ArrayList<>();
    }

    public List<String> getKeyPointsForTesting() {
        return keyPointsForTesting;
    }

    public void setKeyPointsForTesting(List<String> keyPointsForTesting) {
        this.keyPointsForTesting = keyPointsForTesting != null ? keyPointsForTesting : new ArrayList<>();
    }

    public List<String> getEdgeCasesAndRisks() {
        return edgeCasesAndRisks;
    }

    public void setEdgeCasesAndRisks(List<String> edgeCasesAndRisks) {
        this.edgeCasesAndRisks = edgeCasesAndRisks != null ? edgeCasesAndRisks : new ArrayList<>();
    }

    public List<String> getExamplesOrScenarios() {
        return examplesOrScenarios;
    }

    public void setExamplesOrScenarios(List<String> examplesOrScenarios) {
        this.examplesOrScenarios = examplesOrScenarios != null ? examplesOrScenarios : new ArrayList<>();
    }

    public List<StoryAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<StoryAttachment> attachments) {
        this.attachments = attachments != null ? attachments : new ArrayList<>();
    }

    /** Deep copy for merging manual acceptance criteria before generation. */
    public StoryDetails copy() {
        StoryDetails n = new StoryDetails();
        n.setTitle(title);
        n.setDescription(description);
        n.setDescriptionMarkdown(descriptionMarkdown);
        n.setStoryType(storyType);
        n.setAcceptanceCriteria(new ArrayList<>(acceptanceCriteria));
        n.setKeyPointsForTesting(new ArrayList<>(keyPointsForTesting));
        n.setEdgeCasesAndRisks(new ArrayList<>(edgeCasesAndRisks));
        n.setExamplesOrScenarios(new ArrayList<>(examplesOrScenarios));
        List<StoryAttachment> attCopy = new ArrayList<>();
        for (StoryAttachment a : attachments) {
            attCopy.add(new StoryAttachment(a.getFilename(), a.getNote()));
        }
        n.setAttachments(attCopy);
        return n;
    }

    /** Body for display and generation: prefer structured markdown field when present. */
    public String getPrimaryDescription() {
        if (descriptionMarkdown != null && !descriptionMarkdown.isBlank() && !"N/A".equalsIgnoreCase(descriptionMarkdown.trim())) {
            return descriptionMarkdown;
        }
        return description != null ? description : "N/A";
    }

    /** One line for UI: attachment count and names; no file content. */
    public String getAttachmentsSummaryLine() {
        if (attachments == null || attachments.isEmpty()) return "";
        String names = attachments.stream()
            .map(a -> a.getFilename() + (a.getNote() != null && !a.getNote().isBlank() ? " (" + a.getNote() + ")" : ""))
            .collect(Collectors.joining(", "));
        return "Attachments (" + attachments.size() + "): " + names + " — open these files in Jira when testing; their contents are not loaded in this tool.";
    }

    /** Text block for generateTestCases prompt. */
    public String formatAttachmentsForPrompt() {
        if (attachments == null || attachments.isEmpty()) {
            return "No attachments listed on this issue (or none found).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("This issue has ").append(attachments.size()).append(" attachment(s). Filenames (open in Jira to use; contents not available here):\n");
        for (StoryAttachment a : attachments) {
            sb.append("- ").append(a.getFilename());
            if (a.getNote() != null && !a.getNote().isBlank()) sb.append(" — ").append(a.getNote());
            sb.append("\n");
        }
        sb.append("Include test ideas that reference validating behavior described in those attachments where relevant, and note that testers must consult the files in Jira.");
        return sb.toString().trim();
    }
}
