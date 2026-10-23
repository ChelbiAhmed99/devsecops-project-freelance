/**
 * Role : definir les donnees echangees avec Jira et le workflow d'actions controlees.
 * Contenu : definition de JiraModels et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.model;

import java.time.LocalDateTime;
import java.util.List;

/** Contrats de l'intégration Jira (recherche de tickets, propositions d'actions, validation). */
public final class JiraModels {

    private JiraModels() {
    }

    public record JiraIssue(String key, String summary, String status, String priority, String assignee,
                            String updated, List<String> labels, String url) { }

    /** Ticket candidat pour une anomalie, avec son score de correspondance et la raison. */
    public record JiraMatch(JiraIssue issue, double score, String reason) { }

    /** Incident pour lequel on cherche un ticket ou on prépare une proposition. */
    public record IncidentQuery(String application, String incidentType, String incidentRef, String text,
                                String recommendationId) { }

    /** type : CREATE_ISSUE (nouveau ticket) ou ADD_COMMENT (commentaire sur un ticket existant). */
    public record ProposalRequest(String type, String application, String projectKey, String incidentType,
                                  String incidentRef, String issueKey, String summary, String description,
                                  String comment, String recommendationId) { }

    public record DecisionRequest(String comment) { }

    public record JiraStatus(boolean enabled, boolean configured, String mode, String deployment, String baseUrl,
                             String defaultProject, String issueType, boolean fourEyes) { }

    public record JiraActionView(Long id, String type, String status, String applicationCode, String projectKey,
                                 String incidentType, String incidentRef, String fingerprint, String issueKey,
                                 String summary, String description, String comment, String recommendationId,
                                 String proposedBy, LocalDateTime proposedAt, String decidedBy, LocalDateTime decidedAt,
                                 String decisionComment, LocalDateTime executedAt, String resultKey, String resultUrl,
                                 String error) { }
}
