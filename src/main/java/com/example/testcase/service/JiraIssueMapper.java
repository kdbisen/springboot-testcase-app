package com.example.testcase.service;

import com.example.testcase.model.StoryAttachment;
import com.example.testcase.model.StoryDetails;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps Jira REST API v3 issue JSON to {@link StoryDetails} (summary, ADF description, attachments).
 */
public final class JiraIssueMapper {

    private static final Pattern AC_HEADER = Pattern.compile(
        "(?i)^#+\\s*(acceptance criteria|definition of done|\\bac\\b|\\bdod\\b)\\s*:?\\s*$");
    private static final Pattern AC_BOLD_HEADER = Pattern.compile(
        "(?i)^\\*\\*\\s*(acceptance criteria|definition of done)\\s*\\*\\*\\s*:?\\s*$");

    private JiraIssueMapper() {}

    public static StoryDetails fromIssueJson(JsonNode issue) {
        StoryDetails d = new StoryDetails();
        if (issue == null || issue.isNull()) {
            d.setTitle("N/A");
            d.setDescription("N/A");
            return d;
        }
        JsonNode fields = issue.get("fields");
        if (fields == null || fields.isNull()) {
            d.setTitle("N/A");
            d.setDescription("N/A");
            return d;
        }

        d.setTitle(textOrNa(fields.get("summary")));

        JsonNode issuetype = fields.get("issuetype");
        if (issuetype != null && !issuetype.isNull()) {
            d.setStoryType(issuetype.path("name").asText(""));
        }

        JsonNode desc = fields.get("description");
        String plain;
        String md;
        if (desc != null && desc.isObject() && desc.has("type")) {
            plain = AdfConverter.toPlainText(desc);
            md = AdfConverter.toMarkdown(desc);
        } else if (desc != null && desc.isTextual()) {
            plain = desc.asText();
            md = plain;
        } else {
            plain = "";
            md = "";
        }

        if (plain.isBlank()) {
            d.setDescription("N/A");
            d.setDescriptionMarkdown("");
        } else {
            d.setDescription(plain);
            d.setDescriptionMarkdown(md.isBlank() ? plain : md);
        }

        d.setAcceptanceCriteria(extractAcceptanceCriteria(plain, md));

        List<StoryAttachment> attachments = new ArrayList<>();
        JsonNode att = fields.get("attachment");
        if (att != null && att.isArray()) {
            for (JsonNode a : att) {
                if (a == null) continue;
                String fn = a.path("filename").asText("");
                if (fn.isBlank()) continue;
                long size = a.path("size").asLong(0);
                String note = size > 0 ? (size + " bytes") : null;
                attachments.add(new StoryAttachment(fn, note));
            }
        }
        d.setAttachments(attachments);

        return d;
    }

    private static String textOrNa(JsonNode n) {
        if (n == null || n.isNull()) return "N/A";
        String t = n.asText("").trim();
        return t.isEmpty() ? "N/A" : t;
    }

    /**
     * Heuristic: lines under Acceptance Criteria / Definition of Done headings, or numbered/bulleted blocks
     * following those labels.
     */
    static List<String> extractAcceptanceCriteria(String plain, String markdown) {
        String src = markdown != null && !markdown.isBlank() ? markdown : plain;
        if (src == null || src.isBlank()) return List.of();

        List<String> lines = new ArrayList<>();
        String[] split = src.split("\r?\n");
        boolean inSection = false;
        Pattern bullet = Pattern.compile("^[-*+•]\\s+(.*)$");
        Pattern numbered = Pattern.compile("^\\d+[.)]\\s+(.*)$");

        for (String raw : split) {
            String t = raw.trim();
            if (AC_HEADER.matcher(t).matches() || AC_BOLD_HEADER.matcher(t).matches()) {
                inSection = true;
                continue;
            }
            if (inSection) {
                if (t.startsWith("#") && t.length() > 1 && !t.startsWith("# ")) {
                    break;
                }
                if (t.matches("^#{1,6}\\s+.+") && !AC_HEADER.matcher(t).matches()) {
                    // next markdown section
                    if (lines.isEmpty()) continue;
                    break;
                }
                if (t.isBlank()) {
                    if (!lines.isEmpty()) break;
                    continue;
                }
                Matcher bm = bullet.matcher(t);
                if (bm.matches()) {
                    lines.add(bm.group(1).trim());
                    continue;
                }
                Matcher nm = numbered.matcher(t);
                if (nm.matches()) {
                    lines.add(nm.group(1).trim());
                    continue;
                }
                if (!lines.isEmpty() && !t.startsWith("#")) {
                    lines.set(lines.size() - 1, lines.get(lines.size() - 1) + " " + t);
                }
            }
        }
        return lines;
    }
}
