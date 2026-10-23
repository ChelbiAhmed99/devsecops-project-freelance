/**
 * Role : representer et persister les donnees metier de AiRecommendation dans la base de donnees.
 * Contenu : definition de AiRecommendationRecord et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.domain;

import com.vermeg.assistant.model.AiRecommendation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Recommandation IA persistée, avec la trace complète de sa validation humaine. */
@Entity
@Table(name = "ai_recommendation", indexes = @Index(name = "idx_ai_ref", columnList = "incident_ref"))
public class AiRecommendationRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 40)
    private String incidentType;

    @Column(length = 2000)
    private String incidentSummary;

    @Column(name = "incident_ref", length = 300)
    private String incidentRef;

    @Column(length = 200)
    private String source;

    @Column(length = 10000)
    private String explanation;

    @Column(length = 10000)
    private String suggestedFix;

    @Column(length = 4000)
    private String severityAssessment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiRecommendation.ValidationStatus status;

    @Column(length = 30)
    private String provider;

    @Column(length = 100)
    private String model;

    private LocalDateTime createdAt;

    @Column(length = 80)
    private String createdBy;

    @Column(length = 80)
    private String reviewedBy;

    private LocalDateTime reviewedAt;

    @Column(length = 2000)
    private String reviewComment;

    @Column(name = "application_code", length = 60)
    private String applicationCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AiRecommendation.FixStatus fixStatus;

    @Column(length = 80)
    private String appliedBy;

    private LocalDateTime appliedAt;

    @Column(length = 300)
    private String appliedReference;

    private LocalDateTime verifiedAt;

    @Column(length = 2000)
    private String verificationDetails;

    @Column(length = 6000)
    private String diagnostic;

    public AiRecommendationRecord() {
    }

    public static AiRecommendationRecord from(AiRecommendation r) {
        AiRecommendationRecord e = new AiRecommendationRecord();
        e.id = r.getId();
        e.copyFrom(r);
        return e;
    }

    public void copyFrom(AiRecommendation r) {
        incidentType = r.getIncidentType();
        incidentSummary = VulnerabilityRecord.truncate(r.getIncidentSummary(), 2000);
        incidentRef = VulnerabilityRecord.truncate(r.getIncidentRef(), 300);
        source = VulnerabilityRecord.truncate(r.getSource(), 200);
        explanation = VulnerabilityRecord.truncate(r.getExplanation(), 10000);
        suggestedFix = VulnerabilityRecord.truncate(r.getSuggestedFix(), 10000);
        severityAssessment = VulnerabilityRecord.truncate(r.getSeverityAssessment(), 4000);
        status = r.getStatus();
        provider = r.getProvider();
        model = r.getModel();
        createdAt = r.getCreatedAt();
        createdBy = r.getCreatedBy();
        reviewedBy = r.getReviewedBy();
        reviewedAt = r.getReviewedAt();
        reviewComment = VulnerabilityRecord.truncate(r.getReviewComment(), 2000);
        applicationCode = r.getApplicationCode();
        fixStatus = r.getFixStatus();
        appliedBy = r.getAppliedBy();
        appliedAt = r.getAppliedAt();
        appliedReference = VulnerabilityRecord.truncate(r.getAppliedReference(), 300);
        verifiedAt = r.getVerifiedAt();
        verificationDetails = VulnerabilityRecord.truncate(r.getVerificationDetails(), 2000);
        diagnostic = VulnerabilityRecord.truncate(r.getDiagnostic(), 6000);
    }

    public AiRecommendation toModel() {
        AiRecommendation r = new AiRecommendation(id, incidentType, incidentSummary, explanation,
                suggestedFix, severityAssessment, status, createdAt);
        r.setIncidentRef(incidentRef);
        r.setSource(source);
        r.setProvider(provider);
        r.setModel(model);
        r.setCreatedBy(createdBy);
        r.setReviewedBy(reviewedBy);
        r.setReviewedAt(reviewedAt);
        r.setReviewComment(reviewComment);
        r.setApplicationCode(applicationCode);
        r.setFixStatus(fixStatus);
        r.setAppliedBy(appliedBy);
        r.setAppliedAt(appliedAt);
        r.setAppliedReference(appliedReference);
        r.setVerifiedAt(verifiedAt);
        r.setVerificationDetails(verificationDetails);
        r.setDiagnostic(diagnostic);
        return r;
    }

    public String getId() { return id; }
    public AiRecommendation.ValidationStatus getStatus() { return status; }
    public void setStatus(AiRecommendation.ValidationStatus status) { this.status = status; }
    public String getIncidentRef() { return incidentRef; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public void setReviewComment(String reviewComment) {
        this.reviewComment = VulnerabilityRecord.truncate(reviewComment, 2000);
    }
}
