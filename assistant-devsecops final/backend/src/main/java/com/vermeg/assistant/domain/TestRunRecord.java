/**
 * Role : representer et persister les donnees metier de TestRun dans la base de donnees.
 * Contenu : definition de TestRunRecord et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Historique des exécutions de tests. Le détail des suites est stocké en JSON
 * (colonne TEXT) : il n'est jamais requêté en SQL, seulement relu pour
 * l'affichage, la corrélation et la détection de tests instables (flaky).
 */
@Entity
@Table(name = "test_run")
public class TestRunRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime executedAt;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;

    @Column(name = "run_trigger", length = 20)
    private String trigger;

    @Column(length = 80)
    private String triggeredBy;

    @Column(length = 200)
    private String branch;

    @Column(length = 80)
    private String commitSha;

    @Column(length = 200)
    private String pipelineId;

    private int totalTests;
    private int failures;
    private int errors;
    private int skipped;
    private double successRate;
    private Double lineCoverage;
    private Double branchCoverage;
    private double durationSeconds;
    private Integer exitCode;

    @Column(length = 1_000_000) // varchar portable H2 / PostgreSQL
    private String suitesJson;

    @Column(length = 4000)
    private String outputTail;

    public TestRunRecord() {
    }

    public Long getId() { return id; }
    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }
    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = VulnerabilityRecord.truncate(branch, 200); }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = VulnerabilityRecord.truncate(commitSha, 80); }
    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = VulnerabilityRecord.truncate(pipelineId, 200); }
    public int getTotalTests() { return totalTests; }
    public void setTotalTests(int totalTests) { this.totalTests = totalTests; }
    public int getFailures() { return failures; }
    public void setFailures(int failures) { this.failures = failures; }
    public int getErrors() { return errors; }
    public void setErrors(int errors) { this.errors = errors; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double successRate) { this.successRate = successRate; }
    public Double getLineCoverage() { return lineCoverage; }
    public void setLineCoverage(Double lineCoverage) { this.lineCoverage = lineCoverage; }
    public Double getBranchCoverage() { return branchCoverage; }
    public void setBranchCoverage(Double branchCoverage) { this.branchCoverage = branchCoverage; }
    public double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(double durationSeconds) { this.durationSeconds = durationSeconds; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public String getSuitesJson() { return suitesJson; }
    public void setSuitesJson(String suitesJson) { this.suitesJson = suitesJson; }
    public String getOutputTail() { return outputTail; }
    public void setOutputTail(String outputTail) { this.outputTail = VulnerabilityRecord.truncate(outputTail, 4000); }
}
