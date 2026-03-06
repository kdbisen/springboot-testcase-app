package com.example.testcase.service;

import com.example.testcase.model.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parse markdown table from Gemini output into TestCase list.
 * Handles flexible header names (Steps/Step, Expected Result/Expected) and normalizes semicolons in Steps to newlines.
 */
@Service
public class TableParserService {

    private static final Logger log = LoggerFactory.getLogger(TableParserService.class);
    private static final Pattern SEPARATOR = Pattern.compile("^\\|[-:\\s|]+\\|$");

    public List<TestCase> parseTable(String text, String storyKey) {
        List<TestCase> cases = new ArrayList<>();
        if (text == null || text.isBlank()) return cases;
        String[] lines = text.trim().split("\n");
        if (lines.length < 2) return cases;
        // Merge continuation lines (lines without | that continue previous cell content, e.g. multi-line Steps)
        List<String> mergedLines = mergeMultilineCells(lines);
        List<String[]> rows = new ArrayList<>();
        for (String line : mergedLines) {
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
        log.debug("Table headers: {}", String.join(" | ", headers));
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            String[] padded = new String[headers.length];
            for (int i = 0; i < headers.length; i++) {
                padded[i] = i < row.length ? row[i] : "";
            }
            Map<String, String> map = new java.util.HashMap<>();
            for (int i = 0; i < headers.length; i++) map.put(headers[i], padded[i]);
            String steps = getAny(map, "Steps", "Step", "Test Steps", "Steps to Execute");
            // Normalize: semicolons -> newlines for display
            if (steps != null && !steps.isEmpty()) {
                steps = steps.replace("; ", "\n").replace(";", "\n").trim();
            }
            TestCase tc = new TestCase(
                getAny(map, "ID"),
                getAny(map, "Test Case Name", "Title", "Name"),
                getAny(map, "Priority"),
                getAny(map, "Severity"),
                getAny(map, "Test Type", "Type"),
                steps != null ? steps : "",
                getAny(map, "Expected Result", "Expected", "Expected Outcome"),
                getAny(map, "Test Data", "Data"),
                storyKey
            );
            cases.add(tc);
        }
        return cases;
    }

    /** Merge lines that continue a previous table cell (e.g. Steps with newlines). */
    private List<String> mergeMultilineCells(String[] lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            boolean hasPipe = line.contains("|");
            if (result.isEmpty()) {
                result.add(line);
            } else if (!hasPipe && !line.trim().isEmpty()) {
                // Line without | continues previous cell - append to previous line
                String last = result.get(result.size() - 1);
                int lastPipe = last.lastIndexOf('|');
                if (lastPipe > 0) {
                    result.set(result.size() - 1, last.substring(0, lastPipe) + "\n" + line.trim() + " |");
                } else {
                    result.set(result.size() - 1, last + "\n" + line.trim());
                }
            } else if (hasPipe && !result.get(result.size() - 1).trim().endsWith("|")) {
                // Line with | but previous doesn't end with | - rest of same row (e.g. "| Expected | Data |")
                result.set(result.size() - 1, result.get(result.size() - 1) + " " + line.trim());
            } else {
                result.add(line);
            }
        }
        return result;
    }

    private String getAny(Map<String, String> m, String... keys) {
        for (String key : keys) {
            String v = m.get(key);
            if (v != null && !v.isBlank()) return v;
        }
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            for (String key : keys) {
                if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
            }
        }
        return "";
    }
}
