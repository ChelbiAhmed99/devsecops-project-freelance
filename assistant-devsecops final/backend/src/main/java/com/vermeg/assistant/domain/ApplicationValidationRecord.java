/**
 * Role : persister le resultat de validation d'une application et son rapport independant.
 * Contenu : definition de ApplicationValidationRecord et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.domain;

import com.vermeg.assistant.model.ValidationModels;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Résultat de validation d'UNE application dans une campagne : décision automatique,
 * indicateurs clés (pour les tableaux de bord), rapport complet (JSON structuré + Markdown
 * + HTML), et décision humaine éventuelle (confirmation ou correction, toujours tracée).
 */
@Entity
@Table(name = "application_validation", indexes = {
        @Index(name = "idx_appval_campaign", columnList = "campaign_id"),
        @Index(name = "idx_appval_app", columnList = "application_code")
})
public class ApplicationValidationRecord {

    public static final int MAX_REPORT = 1_000_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "application_code", length = 60)
    private String applicationCode;

    @Column(length = 120)
    private String applicationName;

    @Column(length = 20)
    private String criticality;

    /** PENDING, RUNNING, DONE, ERROR. */
    @Column(length = 20)
    private String state;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ValidationModels.Verdict verdict;

    /** Décision après revue humaine (sinon identique à la décision automatique). */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ValidationModels.Verdict finalVerdict;

    private Double score;
    private Double baseScore;
    private Double llmAdjustment;
    private Double confidence;
    private boolean reviewRequired;

    private Integer testsTotal;
    private Integer testsFailed;
    private Double successRate;
    private Double lineCoverage;
    private Long openCritical;
    private Long openHigh;
    private Integer criticalAnomalies;
    private Integer incidents;
    private Long logErrors;

    @Column(length = 20)
    private String llmStatus;
    @Column(length = 20)
    private String llmOpinion;
    @Column(length = 30)
    private String llmProvider;

    @Column(length = 2000)
    private String error;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Column(length = MAX_REPORT)
    private String reportJson;

    @Column(length = MAX_REPORT)
    private String reportMarkdown;

    @Column(length = MAX_REPORT)
    private String reportHtml;

    // --- Décision humaine
    /** CONFIRMED ou OVERRIDDEN. */
    @Column(length = 20)
    private String humanDecision;
    @Column(length = 80)
    private String decidedBy;
    private LocalDateTime decidedAt;
    @Column(length = 2000)
    private String decisionComment;

    public ApplicationValidationRecord() {
    }

    public Long getId() { return id; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    public String getApplicationCode() { return applicationCode; }
    public void setApplicationCode(String applicationCode) { this.applicationCode = applicationCode; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = VulnerabilityRecord.truncate(applicationName, 120); }
    public String getCriticality() { return criticality; }
    public void setCriticality(String criticality) { this.criticality = criticality; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public ValidationModels.Verdict getVerdict() { return verdict; }
    public void setVerdict(ValidationModels.Verdict verdict) { this.verdict = verdict; }
    public ValidationModels.Verdict getFinalVerdict() { return finalVerdict; }
    public void setFinalVerdict(ValidationModels.Verdict finalVerdict) { this.finalVerdict = finalVerdict; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Double getBaseScore() { return baseScore; }
    public void setBaseScore(Double baseScore) { this.baseScore = baseScore; }
    public Double getLlmAdjustment() { return llmAdjustment; }
    public void setLlmAdjustment(Double llmAdjustment) { this.llmAdjustment = llmAdjustment; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public boolean isReviewRequired() { return reviewRequired; }
    public void setReviewRequired(boolean reviewRequired) { this.reviewRequired = reviewRequired; }
    public Integer getTestsTotal() { return testsTotal; }
    public void setTestsTotal(Integer testsTotal) { this.testsTotal = testsTotal; }
    public Integer getTestsFailed() { return testsFailed; }
    public void setTestsFailed(Integer testsFailed) { this.testsFailed = testsFailed; }
    public Double getSuccessRate() { return successRate; }
    public void setSuccessRate(Double successRate) { this.successRate = successRate; }
    public Double getLineCoverage() { return lineCoverage; }
    public void setLineCoverage(Double lineCoverage) { this.lineCoverage = lineCoverage; }
    public Long getOpenCritical() { return openCritical; }
    public void setOpenCritical(Long openCritical) { this.openCritical = openCritical; }
    public Long getOpenHigh() { return openHigh; }
    public void setOpenHigh(Long openHigh) { this.openHigh = openHigh; }
    public Integer getCriticalAnomalies() { return criticalAnomalies; }
    public void setCriticalAnomalies(Integer criticalAnomalies) { this.criticalAnomalies = criticalAnomalies; }
    public Integer getIncidents() { return incidents; }
    public void setIncidents(Integer incidents) { this.incidents = incidents; }
    public Long getLogErrors() { return logErrors; }
    public void setLogErrors(Long logErrors) { this.logErrors = logErrors; }
    public String getLlmStatus() { return llmStatus; }
    public void setLlmStatus(String llmStatus) { this.llmStatus = llmStatus; }
    public String getLlmOpinion() { return llmOpinion; }
    public void setLlmOpinion(String llmOpinion) { this.llmOpinion = llmOpinion; }
    public String getLlmProvider() { return llmProvider; }
    public void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }
    public String getError() { return error; }
    public void setError(String error) { this.error = VulnerabilityRecord.truncate(error, 2000); }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = VulnerabilityRecord.truncate(reportJson, MAX_REPORT); }
    public String getReportMarkdown() { return reportMarkdown; }
    public void setReportMarkdown(String reportMarkdown) { this.reportMarkdown = VulnerabilityRecord.truncate(reportMarkdown, MAX_REPORT); }
    public String getReportHtml() { return reportHtml; }
    public void setReportHtml(String reportHtml) { this.reportHtml = VulnerabilityRecord.truncate(reportHtml, MAX_REPORT); }
    public String getHumanDecision() { return humanDecision; }
    public void setHumanDecision(String humanDecision) { this.humanDecision = humanDecision; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getDecisionComment() { return decisionComment; }
    public void setDecisionComment(String decisionComment) { this.decisionComment = VulnerabilityRecord.truncate(decisionComment, 2000); }
}
