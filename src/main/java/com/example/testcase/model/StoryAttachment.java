package com.example.testcase.model;

/**
 * Jira attachment metadata (filename + optional note from AI; no file content).
 */
public class StoryAttachment {
    private String filename = "";
    private String note;

    public StoryAttachment() {}

    public StoryAttachment(String filename, String note) {
        this.filename = filename != null ? filename : "";
        this.note = note;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename != null ? filename : "";
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
