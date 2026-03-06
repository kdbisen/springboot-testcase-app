package com.example.testcase.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JiraKeyUtil {
    private static final Pattern JIRA_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b", Pattern.CASE_INSENSITIVE);

    public static String extractStoryKey(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = JIRA_KEY.matcher(text);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    public static List<String> extractStoryKeys(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher m = JIRA_KEY.matcher(text);
        while (m.find()) seen.add(m.group(1).toUpperCase());
        return new ArrayList<>(seen);
    }
}
