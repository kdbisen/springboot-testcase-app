package com.example.testcase.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Atlassian Document Format (ADF) to plain text and lightweight markdown for prompts and UI.
 */
public final class AdfConverter {

    private AdfConverter() {}

    public static String toPlainText(JsonNode adf) {
        if (adf == null || adf.isNull() || adf.isMissingNode()) return "";
        return walkPlain(adf).trim();
    }

    public static String toMarkdown(JsonNode adf) {
        if (adf == null || adf.isNull() || adf.isMissingNode()) return "";
        return walkMarkdown(adf).trim();
    }

    private static String walkPlain(JsonNode node) {
        if (node == null || node.isNull()) return "";
        String type = node.path("type").asText("");
        return switch (type) {
            case "doc" -> joinChildrenPlain(node);
            case "paragraph" -> joinInlinePlain(node) + "\n";
            case "heading" -> joinInlinePlain(node) + "\n";
            case "blockquote" -> joinChildrenPlain(node) + "\n";
            case "codeBlock" -> node.path("text").asText("") + "\n";
            case "text" -> node.path("text").asText("");
            case "hardBreak" -> "\n";
            case "bulletList" -> listPlain(node, false);
            case "orderedList" -> listPlain(node, true);
            case "listItem" -> joinChildrenPlain(node);
            case "table" -> tablePlain(node);
            case "tableRow" -> joinChildrenPlain(node);
            case "tableCell" -> joinInlinePlain(node) + "\t";
            case "mediaSingle", "media" -> "";
            case "rule" -> "\n---\n";
            default -> {
                if (node.has("content")) yield joinChildrenPlain(node);
                yield "";
            }
        };
    }

    private static String listPlain(JsonNode listNode, boolean ordered) {
        JsonNode content = listNode.get("content");
        if (content == null || !content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (JsonNode item : content) {
            String prefix = ordered ? (n++ + ". ") : "- ";
            String body = walkPlain(item).trim();
            if (!body.isEmpty()) sb.append(prefix).append(body).append("\n");
        }
        return sb.toString();
    }

    private static String tablePlain(JsonNode table) {
        JsonNode rows = table.get("content");
        if (rows == null || !rows.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode row : rows) {
            String line = walkPlain(row).trim().replaceAll("\t+$", "").replace("\t", " | ");
            if (!line.isEmpty()) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static String joinChildrenPlain(JsonNode node) {
        JsonNode content = node.get("content");
        if (content == null || !content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            sb.append(walkPlain(c));
        }
        return sb.toString();
    }

    private static String joinInlinePlain(JsonNode node) {
        JsonNode content = node.get("content");
        if (content == null || !content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            sb.append(walkPlain(c));
        }
        return sb.toString();
    }

    private static String walkMarkdown(JsonNode node) {
        if (node == null || node.isNull()) return "";
        String type = node.path("type").asText("");
        return switch (type) {
            case "doc" -> joinChildrenMd(node);
            case "paragraph" -> joinInlineMd(node) + "\n\n";
            case "heading" -> {
                int level = Math.min(6, Math.max(1, node.path("attrs").path("level").asInt(2)));
                String hashes = "#".repeat(level);
                yield hashes + " " + joinInlineMd(node).trim() + "\n\n";
            }
            case "blockquote" -> {
                String inner = joinChildrenMd(node).trim().replace("\n", "\n> ");
                yield "> " + inner + "\n\n";
            }
            case "codeBlock" -> "```\n" + node.path("text").asText("") + "\n```\n\n";
            case "text" -> formatTextMarks(node);
            case "hardBreak" -> "  \n";
            case "bulletList" -> listMd(node, false);
            case "orderedList" -> listMd(node, true);
            case "listItem" -> joinChildrenMd(node);
            case "table" -> tableMd(node);
            case "tableRow", "tableCell" -> joinChildrenMd(node);
            case "rule" -> "\n---\n\n";
            default -> {
                if (node.has("content")) yield joinChildrenMd(node);
                yield "";
            }
        };
    }

    private static String formatTextMarks(JsonNode textNode) {
        String t = textNode.path("text").asText("");
        JsonNode marks = textNode.get("marks");
        if (marks == null || !marks.isArray()) return t;
        for (JsonNode m : marks) {
            String mt = m.path("type").asText("");
            t = switch (mt) {
                case "strong" -> "**" + t + "**";
                case "em" -> "*" + t + "*";
                case "code" -> "`" + t + "`";
                case "link" -> {
                    String href = m.path("attrs").path("href").asText("");
                    yield "[" + t + "](" + href + ")";
                }
                default -> t;
            };
        }
        return t;
    }

    private static String joinInlineMd(JsonNode node) {
        JsonNode content = node.get("content");
        if (content == null || !content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            sb.append(walkMarkdown(c));
        }
        return sb.toString();
    }

    private static String joinChildrenMd(JsonNode node) {
        JsonNode content = node.get("content");
        if (content == null || !content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            sb.append(walkMarkdown(c));
        }
        return sb.toString();
    }

    private static String listMd(JsonNode listNode, boolean ordered) {
        JsonNode content = listNode.get("content");
        if (content == null || !content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (JsonNode item : content) {
            String prefix = ordered ? (n++ + ". ") : "- ";
            String body = walkMarkdown(item).trim();
            if (!body.isEmpty()) sb.append(prefix).append(body).append("\n");
        }
        return sb.toString() + "\n";
    }

    private static String tableMd(JsonNode table) {
        JsonNode rows = table.get("content");
        if (rows == null || !rows.isArray() || rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            if (!"tableRow".equals(row.path("type").asText())) continue;
            JsonNode cells = row.get("content");
            if (cells == null || !cells.isArray()) continue;
            sb.append("|");
            for (JsonNode cell : cells) {
                String text = joinInlineMd(cell).trim().replace("\n", " ").replace("|", "\\|");
                sb.append(" ").append(text).append(" |");
            }
            sb.append("\n");
            if (i == 0) {
                sb.append("|");
                for (int c = 0; c < cells.size(); c++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        return sb.toString() + "\n";
    }
}
