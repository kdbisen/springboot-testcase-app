package com.example.testcase.service;

import com.example.testcase.model.TestCase;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ExportService {

    public byte[] toExcel(List<TestCase> cases, String storyKey) throws Exception {
        String[] headers = {"ID", "Test Case Name", "Priority", "Severity", "Test Type", "Steps", "Expected Result", "Test Data", "story_key"};
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Test Cases");
            CellStyle headerStyle = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }
            int rowNum = 1;
            for (TestCase tc : cases) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(tc.getId());
                row.createCell(1).setCellValue(tc.getTitle());
                row.createCell(2).setCellValue(tc.getPriority());
                row.createCell(3).setCellValue(tc.getSeverity());
                row.createCell(4).setCellValue(tc.getTestType());
                row.createCell(5).setCellValue(tc.getSteps());
                row.createCell(6).setCellValue(tc.getExpected());
                row.createCell(7).setCellValue(tc.getData());
                row.createCell(8).setCellValue(tc.getStoryKey());
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        }
    }

    public String toAllureCsv(List<TestCase> cases, String storyKey) {
        StringWriter sw = new StringWriter();
        sw.write("allure_id,name,description,precondition,scenario,expected_result,tested_feature,tested_story,tags,created_by,supervised_by,useful_links,issue_tracker\n");
        for (int i = 0; i < cases.size(); i++) {
            TestCase tc = cases.get(i);
            String name = (tc.getTitle() != null ? tc.getTitle() : tc.getId() != null ? tc.getId() : "Test Case " + (i + 1));
            if (name.length() > 255) name = name.substring(0, 255);
            String desc = "Priority: " + (tc.getPriority() != null ? tc.getPriority() : "") + " | Severity: " + (tc.getSeverity() != null ? tc.getSeverity() : "") + " | Type: " + (tc.getTestType() != null ? tc.getTestType() : "");
            String steps = tc.getSteps() != null ? tc.getSteps() : "";
            String expected = tc.getExpected() != null ? tc.getExpected() : "";
            String data = tc.getData() != null ? tc.getData() : "";
            String sk = tc.getStoryKey() != null ? tc.getStoryKey() : storyKey;
            StringBuilder scenario = new StringBuilder();
            for (String line : steps.split("\n")) {
                line = line.trim();
                if (!line.isEmpty()) {
                    scenario.append(line).append("\n");
                    if (!expected.isEmpty()) { scenario.append("\tExpected: ").append(expected).append("\n"); expected = ""; }
                }
            }
            if (!data.isEmpty()) scenario.append("\tTest Data: ").append(data);
            if (scenario.length() == 0) scenario.append(tc.getExpected() != null && !tc.getExpected().isEmpty() ? tc.getExpected() : "No steps");
            List<String> tagsParts = new ArrayList<>();
            if (sk != null && !sk.isEmpty()) tagsParts.add(sk);
            if (tc.getPriority() != null && !tc.getPriority().isEmpty()) tagsParts.add(tc.getPriority());
            if (tc.getSeverity() != null && !tc.getSeverity().isEmpty()) tagsParts.add(tc.getSeverity());
            if (tc.getTestType() != null && !tc.getTestType().isEmpty()) tagsParts.add(tc.getTestType());
            String tags = String.join(",", tagsParts);
            sw.write(csvEscape("") + "," + csvEscape(name) + "," + csvEscape(desc) + "," + csvEscape(data) + "," +
                csvEscape(scenario.toString()) + "," + csvEscape(tc.getExpected() != null ? tc.getExpected() : "") + "," +
                csvEscape(sk != null ? sk : "") + "," + csvEscape(sk != null ? sk : "") + "," + csvEscape(tags) + ",,,," + csvEscape(sk != null ? sk : "") + "\n");
        }
        return sw.toString();
    }

    private String csvEscape(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
