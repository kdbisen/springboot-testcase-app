package com.example.testcase.service;

public class JiraApiException extends Exception {

    private final int statusCode;

    public JiraApiException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public JiraApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public JiraApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
