/**
 * Role : definir les donnees echangees ou calculees pour le modele TestExecutionSummary.
 * Contenu : definition de TestExecutionSummary et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.model;

import java.time.LocalDateTime;
import java.util.List;

/** Vue consolidée d'une exécution de tests (toutes suites confondues) + couverture. */
public class TestExecutionSummary {

    private int totalTests;
    private int totalFailures;
    private int totalErrors;
    private int totalSkipped;
    private double successRatePercent;
    private List<SuiteResult> suites;

    private Long runId;
    private LocalDateTime executedAt;
    private String trigger;
    private String branch;
    private String commitSha;
    private Double lineCoverage;
    private Double branchCoverage;
    private double durationSeconds;
    /** Application testée (multi-applications). */
    private String application;

    public TestExecutionSummary() {
    }

    public TestExecutionSummary(int totalTests, int totalFailures, int totalErrors, int totalSkipped,
                                double successRatePercent, List<SuiteResult> suites) {
        this.totalTests = totalTests;
        this.totalFailures = totalFailures;
        this.totalErrors = totalErrors;
        this.totalSkipped = totalSkipped;
        this.successRatePercent = successRatePercent;
        this.suites = suites;
    }

    /** Calcule les totaux et le taux de réussite à partir d'une liste de suites. */
    public static TestExecutionSummary fromSuites(List<SuiteResult> suites) {
        int total = suites.stream().mapToInt(SuiteResult::getTests).sum();
        int failures = suites.stream().mapToInt(SuiteResult::getFailures).sum();
        int errors = suites.stream().mapToInt(SuiteResult::getErrors).sum();
        int skipped = suites.stream().mapToInt(SuiteResult::getSkipped).sum();
        int run = total - skipped;
        double rate = run == 0 ? 0.0 : 100.0 * (run - failures - errors) / run;
        TestExecutionSummary summary = new TestExecutionSummary(total, failures, errors, skipped,
                Math.round(rate * 100.0) / 100.0, suites);
        summary.setDurationSeconds(Math.round(
                suites.stream().mapToDouble(SuiteResult::getTimeSeconds).sum() * 1000.0) / 1000.0);
        return summary;
    }

    public int getTotalTests() { return totalTests; }
    public void setTotalTests(int totalTests) { this.totalTests = totalTests; }
    public int getTotalFailures() { return totalFailures; }
    public void setTotalFailures(int totalFailures) { this.totalFailures = totalFailures; }
    public int getTotalErrors() { return totalErrors; }
    public void setTotalErrors(int totalErrors) { this.totalErrors = totalErrors; }
    public int getTotalSkipped() { return totalSkipped; }
    public void setTotalSkipped(int totalSkipped) { this.totalSkipped = totalSkipped; }
    public double getSuccessRatePercent() { return successRatePercent; }
    public void setSuccessRatePercent(double successRatePercent) { this.successRatePercent = successRatePercent; }
    public List<SuiteResult> getSuites() { return suites; }
    public void setSuites(List<SuiteResult> suites) { this.suites = suites; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public Double getLineCoverage() { return lineCoverage; }
    public void setLineCoverage(Double lineCoverage) { this.lineCoverage = lineCoverage; }
    public Double getBranchCoverage() { return branchCoverage; }
    public void setBranchCoverage(Double branchCoverage) { this.branchCoverage = branchCoverage; }
    public double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(double durationSeconds) { this.durationSeconds = durationSeconds; }
    public String getApplication() { return application; }
    public void setApplication(String application) { this.application = application; }
}
