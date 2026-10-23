/**
 * Role : persister une campagne de validation portant sur une ou plusieurs applications.
 * Contenu : definition de ValidationCampaignRecord et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Campagne : une demande de validation sur N applications, produisant N rapports distincts. */
@Entity
@Table(name = "validation_campaign")
public class ValidationCampaignRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 200)
    private String label;

    /** LATEST_RESULTS (dernières preuves connues) ou RUN_TESTS (exécution des tests avant validation). */
    @Column(length = 20)
    private String mode;

    private boolean useLlm;

    /** RUNNING, COMPLETED, PARTIAL (au moins une application en erreur), FAILED. */
    @Column(length = 20)
    private String status;

    @Column(length = 2000)
    private String applicationCodes;

    @Column(length = 80)
    private String triggeredBy;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private int total;
    private int validated;
    private int reserves;
    private int rejected;
    private int inconclusive;
    private int errors;
    private Double averageScore;

    @Column(length = 64)
    private String jobId;

    public ValidationCampaignRecord() {
    }

    public Long getId() { return id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = VulnerabilityRecord.truncate(label, 200); }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isUseLlm() { return useLlm; }
    public void setUseLlm(boolean useLlm) { this.useLlm = useLlm; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getApplicationCodes() { return applicationCodes; }
    public void setApplicationCodes(String applicationCodes) { this.applicationCodes = VulnerabilityRecord.truncate(applicationCodes, 2000); }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getValidated() { return validated; }
    public void setValidated(int validated) { this.validated = validated; }
    public int getReserves() { return reserves; }
    public void setReserves(int reserves) { this.reserves = reserves; }
    public int getRejected() { return rejected; }
    public void setRejected(int rejected) { this.rejected = rejected; }
    public int getInconclusive() { return inconclusive; }
    public void setInconclusive(int inconclusive) { this.inconclusive = inconclusive; }
    public int getErrors() { return errors; }
    public void setErrors(int errors) { this.errors = errors; }
    public Double getAverageScore() { return averageScore; }
    public void setAverageScore(Double averageScore) { this.averageScore = averageScore; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
}
