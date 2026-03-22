package com.example.testcase.service;

import com.example.testcase.model.StoryAttachment;
import com.example.testcase.model.StoryDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parses Jira story content from Gemini CLI output: JSON-first, then markdown sections, then legacy TITLE:/DESCRIPTION:/AC.
 */
@Component
public class StoryDetailsParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern TITLE_LINE = Pattern.compile("(?i)^TITLE:\\s*(.*)$");
    private static final Pattern SUMMARY_LINE = Pattern.compile("(?i)^Summary:\\s*(.*)$");
    private static final Pattern DESCRIPTION_LINE = Pattern.compile("(?i)^DESCRIPTION:\\s*(.*)$");
    private static final Pattern AC_HEADER = Pattern.compile("(?i)^ACCEPTANCE_CRITERIA:?\\s*(.*)$");
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern BOLD_HEADING = Pattern.compile("^\\*\\*([^*]+)\\*\\*\\s*:?\\s*$");
    private static final Pattern NUMBERED_AC = Pattern.compile("^\\d+[.)]\\s+(.*)$");
    private static final Pattern CHECKBOX = Pattern.compile("^(?:[-*+]\\s*)?\\[( |x|X)\\]\\s*(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\|?\\s*:?-{3,}.*");
    private static final Pattern BULLET = Pattern.compile("^[-*+•]\\s+(.*)$");
    private static final Pattern GHERKIN = Pattern.compile("(?i)^(Given|When|Then|And|But)\\b.*");

    private static final String AC_HEADER_LABELS =
        "acceptance criteria|acceptance|definition of done|\\bdod\\b|\\bac\\b";
    private static final Pattern AC_SECTION_MD = Pattern.compile(
        "(?i)^(#{1,6})\\s*(" + AC_HEADER_LABELS + ")\\s*:?\\s*$");
    private static final Pattern AC_SECTION_BOLD = Pattern.compile(
        "(?i)^\\*\\*\\s*(" + AC_HEADER_LABELS + ")\\s*\\*\\*\\s*:?\\s*$");

    private static final String DESC_HEADER_LABELS =
        "description|overview|details|background|user story|context";
    private static final Pattern DESC_SECTION_MD = Pattern.compile(
        "(?i)^(#{1,6})\\s*(" + DESC_HEADER_LABELS + ")\\s*:?\\s*$");
    private static final Pattern DESC_SECTION_BOLD = Pattern.compile(
        "(?i)^\\*\\*\\s*(" + DESC_HEADER_LABELS + ")\\s*\\*\\*\\s*:?\\s*$");

    public StoryDetails parse(String text) {
        if (text == null || text.isBlank()) {
            StoryDetails d = new StoryDetails();
            d.setTitle("N/A");
            d.setDescription("N/A");
            return d;
        }
        String trimmed = text.trim();
        StoryDetails fromJson = tryParseJson(trimmed);
        if (fromJson != null && isUsable(fromJson)) {
            return normalize(fromJson);
        }
        StoryDetails fromMd = tryParseMarkdownSections(trimmed);
        if (fromMd != null && isUsable(fromMd)) {
            return normalize(fromMd);
        }
        StoryDetails legacy = parseLegacyColonFormat(trimmed);
        return normalize(legacy);
    }

    private static boolean isUsable(StoryDetails d) {
        if (d == null) return false;
        boolean hasTitle = d.getTitle() != null && !d.getTitle().isBlank() && !"N/A".equalsIgnoreCase(d.getTitle().trim());
        boolean hasDesc = d.getDescription() != null && !d.getDescription().isBlank() && !"N/A".equalsIgnoreCase(d.getDescription().trim());
        boolean hasDm = d.getDescriptionMarkdown() != null && !d.getDescriptionMarkdown().isBlank() && !"N/A".equalsIgnoreCase(d.getDescriptionMarkdown().trim());
        boolean hasAc = d.getAcceptanceCriteria() != null && !d.getAcceptanceCriteria().isEmpty();
        boolean hasKp = d.getKeyPointsForTesting() != null && !d.getKeyPointsForTesting().isEmpty();
        return hasTitle || hasDesc || hasDm || hasAc || hasKp;
    }

    private StoryDetails tryParseJson(String text) {
        String jsonStr = extractJsonObject(text);
        if (jsonStr == null) return null;
        try {
            JsonNode root = JSON.readTree(jsonStr);
            if (root == null || !root.isObject()) return null;
            StoryDetails d = new StoryDetails();
            if (root.hasNonNull("title")) d.setTitle(root.get("title").asText("N/A"));
            if (root.hasNonNull("description")) d.setDescription(root.get("description").asText("N/A"));
            if (root.hasNonNull("descriptionMarkdown")) {
                d.setDescriptionMarkdown(root.get("descriptionMarkdown").asText(""));
            }
            if (root.hasNonNull("storyType")) {
                d.setStoryType(root.get("storyType").asText(""));
            }
            JsonNode ac = root.get("acceptanceCriteria");
            if (ac != null && ac.isArray()) {
                for (JsonNode n : ac) {
                    if (n != null && n.isTextual()) {
                        String s = n.asText().trim();
                        if (!s.isEmpty()) d.getAcceptanceCriteria().add(s);
                    }
                }
            } else if (ac != null && ac.isTextual()) {
                for (String line : ac.asText().split("\n")) {
                    String s = line.trim();
                    if (!s.isEmpty()) d.getAcceptanceCriteria().add(s);
                }
            }
            addStringArray(root, "keyPointsForTesting", d.getKeyPointsForTesting());
            addStringArray(root, "edgeCasesAndRisks", d.getEdgeCasesAndRisks());
            addStringArray(root, "examplesOrScenarios", d.getExamplesOrScenarios());
            parseAttachments(root.get("attachments"), d);
            syncDescriptionFromMarkdown(d);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract JSON object from text that may include fences or prose. */
    static String extractJsonObject(String text) {
        String t = stripMarkdownFences(text).trim();
        int start = t.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return t.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String stripMarkdownFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int endFence = t.lastIndexOf("```");
            if (endFence >= 0) t = t.substring(0, endFence);
        }
        return t.trim();
    }

    private StoryDetails tryParseMarkdownSections(String text) {
        String[] lines = text.split("\r?\n");
        if (lines.length == 0) return null;
        String title = null;
        List<String> descLines = new ArrayList<>();
        List<String> acList = new ArrayList<>();
        SectionMode mode = SectionMode.NONE;
        boolean firstBlock = true;
        List<String> preamble = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String s = raw.trim();

            var titleM = TITLE_LINE.matcher(s);
            if (titleM.matches()) {
                title = titleM.group(1).trim();
                mode = SectionMode.NONE;
                firstBlock = false;
                continue;
            }
            var summaryM = SUMMARY_LINE.matcher(s);
            if (summaryM.matches()) {
                title = summaryM.group(1).trim();
                mode = SectionMode.NONE;
                firstBlock = false;
                continue;
            }
            if (DESCRIPTION_LINE.matcher(s).matches()) {
                var m = DESCRIPTION_LINE.matcher(s);
                m.matches();
                String rest = m.group(1).trim();
                mode = SectionMode.DESCRIPTION;
                firstBlock = false;
                if (!rest.isEmpty()) descLines.add(rest);
                continue;
            }
            if (AC_HEADER.matcher(s).matches()) {
                var m = AC_HEADER.matcher(s);
                m.matches();
                String rest = m.group(1).trim();
                mode = SectionMode.AC;
                firstBlock = false;
                if (rest.startsWith("-")) acList.add(rest.substring(1).trim());
                else if (!rest.isEmpty()) addAcLine(acList, rest);
                continue;
            }

            if (isAcSectionHeader(s)) {
                mode = SectionMode.AC;
                firstBlock = false;
                continue;
            }
            if (isDescSectionHeader(s)) {
                mode = SectionMode.DESCRIPTION;
                firstBlock = false;
                continue;
            }

            var md = MD_HEADING.matcher(s);
            if (md.matches()) {
                String label = md.group(2).trim();
                if (matchesAcLabel(label)) {
                    mode = SectionMode.AC;
                    firstBlock = false;
                    continue;
                }
                if (matchesDescLabel(label)) {
                    mode = SectionMode.DESCRIPTION;
                    firstBlock = false;
                    continue;
                }
                if (title == null && firstBlock && md.group(1).length() == 1) {
                    title = label;
                    mode = SectionMode.NONE;
                    firstBlock = false;
                    continue;
                }
            }
            var bold = BOLD_HEADING.matcher(s);
            if (bold.matches()) {
                String label = bold.group(1).trim();
                if (matchesAcLabel(label)) {
                    mode = SectionMode.AC;
                    firstBlock = false;
                    continue;
                }
                if (matchesDescLabel(label)) {
                    mode = SectionMode.DESCRIPTION;
                    firstBlock = false;
                    continue;
                }
            }

            switch (mode) {
                case DESCRIPTION -> descLines.add(raw);
                case AC -> {
                    if (looksLikeMarkdownTableRow(s)) {
                        String next = i + 1 < lines.length ? lines[i + 1].trim() : "";
                        if (TABLE_SEP.matcher(next).matches()) {
                            continue;
                        }
                        if (TABLE_SEP.matcher(s).matches()) {
                            continue;
                        }
                        addAcTableRow(acList, s);
                    } else {
                        addAcLine(acList, s);
                    }
                }
                case NONE -> {
                    if (firstBlock && !s.isEmpty()) {
                        preamble.add(raw);
                    }
                }
            }
        }

        if (title == null && !preamble.isEmpty()) {
            String first = preamble.get(0).trim();
            var md = MD_HEADING.matcher(first);
            if (md.matches()) title = md.group(2).trim();
            else title = first;
            for (int j = 1; j < preamble.size(); j++) descLines.add(preamble.get(j));
        }

        StoryDetails d = new StoryDetails();
        d.setTitle(title != null && !title.isBlank() ? title : "N/A");
        d.setDescription(!descLines.isEmpty() ? String.join("\n", descLines).trim() : "N/A");
        d.setAcceptanceCriteria(acList);
        return d;
    }

    private enum SectionMode { NONE, DESCRIPTION, AC }

    private static boolean isAcSectionHeader(String s) {
        return AC_SECTION_MD.matcher(s).matches() || AC_SECTION_BOLD.matcher(s).matches();
    }

    private static boolean isDescSectionHeader(String s) {
        return DESC_SECTION_MD.matcher(s).matches() || DESC_SECTION_BOLD.matcher(s).matches();
    }

    private static boolean matchesAcLabel(String label) {
        String l = label.toLowerCase(Locale.ROOT).trim();
        if (l.equals("ac") || l.equals("dod")) return true;
        if (l.contains("acceptance")) return true;
        return l.contains("definition of done");
    }

    private static boolean matchesDescLabel(String label) {
        String l = label.toLowerCase(Locale.ROOT).trim();
        return l.equals("description") || l.equals("overview") || l.equals("details")
            || l.equals("background") || l.equals("user story") || l.equals("context");
    }

    private static void addAcLine(List<String> acList, String s) {
        if (s.isBlank()) return;
        var b = BULLET.matcher(s);
        if (b.matches()) {
            acList.add(b.group(1).trim());
            return;
        }
        var num = NUMBERED_AC.matcher(s);
        if (num.matches()) {
            acList.add(num.group(1).trim());
            return;
        }
        var cb = CHECKBOX.matcher(s);
        if (cb.matches()) {
            acList.add(cb.group(2).trim());
            return;
        }
        if (GHERKIN.matcher(s).matches()) {
            acList.add(s.trim());
            return;
        }
        if (s.contains("|") && looksLikeMarkdownTableRow(s)) {
            addAcTableRow(acList, s);
            return;
        }
        acList.add(s.trim());
    }

    private static void addAcTableRow(List<String> acList, String s) {
        if (TABLE_SEP.matcher(s).matches()) return;
        String[] cells = s.split("\\|");
        List<String> parts = new ArrayList<>();
        for (String c : cells) {
            String t = c.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        if (parts.isEmpty()) return;
        acList.add(String.join(" — ", parts));
    }

    private static boolean looksLikeMarkdownTableRow(String s) {
        return s.contains("|") && s.trim().startsWith("|");
    }

    StoryDetails parseLegacyColonFormat(String text) {
        StoryDetails d = new StoryDetails();
        List<String> descLines = new ArrayList<>();
        String section = null;
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.matches("(?i)^TITLE:\\s*.*")) {
                d.setTitle(s.replaceFirst("(?i)^TITLE:\\s*", "").trim());
                section = null;
            } else if (s.matches("(?i)^DESCRIPTION:\\s*.*")) {
                section = "desc";
                descLines.add(s.replaceFirst("(?i)^DESCRIPTION:\\s*", "").trim());
            } else if (s.matches("(?i)^ACCEPTANCE_CRITERIA.*")) {
                if (!descLines.isEmpty()) d.setDescription(String.join("\n", descLines));
                section = "ac";
                String part = s.replaceFirst("(?i)^ACCEPTANCE_CRITERIA:?\\s*", "").trim();
                addAcLine(d.getAcceptanceCriteria(), part);
            } else if ("desc".equals(section)) {
                descLines.add(s);
            } else if ("ac".equals(section)) {
                if (looksLikeMarkdownTableRow(s)) {
                    addAcTableRow(d.getAcceptanceCriteria(), s);
                } else {
                    addAcLine(d.getAcceptanceCriteria(), s);
                }
            }
        }
        if (!descLines.isEmpty() && "N/A".equals(d.getDescription())) {
            d.setDescription(String.join("\n", descLines));
        }
        if (d.getTitle() == null || d.getTitle().isBlank()) d.setTitle("N/A");
        if (d.getDescription() == null || d.getDescription().isBlank()) d.setDescription("N/A");
        return d;
    }

    private static StoryDetails normalize(StoryDetails d) {
        if (d.getTitle() == null || d.getTitle().isBlank()) d.setTitle("N/A");
        if (d.getDescription() == null || d.getDescription().isBlank()) d.setDescription("N/A");
        if (d.getAcceptanceCriteria() == null) d.setAcceptanceCriteria(new ArrayList<>());
        if (d.getKeyPointsForTesting() == null) d.setKeyPointsForTesting(new ArrayList<>());
        if (d.getEdgeCasesAndRisks() == null) d.setEdgeCasesAndRisks(new ArrayList<>());
        if (d.getExamplesOrScenarios() == null) d.setExamplesOrScenarios(new ArrayList<>());
        if (d.getAttachments() == null) d.setAttachments(new ArrayList<>());
        if (d.getStoryType() == null) d.setStoryType("");
        if (d.getDescriptionMarkdown() == null) d.setDescriptionMarkdown("");
        syncDescriptionFromMarkdown(d);
        return d;
    }

    private static void addStringArray(JsonNode root, String key, List<String> target) {
        JsonNode n = root.get(key);
        if (n == null || !n.isArray()) return;
        for (JsonNode item : n) {
            if (item != null && item.isTextual()) {
                String s = item.asText().trim();
                if (!s.isEmpty()) target.add(s);
            }
        }
    }

    private static void parseAttachments(JsonNode node, StoryDetails d) {
        if (node == null || !node.isArray()) return;
        for (JsonNode n : node) {
            if (n == null) continue;
            if (n.isTextual()) {
                String fn = n.asText().trim();
                if (!fn.isEmpty()) d.getAttachments().add(new StoryAttachment(fn, null));
            } else if (n.isObject()) {
                String fn = n.hasNonNull("filename") ? n.get("filename").asText("") : "";
                String note = n.hasNonNull("note") ? n.get("note").asText(null) : null;
                if (!fn.isBlank()) d.getAttachments().add(new StoryAttachment(fn, note));
            }
        }
    }

    /** If description is still N/A but markdown body exists, align description for legacy consumers. */
    private static void syncDescriptionFromMarkdown(StoryDetails d) {
        if (d.getDescriptionMarkdown() == null || d.getDescriptionMarkdown().isBlank()) return;
        String desc = d.getDescription();
        if (desc == null || desc.isBlank() || "N/A".equalsIgnoreCase(desc.trim())) {
            d.setDescription(d.getDescriptionMarkdown());
        }
    }
}
