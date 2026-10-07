package com.vermeg.assistant.model;

import java.util.List;

/** Résultat consolidé d'une suite de tests (une classe JUnit) issu d'un rapport Surefire. */
public class SuiteResult {

    private String suiteName;
    private int tests;
    private int failures;
    private int errors;
    private int skipped;
    private double timeSeconds;
    private List<TestCaseResult> testCases;

    public SuiteResult() {
    }

    public SuiteResult(String suiteName, int tests, int failures, int errors, int skipped,
                       double timeSeconds, List<TestCaseResult> testCases) {
        this.suiteName = suiteName;
        this.tests = tests;
        this.failures = failures;
        this.errors = errors;
        this.skipped = skipped;
        this.timeSeconds = timeSeconds;
        this.testCases = testCases;
    }

    public String getSuiteName() { return suiteName; }
    public void setSuiteName(String suiteName) { this.suiteName = suiteName; }
    public int getTests() { return tests; }
    public void setTests(int tests) { this.tests = tests; }
    public int getFailures() { return failures; }
    public void setFailures(int failures) { this.failures = failures; }
    public int getErrors() { return errors; }
    public void setErrors(int errors) { this.errors = errors; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
    public double getTimeSeconds() { return timeSeconds; }
    public void setTimeSeconds(double timeSeconds) { this.timeSeconds = timeSeconds; }
    public List<TestCaseResult> getTestCases() { return testCases; }
    public void setTestCases(List<TestCaseResult> testCases) { this.testCases = testCases; }
}
