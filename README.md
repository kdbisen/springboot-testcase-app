# AI Test Case Generator — Spring Boot + Thymeleaf

Same functionality as the Streamlit app, but runs as a **standard Java web app**. No Streamlit/Python required—ideal for environments where Streamlit is blocked (e.g. corporate laptops).

## Features

- **Jira Integration** — Search, fetch stories, create sub-tasks via Gemini CLI + Atlassian MCP
- **3-Step Workflow** — Fetch Story → Review & Generate → Refine & Export
- **AI Test Cases** — Generated from Jira stories with Priority, Severity, Test Type
- **Export** — Excel, JSON, Allure TestOps CSV
- **Create in Jira** — One sub-task per story with all test cases
- **Offline Cache** — Story details cached locally

## Prerequisites

1. **Java 17+**
2. **Maven 3.6+**
3. **Gemini CLI** in PATH — https://github.com/google-gemini/gemini-cli
4. **Atlassian MCP** — `gemini extensions install https://github.com/atlassian/atlassian-mcp-server`

## Build & Run

```bash
cd springboot-testcase-app
mvn spring-boot:run
```

Open http://localhost:8080

## Package as JAR (for deployment)

```bash
mvn clean package
java -jar target/testcase-generator-1.0.0.jar
```

## Configuration

Edit `src/main/resources/application.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| server.port | 8080 | HTTP port |
| gemini.cli.timeout | 300 | Timeout in seconds |
| gemini.approval.mode | yolo | Required for MCP tools |
| story.cache.enabled | false | Offline story cache (set true to enable) |

## Project Structure

```
springboot-testcase-app/
├── pom.xml
├── src/main/java/com/example/testcase/
│   ├── TestCaseApplication.java
│   ├── controller/TestCaseController.java
│   ├── service/
│   │   ├── GeminiCliService.java    # Same logic as Python gemini_cli_client.py
│   │   ├── StoryCacheService.java
│   │   ├── TableParserService.java
│   │   └── ExportService.java
│   ├── model/
│   └── util/JiraKeyUtil.java
└── src/main/resources/
    ├── application.properties
    └── templates/index.html
```

## Comparison with Streamlit Version

| Aspect | Streamlit | Spring Boot + Thymeleaf |
|--------|-----------|--------------------------|
| Runtime | Python + Streamlit | Java 17 + embedded Tomcat |
| UI | Streamlit components | Thymeleaf + HTML/CSS |
| Deployment | `streamlit run` or exe | JAR, WAR, or `java -jar` |
| Corporate-friendly | Often blocked | Standard Java web app |

Internal logic (Gemini CLI calls, prompts, parsing) is identical.
