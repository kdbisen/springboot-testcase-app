package com.example.testcase.service;

import com.example.testcase.model.TestCase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parse markdown table from Gemini output into TestCase list.
 * Same logic as Python parse_table_to_cases in app.py
 */
@Service
public class TableParserService {

    private static final Pattern SEPARATOR = Pattern.compile("^\\|[-:\\s|]+\\|$");

    public List<TestCase> parseTable(String text, String storyKey) {
        List<TestCase> cases = new ArrayList<>();
        if (text == null || text.isBlank()) return cases;
        String[] lines = text.trim().split("\n");
        if (lines.length < 2) return cases;
        List<String[]> rows = new ArrayList<>();
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (!line.contains("|")) continue;
            if (SEPARATOR.matcher(line).matches()) continue; // skip |---| row
            String[] cells = line.split("\\|");
            if (cells.length < 2) continue;
            String[] trimmed = new String[cells.length - 1];
            for (int i = 1; i < cells.length; i++) trimmed[i - 1] = cells[i].trim();
            rows.add(trimmed);
        }
        if (rows.size() < 2) return cases;
        String[] headers = rows.get(0);
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            String[] padded = new String[headers.length];
            for (int i = 0; i < headers.length; i++) {
                padded[i] = i < row.length ? row[i] : "";
            }
            java.util.Map<String, String> map = new java.util.HashMap<>();
            for (int i = 0; i < headers.length; i++) map.put(headers[i], padded[i]);
            TestCase tc = new TestCase(
                get(map, "ID"),
                get(map, "Test Case Name"),
                get(map, "Priority"),
                get(map, "Severity"),
                get(map, "Test Type"),
                get(map, "Steps"),
                get(map, "Expected Result"),
                get(map, "Test Data"),
                storyKey
            );
            cases.add(tc);
        }
        return cases;
    }

    private String get(java.util.Map<String, String> m, String key) {
        String v = m.get(key);
        return v != null ? v : "";
    }
}
