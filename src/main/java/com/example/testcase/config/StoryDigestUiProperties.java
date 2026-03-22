package com.example.testcase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls which story digest blocks appear on Step 2 and which fields are passed into the test-generation prompt.
 * Default: Jira title/summary, description, and acceptance criteria only (Gemini/MCP noise and extras hidden).
 */
@ConfigurationProperties(prefix = "story.digest.ui")
public class StoryDigestUiProperties {

    /** Gray intro line under "Story digest" heading. */
    private boolean showDigestIntro = false;

    private boolean showStoryType = false;
    private boolean showKeyPointsForTesting = false;
    private boolean showAcceptanceCriteria = true;
    private boolean showEdgeCasesAndRisks = false;
    private boolean showExamplesOrScenarios = false;
    private boolean showAttachments = false;

    public boolean isShowDigestIntro() {
        return showDigestIntro;
    }

    public void setShowDigestIntro(boolean showDigestIntro) {
        this.showDigestIntro = showDigestIntro;
    }

    public boolean isShowStoryType() {
        return showStoryType;
    }

    public void setShowStoryType(boolean showStoryType) {
        this.showStoryType = showStoryType;
    }

    public boolean isShowKeyPointsForTesting() {
        return showKeyPointsForTesting;
    }

    public void setShowKeyPointsForTesting(boolean showKeyPointsForTesting) {
        this.showKeyPointsForTesting = showKeyPointsForTesting;
    }

    public boolean isShowAcceptanceCriteria() {
        return showAcceptanceCriteria;
    }

    public void setShowAcceptanceCriteria(boolean showAcceptanceCriteria) {
        this.showAcceptanceCriteria = showAcceptanceCriteria;
    }

    public boolean isShowEdgeCasesAndRisks() {
        return showEdgeCasesAndRisks;
    }

    public void setShowEdgeCasesAndRisks(boolean showEdgeCasesAndRisks) {
        this.showEdgeCasesAndRisks = showEdgeCasesAndRisks;
    }

    public boolean isShowExamplesOrScenarios() {
        return showExamplesOrScenarios;
    }

    public void setShowExamplesOrScenarios(boolean showExamplesOrScenarios) {
        this.showExamplesOrScenarios = showExamplesOrScenarios;
    }

    public boolean isShowAttachments() {
        return showAttachments;
    }

    public void setShowAttachments(boolean showAttachments) {
        this.showAttachments = showAttachments;
    }
}
