package com.example.testcase.controller;

import com.example.testcase.model.AppSession;
import com.example.testcase.model.JiraSearchResult;
import com.example.testcase.model.StoryDetails;
import com.example.testcase.model.SubtaskCreateResult;
import com.example.testcase.model.TestCase;
import com.example.testcase.model.TestCaseView;
import com.example.testcase.config.StoryDigestUiProperties;
import com.example.testcase.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
public class TestCaseController {

    private static final Logger log = LoggerFactory.getLogger(TestCaseController.class);
    private final GeminiCliService geminiService;
    private final JiraStoryService jiraStoryService;
    private final StoryCacheService cacheService;
    private final TableParserService tableParser;
    private final ExportService exportService;
    private final StoryDigestUiProperties storyDigestUi;
    private final ObjectMapper json = new ObjectMapper();

    /** Concurrent MCP/REST fetches when multiple keys (1 = sequential only). */
    @Value("${story.fetch.parallelism:3}")
    private int storyFetchParallelism;

    public TestCaseController(GeminiCliService geminiService, JiraStoryService jiraStoryService,
                               StoryCacheService cacheService,
                               TableParserService tableParser, ExportService exportService,
                               StoryDigestUiProperties storyDigestUi) {
        this.geminiService = geminiService;
        this.jiraStoryService = jiraStoryService;
        this.cacheService = cacheService;
        this.tableParser = tableParser;
        this.exportService = exportService;
        this.storyDigestUi = storyDigestUi;
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

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> health() {
        return geminiService.checkGeminiAvailability();
    }

    @GetMapping("/")
    public String index(@RequestParam(required = false) Boolean recheck, Model model, HttpSession session) {
        AppSession s = getSession(session);
        if (Boolean.TRUE.equals(recheck)) session.removeAttribute("geminiStatus");
        @SuppressWarnings("unchecked")
        Map<String, Object> cached = (Map<String, Object>) session.getAttribute("geminiStatus");
        if (cached == null) {
            cached = geminiService.checkGeminiAvailability();
            session.setAttribute("geminiStatus", cached);
        }
        model.addAttribute("geminiStatus", cached);
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
        model.addAttribute("digestUi", storyDigestUi);
        model.addAttribute("storyMissingAcceptanceCriteria", computeAnyStoryMissingAcceptanceCriteria(s, storyKeys));
        return "index";
    }

    private boolean computeAnyStoryMissingAcceptanceCriteria(AppSession s, List<String> storyKeys) {
        if (!s.hasStory() || storyKeys == null) return false;
        for (String sk : storyKeys) {
            StoryDetails sd = s.getStoryDetailsBatch() != null ? s.getStoryDetailsBatch().get(sk) : null;
            if (sd == null) sd = s.getStoryDetails();
            if (sd != null && (sd.getAcceptanceCriteria() == null || sd.getAcceptanceCriteria().isEmpty())) {
                return true;
            }
        }
        return false;
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
            List<JiraSearchResult> results = jiraStoryService.searchIssues(query.trim(), 10);
            log.info("Search returned {} issues", results.size());
            s.setJiraSearchResults(results);
            s.setJiraSearchDone(true);
            ra.addFlashAttribute("message", "Found " + results.size() + " issues.");
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage(), e);
            ra.addFlashAttribute("error", GeminiCliService.userFriendlyMessage(e));
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
            Map<String, StoryDetails> batch = fetchStoriesForKeys(keysToFetch);
            s.setStoryKey(keysToFetch.get(0));
            s.setStoryKeys(keysToFetch);
            s.setStoryDetails(batch.get(keysToFetch.get(0)));
            s.setStoryDetailsBatch(batch);
            s.setActiveStep(1);
            ra.addFlashAttribute("message", "Fetched " + keysToFetch.size() + " story/stories.");
        } catch (Exception e) {
            log.error("Fetch failed for keys {}: {}", keysToFetch, e.getMessage(), e);
            ra.addFlashAttribute("error", GeminiCliService.userFriendlyMessage(e));
        }
        return "redirect:/";
    }

    /**
     * Loads stories from cache or fetches; uses a bounded pool when multiple keys need a fresh MCP/REST fetch.
     */
    private Map<String, StoryDetails> fetchStoriesForKeys(List<String> keysToFetch) throws Exception {
        Map<String, StoryDetails> byKey = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String key : keysToFetch) {
            StoryDetails cached = cacheService.getCachedStory(key);
            if (cached != null) {
                byKey.put(key, cached);
                log.info("Using cached story {}", key);
            } else {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return orderBatch(keysToFetch, byKey);
        }
        if (missing.size() == 1 || storyFetchParallelism <= 1) {
            for (String key : missing) {
                log.debug("Fetching story {}", key);
                StoryDetails details = jiraStoryService.fetchStoryDetails(key);
                cacheService.cacheStory(key, details);
                byKey.put(key, details);
                log.info("Fetched story {} (REST API or Gemini per configuration)", key);
            }
        } else {
            int poolSize = Math.min(Math.max(1, storyFetchParallelism), missing.size());
            ExecutorService pool = Executors.newFixedThreadPool(poolSize);
            try {
                Map<String, Future<StoryDetails>> futures = new LinkedHashMap<>();
                for (String key : missing) {
                    futures.put(key, pool.submit(() -> jiraStoryService.fetchStoryDetails(key)));
                }
                for (String key : missing) {
                    try {
                        StoryDetails details = futures.get(key).get();
                        cacheService.cacheStory(key, details);
                        byKey.put(key, details);
                        log.info("Fetched story {} (parallel fetch)", key);
                    } catch (ExecutionException ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        if (c instanceof Exception) throw (Exception) c;
                        throw new RuntimeException(c);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                }
            } finally {
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(120, TimeUnit.SECONDS)) {
                        pool.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
        return orderBatch(keysToFetch, byKey);
    }

    private static Map<String, StoryDetails> orderBatch(List<String> keysOrder, Map<String, StoryDetails> byKey) {
        Map<String, StoryDetails> batch = new LinkedHashMap<>();
        for (String key : keysOrder) {
            batch.put(key, byKey.get(key));
        }
        return batch;
    }

    // --- Step 2: Generate Test Cases ---
    @PostMapping("/generate")
    public String generate(@RequestParam(defaultValue = "true") boolean includeNegative,
                           @RequestParam(defaultValue = "true") boolean includeBoundary,
                           @RequestParam(required = false) String customInstructions,
                           @RequestParam(required = false) String manualAcceptanceCriteria,
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
            boolean anyStoryHadNoAc = false;
            for (String sk : storyKeys) {
                StoryDetails det = s.getStoryDetailsBatch().get(sk);
                if (det == null) det = s.getStoryDetails();
                StoryDetails forGen = det.copy();
                if (storyKeys.size() <= 1 && manualAcceptanceCriteria != null && !manualAcceptanceCriteria.isBlank()) {
                    for (String line : manualAcceptanceCriteria.split("\r?\n")) {
                        String t = line.trim();
                        if (!t.isEmpty()) forGen.getAcceptanceCriteria().add(t);
                    }
                }
                if (forGen.getAcceptanceCriteria() == null || forGen.getAcceptanceCriteria().isEmpty()) {
                    anyStoryHadNoAc = true;
                }
                String table = geminiService.generateTestCases(sk, forGen, includeNegative, includeBoundary, customInstructions);
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
            if (anyStoryHadNoAc) {
                ra.addFlashAttribute("warning",
                    "No acceptance criteria were present for at least one story. Tests were generated from the title and description only. "
                        + "Add acceptance criteria in Jira, or paste them into the optional field on Step 2 before generating.");
            }
        } catch (Exception e) {
            log.error("Generate failed: {}", e.getMessage(), e);
            ra.addFlashAttribute("error", GeminiCliService.userFriendlyMessage(e));
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
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<TestCase>> e : byStory.entrySet()) {
            SubtaskCreateResult r = geminiService.createJiraSubtaskForStory(e.getKey(), e.getValue());
            if (r.success()) {
                created.add(e.getKey() + " → " + r.issueKey());
            } else {
                failures.add(e.getKey() + ": " + r.errorMessage());
                log.warn("Jira sub-task not created for {}: {}", e.getKey(), r.errorMessage());
            }
        }
        log.info("Created {} Jira sub-task(s): {}; failures: {}", created.size(), created, failures);
        if (!created.isEmpty()) {
            ra.addFlashAttribute("message", "Created " + created.size() + " sub-task(s): " + String.join(", ", created));
        }
        if (!failures.isEmpty()) {
            if (created.isEmpty()) {
                ra.addFlashAttribute("error", "No Jira sub-tasks were created. " + String.join("; ", failures));
            } else {
                ra.addFlashAttribute("warning", "Some sub-tasks failed (" + failures.size() + "): " + String.join("; ", failures));
            }
        }
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
