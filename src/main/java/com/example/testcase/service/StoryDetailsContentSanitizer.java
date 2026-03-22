package com.example.testcase.service;

import com.example.testcase.model.StoryAttachment;
import com.example.testcase.model.StoryDetails;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes Gemini CLI / MCP bootstrap lines that leak into JSON story fields so the UI shows Jira content only.
 */
public final class StoryDetailsContentSanitizer {

    private StoryDetailsContentSanitizer() {}

    public static void sanitize(StoryDetails d) {
        if (d == null) return;
        d.setTitle(sanitizeTitle(d.getTitle()));
        d.setDescription(sanitizeBody(d.getDescription(), "N/A"));
        d.setDescriptionMarkdown(sanitizeBody(d.getDescriptionMarkdown(), ""));
        d.setStoryType(sanitizeBody(d.getStoryType(), ""));
        d.setAcceptanceCriteria(filterStringList(d.getAcceptanceCriteria()));
        d.setKeyPointsForTesting(filterStringList(d.getKeyPointsForTesting()));
        d.setEdgeCasesAndRisks(filterStringList(d.getEdgeCasesAndRisks()));
        d.setExamplesOrScenarios(filterStringList(d.getExamplesOrScenarios()));
        d.setAttachments(filterAttachments(d.getAttachments()));
    }

    private static String sanitizeTitle(String t) {
        String s = GeminiCliOutputCleaner.cleanModelResponse(t != null ? t : "").trim();
        if (s.isEmpty() || GeminiCliOutputCleaner.isNoiseLine(s)) return "N/A";
        return s;
    }

    private static String sanitizeBody(String t, String emptyDefault) {
        String cleaned = GeminiCliOutputCleaner.cleanModelResponse(t != null ? t : "").trim();
        if (cleaned.isEmpty()) return emptyDefault;
        return cleaned;
    }

    private static List<String> filterStringList(List<String> in) {
        if (in == null) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s == null) continue;
            String t = GeminiCliOutputCleaner.cleanModelResponse(s).trim();
            if (t.isEmpty() || GeminiCliOutputCleaner.isNoiseLine(t)) continue;
            out.add(t);
        }
        return out;
    }

    private static List<StoryAttachment> filterAttachments(List<StoryAttachment> in) {
        if (in == null) return new ArrayList<>();
        List<StoryAttachment> out = new ArrayList<>();
        for (StoryAttachment a : in) {
            if (a == null || a.getFilename() == null) continue;
            String fn = a.getFilename().trim();
            if (fn.isEmpty() || GeminiCliOutputCleaner.isNoiseLine(fn)) continue;
            StoryAttachment c = new StoryAttachment(fn, a.getNote());
            out.add(c);
        }
        return out;
    }
}
