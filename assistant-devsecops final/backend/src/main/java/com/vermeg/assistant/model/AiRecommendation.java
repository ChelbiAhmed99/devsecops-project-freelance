/**
 * Role : definir les donnees echangees ou calculees pour le modele AiRecommendation.
 * Contenu : definition de AiRecommendation et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.model;

import java.time.LocalDateTime;

/**
 * Explication / proposition de correction générée par la brique IA.
 * Règle du cahier des charges : toute recommandation naît en PENDING_REVIEW
 * et n'est jamais appliquée automatiquement ; seule une validation humaine
 * explicite (APPROVED / REJECTED) la fait changer d'état.
 */
public class AiRecommendation {

    private String id;
    private String incidentType;    // log_anomaly | test_failure | vulnerability | other
    private String incidentSummary;
    private String explanation;
    private String suggestedFix;
    private String severityAssessment;
    private ValidationStatus status;
    private LocalDateTime createdAt;

    private String incidentRef;     // référence stable de l'incident (clé d'anomalie, id de vulnérabilité...)
    private String source;
    private String provider;        // "groq" (LLM) ou "heuristic" (base de connaissances locale)
    private String model;
    private String createdBy;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewComment;

    // --- Cycle de vie de la correction (après validation humaine) ---
    private String applicationCode;
    /** NONE -> APPLIED (déclarée appliquée par un humain) -> VERIFIED | STILL_PRESENT | NOT_ENOUGH_DATA. */
    private FixStatus fixStatus = FixStatus.NONE;
    private String appliedBy;
    private LocalDateTime appliedAt;
    /** Référence de la correction (commit, merge request, ticket...). */
    private String appliedReference;
    private LocalDateTime verifiedAt;
    private String verificationDetails;
    /** Diagnostic d'origine (cause racine probable, preuves) au moment de la proposition. */
    private String diagnostic;

    public enum FixStatus {
        NONE, APPLIED, VERIFIED, STILL_PRESENT, NOT_ENOUGH_DATA
    }

    public enum ValidationStatus {
        PENDING_REVIEW, APPROVED, REJECTED
    }

    public AiRecommendation() {
    }

    public AiRecommendation(String id, String incidentType, String incidentSummary, String explanation,
                            String suggestedFix, String severityAssessment, ValidationStatus status,
                            LocalDateTime createdAt) {
        this.id = id;
        this.incidentType = incidentType;
        this.incidentSummary = incidentSummary;
        this.explanation = explanation;
        this.suggestedFix = suggestedFix;
        this.severityAssessment = severityAssessment;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIncidentType() { return incidentType; }
    public void setIncidentType(String incidentType) { this.incidentType = incidentType; }
    public String getIncidentSummary() { return incidentSummary; }
    public void setIncidentSummary(String incidentSummary) { this.incidentSummary = incidentSummary; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public String getSuggestedFix() { return suggestedFix; }
    public void setSuggestedFix(String suggestedFix) { this.suggestedFix = suggestedFix; }
    public String getSeverityAssessment() { return severityAssessment; }
    public void setSeverityAssessment(String severityAssessment) { this.severityAssessment = severityAssessment; }
    public ValidationStatus getStatus() { return status; }
    public void setStatus(ValidationStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getIncidentRef() { return incidentRef; }
    public void setIncidentRef(String incidentRef) { this.incidentRef = incidentRef; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public String getApplicationCode() { return applicationCode; }
    public void setApplicationCode(String applicationCode) { this.applicationCode = applicationCode; }
    public FixStatus getFixStatus() { return fixStatus; }
    public void setFixStatus(FixStatus fixStatus) { this.fixStatus = fixStatus == null ? FixStatus.NONE : fixStatus; }
    public String getAppliedBy() { return appliedBy; }
    public void setAppliedBy(String appliedBy) { this.appliedBy = appliedBy; }
    public LocalDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(LocalDateTime appliedAt) { this.appliedAt = appliedAt; }
    public String getAppliedReference() { return appliedReference; }
    public void setAppliedReference(String appliedReference) { this.appliedReference = appliedReference; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getVerificationDetails() { return verificationDetails; }
    public void setVerificationDetails(String verificationDetails) { this.verificationDetails = verificationDetails; }
    public String getDiagnostic() { return diagnostic; }
    public void setDiagnostic(String diagnostic) { this.diagnostic = diagnostic; }
}
