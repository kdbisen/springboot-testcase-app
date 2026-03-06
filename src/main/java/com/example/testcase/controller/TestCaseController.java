package com.example.testcase.controller;

import com.example.testcase.model.AppSession;
import com.example.testcase.model.JiraSearchResult;
import com.example.testcase.model.StoryDetails;
import com.example.testcase.model.TestCase;
import com.example.testcase.model.TestCaseView;
import com.example.testcase.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
public class TestCaseController {

    private static final Logger log = LoggerFactory.getLogger(TestCaseController.class);
    private final GeminiCliService geminiService;
    private final StoryCacheService cacheService;
    private final TableParserService tableParser;
    private final ExportService exportService;
    private final ObjectMapper json = new ObjectMapper();

    public TestCaseController(GeminiCliService geminiService, StoryCacheService cacheService,
                               TableParserService tableParser, ExportService exportService) {
        this.geminiService = geminiService;
        this.cacheService = cacheService;
        this.tableParser = tableParser;
        this.exportService = exportService;
    }

    @SuppressWarnings("unchecked")
    private AppSession getSession(HttpSession session) {
        AppSession s = (AppSession) session.getAttribute("appSession");
        if (s == null) {
            s = new AppSession();
            session.setAttribute("appSession", s);
        }
        return s;
    }

    @GetMapping("/")
    public String index(Model model, HttpSession session) {
        AppSession s = getSession(session);
        model.addAttribute("appSession", s);  // Use appSession to avoid conflict with Thymeleaf's built-in "session"
        model.addAttribute("hasStory", s.hasStory());
        model.addAttribute("hasTestCases", s.hasTestCases());
        model.addAttribute("stepLabels", getStepLabels(s));
        List<TestCaseView> views = new ArrayList<>();
        int i = 0;
        for (TestCase tc : s.getTestCases()) {
            views.add(new TestCaseView(tc, i, false));
            i++;
        }
        int ci = 0;
        for (TestCase tc : s.getCustomCases()) {
            views.add(new TestCaseView(tc, -1, ci++, true));
        }
        model.addAttribute("visibleTestCases", views);
        List<String> storyKeys = s.getStoryKeys().isEmpty() && s.getStoryKey() != null
            ? List.of(s.getStoryKey()) : s.getStoryKeys();
        model.addAttribute("storyKeys", storyKeys);
        // Group test cases by story for Step 3 display
        Map<String, List<TestCaseView>> byStory = new LinkedHashMap<>();
        for (String sk : storyKeys) byStory.put(sk, new ArrayList<>());
        for (TestCaseView v : views) {
            String sk = v.getTestCase().getStoryKey() != null ? v.getTestCase().getStoryKey() : (storyKeys.isEmpty() ? "" : storyKeys.get(0));
            byStory.computeIfAbsent(sk, k -> new ArrayList<>()).add(v);
        }
        model.addAttribute("testCasesByStory", byStory);
        return "index";
    }

    private List<String> getStepLabels(AppSession s) {
        return List.of(
            "1. Fetch Story" + (s.hasStory() ? " ✓" : ""),
            "2. Review & Generate" + (s.hasTestCases() ? " ✓" : ""),
            "3. Refine & Export"
        );
    }

