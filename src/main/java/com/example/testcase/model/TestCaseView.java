package com.example.testcase.model;

/** Wrapper for displaying test case with its index for reject/approve/remove actions. */
public class TestCaseView {
    private final TestCase testCase;
    private final int testCaseIndex;  // index in testCases list, -1 if custom
    private final int customIndex;    // index in customCases list, -1 if not custom
    private final boolean custom;

    public TestCaseView(TestCase testCase, int testCaseIndex, boolean custom) {
        this(testCase, testCaseIndex, -1, custom);
    }
    public TestCaseView(TestCase testCase, int testCaseIndex, int customIndex, boolean custom) {
        this.testCase = testCase;
        this.testCaseIndex = testCaseIndex;
        this.customIndex = customIndex;
        this.custom = custom;
    }
    public TestCase getTestCase() { return testCase; }
    public int getTestCaseIndex() { return testCaseIndex; }
    public int getCustomIndex() { return customIndex; }
    public boolean isCustom() { return custom; }
}
