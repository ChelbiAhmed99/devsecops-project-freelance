/**
 * Role : tracer chaque action Jira proposee, validee et executee par l'assistant.
 * Contenu : definition de JiraActionRecord et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Action Jira contrôlée : aucune création ni modification de ticket n'est faite sans une
 * proposition, puis une approbation explicite (principe des quatre yeux), le tout tracé.
 * Statuts : PROPOSED -> EXECUTED | SIMULATED | FAILED, ou PROPOSED -> REJECTED.
 */
@Entity
@Table(name = "jira_action", indexes = {
        @Index(name = "idx_jira_status", columnList = "status"),
        @Index(name = "idx_jira_fp", columnList = "fingerprint")
})
public class JiraActionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 20)
    private String type;
    @Column(length = 20)
    private String status;
    @Column(length = 60)
    private String applicationCode;
    @Column(length = 40)
    private String projectKey;
    @Column(length = 40)
    private String incidentType;
    @Column(length = 300)
    private String incidentRef;
    /** Empreinte stable de l'incident, posée en label Jira : évite les doublons de tickets. */
    @Column(length = 60)
    private String fingerprint;
    /** Ticket visé (commentaire) ; pour une création, le ticket créé est dans resultKey. */
    @Column(length = 40)
    private String issueKey;
    @Column(length = 255)
    private String summary;
    @Column(length = 8000)
    private String description;
    @Column(length = 8000)
    private String comment;
    @Column(length = 36)
    private String recommendationId;
    @Column(length = 80)
    private String proposedBy;
    private LocalDateTime proposedAt;
    @Column(length = 80)
    private String decidedBy;
    private LocalDateTime decidedAt;
    @Column(length = 2000)
    private String decisionComment;
    private LocalDateTime executedAt;
    @Column(length = 40)
    private String resultKey;
    @Column(length = 500)
    private String resultUrl;
    @Column(length = 2000)
    private String error;

    public JiraActionRecord() {
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getApplicationCode() { return applicationCode; }
    public void setApplicationCode(String applicationCode) { this.applicationCode = applicationCode; }
    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    public String getIncidentType() { return incidentType; }
    public void setIncidentType(String incidentType) { this.incidentType = incidentType; }
    public String getIncidentRef() { return incidentRef; }
    public void setIncidentRef(String incidentRef) { this.incidentRef = VulnerabilityRecord.truncate(incidentRef, 300); }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getIssueKey() { return issueKey; }
    public void setIssueKey(String issueKey) { this.issueKey = issueKey; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = VulnerabilityRecord.truncate(summary, 255); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = VulnerabilityRecord.truncate(description, 8000); }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = VulnerabilityRecord.truncate(comment, 8000); }
    public String getRecommendationId() { return recommendationId; }
    public void setRecommendationId(String recommendationId) { this.recommendationId = recommendationId; }
    public String getProposedBy() { return proposedBy; }
    public void setProposedBy(String proposedBy) { this.proposedBy = proposedBy; }
    public LocalDateTime getProposedAt() { return proposedAt; }
    public void setProposedAt(LocalDateTime proposedAt) { this.proposedAt = proposedAt; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getDecisionComment() { return decisionComment; }
    public void setDecisionComment(String decisionComment) { this.decisionComment = VulnerabilityRecord.truncate(decisionComment, 2000); }
    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
    public String getResultKey() { return resultKey; }
    public void setResultKey(String resultKey) { this.resultKey = resultKey; }
    public String getResultUrl() { return resultUrl; }
    public void setResultUrl(String resultUrl) { this.resultUrl = VulnerabilityRecord.truncate(resultUrl, 500); }
    public String getError() { return error; }
    public void setError(String error) { this.error = VulnerabilityRecord.truncate(error, 2000); }
}
