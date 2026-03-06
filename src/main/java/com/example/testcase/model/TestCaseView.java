package com.example.testcase.model;

/** Wrapper for displaying test case with its index for reject/update actions. */
public class TestCaseView {
    private final TestCase testCase;
    private final int testCaseIndex;  // index in testCases list, -1 if custom
    private final boolean custom;

    public TestCaseView(TestCase testCase, int testCaseIndex, boolean custom) {
        this.testCase = testCase;
        this.testCaseIndex = testCaseIndex;
        this.custom = custom;
    }
    public TestCase getTestCase() { return testCase; }
    public int getTestCaseIndex() { return testCaseIndex; }
    public boolean isCustom() { return custom; }
}
