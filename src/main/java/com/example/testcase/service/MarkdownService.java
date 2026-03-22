package com.example.testcase.service;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Renders markdown to HTML for safe display in Thymeleaf (story digest). Text nodes only; raw HTML in source is escaped by commonmark.
 */
@Service
public class MarkdownService {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownService() {
        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder().extensions(extensions).build();
    }

    public String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }
}
