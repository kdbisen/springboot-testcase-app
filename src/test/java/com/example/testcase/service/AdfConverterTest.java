package com.example.testcase.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdfConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void toPlainText_paragraphAndBullet() throws Exception {
        String raw = """
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"Hello"}]},
              {"type":"bulletList","content":[
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"One"}]}]}
              ]}
            ]}""";
        JsonNode adf = JSON.readTree(raw);
        String plain = AdfConverter.toPlainText(adf);
        assertTrue(plain.contains("Hello"));
        assertTrue(plain.contains("One"));
    }

    @Test
    void toMarkdown_heading() throws Exception {
        String raw = """
            {"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Title"}]}
            ]}""";
        String md = AdfConverter.toMarkdown(JSON.readTree(raw));
        assertTrue(md.contains("## Title"));
    }
}