    // --- Step 1: Search Jira ---
    @PostMapping("/search")
    public String search(@RequestParam(required = false) String query, RedirectAttributes ra, HttpSession session) {
        AppSession s = getSession(session);
        log.info("Search Jira: query={}", query);
        if (query == null || query.isBlank()) {
            ra.addFlashAttribute("error", "Enter a search term.");
            return "redirect:/";
        }
        try {
            List<JiraSearchResult> results = geminiService.searchJiraIssues(query.trim(), 10);
            log.info("Search returned {} issues", results.size());
            s.setJiraSearchResults(results);
            s.setJiraSearchDone(true);
            ra.addFlashAttribute("message", "Found " + results.size() + " issues.");
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage(), e);
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    // --- Step 1: Fetch Story ---
    @PostMapping("/fetch")
    public String fetch(@RequestParam(required = false) String jiraInput,
                        @RequestParam(required = false) String selectedKey,
                        @RequestParam(defaultValue = "false") boolean batchMode,
                        RedirectAttributes ra, HttpSession session) {
        AppSession s = getSession(session);
        List<String> keysToFetch = new ArrayList<>();
        if (selectedKey != null && !selectedKey.isBlank()) {
            keysToFetch.add(selectedKey.trim().toUpperCase());
            log.info("Fetch story: selectedKey={}", selectedKey);
        } else if (jiraInput != null && !jiraInput.isBlank()) {
            keysToFetch = com.example.testcase.util.JiraKeyUtil.extractStoryKeys(jiraInput);
            log.info("Fetch story: jiraInput={} -> keys={}", jiraInput, keysToFetch);
        }
        if (keysToFetch.isEmpty()) {
            log.warn("Fetch failed: no Jira ID provided (jiraInput={}, selectedKey={})", jiraInput, selectedKey);
            ra.addFlashAttribute("error", "Enter a Jira ID (e.g. PROJ-456) or select from search.");
            return "redirect:/";
        }
        try {
            Map<String, StoryDetails> batch = new LinkedHashMap<>();
            for (String key : keysToFetch) {
                log.debug("Fetching story {}", key);
                StoryDetails details = cacheService.getCachedStory(key);
                if (details == null) {
                    details = geminiService.fetchStoryDetails(key);
                    cacheService.cacheStory(key, details);
                    log.info("Fetched story {} from Jira (via Gemini CLI)", key);
                } else {
                    log.info("Using cached story {}", key);
                }
                batch.put(key, details);
            }
            s.setStoryKey(keysToFetch.get(0));
            s.setStoryKeys(keysToFetch);
            s.setStoryDetails(batch.get(keysToFetch.get(0)));
            s.setStoryDetailsBatch(batch);
            s.setActiveStep(1);
            ra.addFlashAttribute("message", "Fetched " + keysToFetch.size() + " story/stories.");
        } catch (Exception e) {
            log.error("Fetch failed for keys {}: {}", keysToFetch, e.getMessage(), e);
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    // --- Step 2: Generate Test Cases ---
    @PostMapping("/generate")
    public String generate(@RequestParam(defaultValue = "true") boolean includeNegative,
                           @RequestParam(defaultValue = "true") boolean includeBoundary,
                           @RequestParam(required = false) String customInstructions,
                           RedirectAttributes ra, HttpSession session) {
        AppSession s = getSession(session);
        log.info("Generate test cases: includeNegative={}, includeBoundary={}", includeNegative, includeBoundary);
        if (!s.hasStory()) {
            ra.addFlashAttribute("error", "Fetch a story first.");
            return "redirect:/";
        }
        List<String> storyKeys = s.getStoryKeys().isEmpty() ? List.of(s.getStoryKey()) : s.getStoryKeys();
        try {
            List<TestCase> allCases = new ArrayList<>();
            int tcOffset = 0;
            for (String sk : storyKeys) {
                StoryDetails det = s.getStoryDetailsBatch().get(sk);
                if (det == null) det = s.getStoryDetails();
                String table = geminiService.generateTestCases(sk, det, includeNegative, includeBoundary, customInstructions);
                List<TestCase> cases = tableParser.parseTable(table, sk);
                for (TestCase tc : cases) {
                    tc.setStoryKey(sk);
                    if (tc.getId() == null || tc.getId().isBlank()) tc.setId("TC-" + String.format("%02d", ++tcOffset));
                    allCases.add(tc);
                }
                tcOffset = allCases.size();
            }
            s.setTestCases(allCases);
            s.setCustomCases(new ArrayList<>());
            s.setActiveStep(2);
            log.info("Generated {} test cases for stories {}", allCases.size(), storyKeys);
            ra.addFlashAttribute("message", "Generated " + allCases.size() + " test cases.");
        } catch (Exception e) {
            log.error("Generate failed: {}", e.getMessage(), e);
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    /** Apply edits from form params (tc_X_priority, tc_X_expected, etc.) to session test cases. */
    private void applyUpdatesFromParams(AppSession s, Map<String, String> params) {
        if (params == null) return;
        Set<String> indices = new HashSet<>();
        for (String key : params.keySet()) {
            if (key.matches("tc_\\d+_.*")) {
                String idx = key.replaceFirst("tc_(\\d+)_.*", "$1");
                indices.add(idx);
            }
        }
        for (String idxStr : indices) {
            int idx;
            try { idx = Integer.parseInt(idxStr); } catch (NumberFormatException e) { continue; }
            TestCase tc = idx >= 1000 && (idx - 1000) < s.getCustomCases().size()
                ? s.getCustomCases().get(idx - 1000) : null;
            if (tc == null && idx < s.getTestCases().size()) tc = s.getTestCases().get(idx);
            if (tc == null) continue;
            String v;
            if ((v = params.get("tc_" + idx + "_title")) != null) tc.setTitle(v);
            if ((v = params.get("tc_" + idx + "_priority")) != null) tc.setPriority(v);
            if ((v = params.get("tc_" + idx + "_severity")) != null) tc.setSeverity(v);
            if ((v = params.get("tc_" + idx + "_testType")) != null) tc.setTestType(v);
            if ((v = params.get("tc_" + idx + "_expected")) != null) tc.setExpected(v);
            if ((v = params.get("tc_" + idx + "_data")) != null) tc.setData(v);
            if ((v = params.get("tc_" + idx + "_steps")) != null) tc.setSteps(v);
        }
    }

    // --- Step 3: Reject test case ---
    @PostMapping("/reject/{index}")
    public String reject(@PathVariable int index, @RequestParam Map<String, String> params, RedirectAttributes ra, HttpSession session) {
        AppSession s = getSession(session);
        applyUpdatesFromParams(s, params);
        if (index >= 0 && index < s.getTestCases().size()) {
            s.getTestCases().get(index).setRejected(true);
        }
        return "redirect:/#step3";
    }

    // --- Step 3: Approve test case (un-reject) ---
    @PostMapping("/approve/{index}")
    public String approve(@PathVariable int index, @RequestParam Map<String, String> params, RedirectAttributes ra, HttpSession session) {
        AppSession s = getSession(session);
        applyUpdatesFromParams(s, params);
        if (index >= 0 && index < s.getTestCases().size()) {
            s.getTestCases().get(index).setRejected(false);
        }
        return "redirect:/#step3";
    }

    // --- Step 3: Remove custom test case ---
    @PostMapping("/remove-custom/{index}")
    public String removeCustom(@PathVariable int index, @RequestParam Map<String, String> params, HttpSession session) {
        AppSession s = getSession(session);
        applyUpdatesFromParams(s, params);
        if (index >= 0 && index < s.getCustomCases().size()) {
            s.getCustomCases().remove(index);
        }
        return "redirect:/#step3";
    }

    // --- Step 3: Add custom test case ---
    @PostMapping("/add-custom")
    public String addCustom(@RequestParam String storyKey, @RequestParam Map<String, String> params, RedirectAttributes ra, HttpSession session) {
        AppSession s = getSession(session);
        applyUpdatesFromParams(s, params);
        TestCase tc = new TestCase();
        tc.setId("TC-C" + String.format("%02d", s.getCustomCases().size() + 1));
        tc.setTitle("");
        tc.setPriority("Medium");
        tc.setSeverity("Medium");
        tc.setTestType("Functional");
        tc.setSteps("");
        tc.setExpected("");
        tc.setData("");
        tc.setStoryKey(storyKey);
        s.getCustomCases().add(tc);
        return "redirect:/#step3";
    }

    // --- Step 3: Create in Jira ---
    @PostMapping("/create-jira")
    public String createJira(@RequestParam Map<String, String> params, RedirectAttributes ra, HttpSession session) {
        AppSession s = getSession(session);
        applyUpdatesFromParams(s, params);
        List<TestCase> visible = s.getVisibleTestCases();
        log.info("Create Jira sub-tasks: {} test cases", visible.size());
        if (visible.isEmpty()) {
            ra.addFlashAttribute("error", "No test cases to create.");
            return "redirect:/";
        }
        Map<String, List<TestCase>> byStory = new LinkedHashMap<>();
        for (TestCase tc : visible) {
            String sk = tc.getStoryKey() != null ? tc.getStoryKey() : s.getStoryKey();
            byStory.computeIfAbsent(sk, k -> new ArrayList<>()).add(tc);
        }
        List<String> created = new ArrayList<>();
        for (Map.Entry<String, List<TestCase>> e : byStory.entrySet()) {
            try {
                String jk = geminiService.createJiraSubtaskForStory(e.getKey(), e.getValue());
                if (jk != null) created.add(e.getKey() + " → " + jk);
            } catch (Exception ex) {
                log.error("Failed to create Jira sub-task for {}: {}", e.getKey(), ex.getMessage(), ex);
            }
        }
        log.info("Created {} Jira sub-task(s): {}", created.size(), created);
        ra.addFlashAttribute("message", "Created " + created.size() + " sub-task(s): " + String.join(", ", created));
        return "redirect:/#step3";
    }

    // --- Step 3: Update session from params then redirect to export ---
    @PostMapping("/update-and-export/excel")
    public String updateAndExportExcel(@RequestParam Map<String, String> params, HttpSession session) {
        applyUpdatesFromParams(getSession(session), params);
        return "redirect:/export/excel";
    }
    @PostMapping("/update-and-export/json")
    public String updateAndExportJson(@RequestParam Map<String, String> params, HttpSession session) {
        applyUpdatesFromParams(getSession(session), params);
        return "redirect:/export/json";
    }
    @PostMapping("/update-and-export/allure")
    public String updateAndExportAllure(@RequestParam Map<String, String> params, HttpSession session) {
        applyUpdatesFromParams(getSession(session), params);
        return "redirect:/export/allure";
    }

    // --- Export Excel ---
    @GetMapping("/export/excel")
    public ResponseEntity<ByteArrayResource> exportExcel(HttpSession session) {
        AppSession s = getSession(session);
        List<TestCase> visible = s.getVisibleTestCases();
        String storyKey = s.getStoryKey() != null ? s.getStoryKey() : "test-cases";
        try {
            byte[] data = exportService.toExcel(visible, storyKey);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=test-cases-" + storyKey + ".xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new ByteArrayResource(data));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // --- Export JSON ---
    @GetMapping(value = "/export/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportJson(HttpSession session) {
        AppSession s = getSession(session);
        List<TestCase> visible = s.getVisibleTestCases();
        String storyKey = s.getStoryKey() != null ? s.getStoryKey() : "test-cases";
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (TestCase tc : visible) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ID", tc.getId());
                m.put("Test Case Name", tc.getTitle());
                m.put("Priority", tc.getPriority());
                m.put("Severity", tc.getSeverity());
                m.put("Test Type", tc.getTestType());
                m.put("Steps", tc.getSteps());
                m.put("Expected Result", tc.getExpected());
                m.put("Test Data", tc.getData());
                m.put("story_key", tc.getStoryKey());
                list.add(m);
            }
            String jsonStr = json.writerWithDefaultPrettyPrinter().writeValueAsString(list);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=test-cases-" + storyKey + ".json")
                .body(jsonStr);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // --- Export Allure CSV ---
    @GetMapping(value = "/export/allure", produces = "text/csv")
    public ResponseEntity<String> exportAllure(HttpSession session) {
        AppSession s = getSession(session);
        List<TestCase> visible = s.getVisibleTestCases();
        String storyKey = s.getStoryKey() != null ? s.getStoryKey() : "test-cases";
        String csv = exportService.toAllureCsv(visible, storyKey);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=test-cases-" + storyKey + "-allure.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv);
    }

    // --- Start Over ---
    @PostMapping("/reset")
    public String reset(RedirectAttributes ra, HttpSession session) {
        getSession(session).reset();
        ra.addFlashAttribute("message", "Started over.");
        return "redirect:/";
    }

    // --- Step navigation ---
    @PostMapping("/step/{step}")
    public String setStep(@PathVariable int step, HttpSession session) {
        getSession(session).setActiveStep(step);
        return "redirect:/";
    }
}
