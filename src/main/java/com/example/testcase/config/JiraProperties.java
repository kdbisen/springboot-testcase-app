package com.example.testcase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jira Cloud REST API (email + API token). When all required fields are set, fetch and search use the API instead of Gemini.
 */
@ConfigurationProperties(prefix = "jira")
public class JiraProperties {

    /** Base URL, e.g. https://your-domain.atlassian.net (no trailing slash). */
    private String baseUrl = "";

    /** Atlassian account email (Jira Cloud). */
    private String email = "";

    /** API token from https://id.atlassian.com/manage-profile/security/api-tokens */
    private String apiToken = "";

    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 60;
    private int maxSearchResults = 25;

    /** Retries for 429 / 5xx (in addition to first attempt). */
    private int retryMaxAttempts = 2;
    private long retryDelayMs = 800L;

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
            && email != null && !email.isBlank()
            && apiToken != null && !apiToken.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl != null ? baseUrl.trim() : "";
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email != null ? email.trim() : "";
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken != null ? apiToken : "";
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getMaxSearchResults() {
        return maxSearchResults;
    }

    public void setMaxSearchResults(int maxSearchResults) {
        this.maxSearchResults = maxSearchResults;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }
}
