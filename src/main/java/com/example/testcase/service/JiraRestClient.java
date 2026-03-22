package com.example.testcase.service;

import com.example.testcase.config.JiraProperties;
import com.example.testcase.model.JiraSearchResult;
import com.example.testcase.model.StoryDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Component
public class JiraRestClient {

    private static final Logger log = LoggerFactory.getLogger(JiraRestClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JiraProperties jiraProperties;
    private RestClient restClient;

    public JiraRestClient(JiraProperties jiraProperties) {
        this.jiraProperties = jiraProperties;
    }

    @PostConstruct
    void init() {
        if (!jiraProperties.isConfigured()) {
            log.info("Jira REST API disabled (set jira.base-url, jira.email, jira.api-token to enable).");
            return;
        }
        String base = jiraProperties.getBaseUrl().replaceAll("/$", "");
        String raw = jiraProperties.getEmail() + ":" + jiraProperties.getApiToken();
        String authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        HttpClient jdk = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(jiraProperties.getConnectTimeoutSeconds()))
            .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(jdk);
        rf.setReadTimeout(Duration.ofSeconds(jiraProperties.getReadTimeoutSeconds()));

        this.restClient = RestClient.builder()
            .baseUrl(base)
            .requestFactory(rf)
            .defaultHeader(HttpHeaders.AUTHORIZATION, authorizationHeader)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
        log.info("Jira REST client initialized for base URL {}", base);
    }

    public boolean isAvailable() {
        return restClient != null;
    }

    public StoryDetails fetchIssue(String issueKey) throws JiraApiException {
        ensureAvailable();
        String key = issueKey.trim().toUpperCase(Locale.ROOT);
        try {
            String body = executeWithRetry(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/rest/api/3/issue/{key}")
                    .queryParam("fields", "summary,description,issuetype,labels,components,priority,attachment,status")
                    .build(key))
                .retrieve()
                .body(String.class));
            JsonNode issue = JSON.readTree(body);
            return JiraIssueMapper.fromIssueJson(issue);
        } catch (RestClientException e) {
            throw new JiraApiException("Jira request failed: " + e.getMessage(), e);
        } catch (JiraApiException e) {
            throw e;
        } catch (Exception e) {
            throw new JiraApiException("Failed to parse Jira issue: " + e.getMessage(), e);
        }
    }

    public List<JiraSearchResult> searchIssues(String query, int maxResults) throws JiraApiException {
        ensureAvailable();
        int cap = Math.min(Math.max(1, maxResults), jiraProperties.getMaxSearchResults());
        String jql = buildSearchJql(query.trim());
        ObjectNode payload = JSON.createObjectNode();
        payload.put("jql", jql);
        payload.put("maxResults", cap);
        payload.putArray("fields").add("summary").add("issuetype").add("key");

        try {
            String body = executeWithRetry(() -> restClient.post()
                .uri("/rest/api/3/search")
                .body(payload.toString())
                .retrieve()
                .body(String.class));
            JsonNode root = JSON.readTree(body);
            JsonNode issues = root.get("issues");
            List<JiraSearchResult> out = new ArrayList<>();
            if (issues != null && issues.isArray()) {
                for (JsonNode issue : issues) {
                    String k = issue.path("key").asText("").toUpperCase(Locale.ROOT);
                    String summary = issue.path("fields").path("summary").asText("");
                    if (!k.isEmpty()) {
                        out.add(new JiraSearchResult(k, summary));
                    }
                }
            }
            return out;
        } catch (RestClientException e) {
            throw new JiraApiException("Jira search failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new JiraApiException("Failed to parse Jira search: " + e.getMessage(), e);
        }
    }

    static String buildSearchJql(String query) {
        if (query.matches("[A-Za-z][A-Za-z0-9]+-\\d+")) {
            return "key = \"" + query.toUpperCase(Locale.ROOT) + "\" ORDER BY updated DESC";
        }
        String escaped = query.replace("\\", "\\\\").replace("\"", "\\\"");
        return "text ~ \"" + escaped + "\" ORDER BY updated DESC";
    }

    private void ensureAvailable() throws JiraApiException {
        if (restClient == null) {
            throw new JiraApiException("Jira REST API is not configured. Set jira.base-url, jira.email, and jira.api-token.");
        }
    }

    private String executeWithRetry(RestClientSupplier supplier) throws JiraApiException {
        int max = Math.max(1, jiraProperties.getRetryMaxAttempts());
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                return supplier.get();
            } catch (RestClientException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (attempt < max && isTransient(msg, e)) {
                    log.warn("Jira request attempt {} failed ({}), retrying in {}ms", attempt, msg, jiraProperties.getRetryDelayMs());
                    sleepQuietly();
                    continue;
                }
                throw mapRestException(e);
            }
        }
        throw new JiraApiException("Jira request failed");
    }

    private static boolean isTransient(String msg, RestClientException e) {
        String m = (msg + " " + e).toLowerCase(Locale.ROOT);
        return m.contains("429") || m.contains("503") || m.contains("502") || m.contains("timeout")
            || m.contains("temporarily") || m.contains("rate");
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(Math.max(100L, jiraProperties.getRetryDelayMs()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private JiraApiException mapRestException(RestClientException e) {
        String m = e.getMessage() != null ? e.getMessage() : e.toString();
        if (m.contains("401") || m.toLowerCase(Locale.ROOT).contains("unauthorized")) {
            return new JiraApiException(401, "Jira authentication failed. Check jira.email and jira.api-token.");
        }
        if (m.contains("403")) {
            return new JiraApiException(403, "Jira forbidden. Check API token permissions for the project.");
        }
        if (m.contains("404")) {
            return new JiraApiException(404, "Jira issue or resource not found.");
        }
        return new JiraApiException(m);
    }

    @FunctionalInterface
    private interface RestClientSupplier {
        String get() throws RestClientException;
    }
}
