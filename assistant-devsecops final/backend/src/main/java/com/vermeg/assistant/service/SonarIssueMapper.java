/**
 * Role : convertir les problemes SonarQube en modeles internes.
 * Contenu : definition de SonarIssueMapper et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vermeg.assistant.model.Vulnerability;

/**
 * Sprint 7 : logique de mapping JSON Sonar -> Vulnerability, extraite dans
 * une classe à part (sans appel réseau) pour pouvoir être testée
 * unitairement sans avoir besoin d'un serveur SonarQube réel.
 */
public final class SonarIssueMapper {

    private SonarIssueMapper() {
    }

    public static Vulnerability toVulnerability(JsonNode issue) {
        String id = issue.path("key").asText("inconnu");
        String component = issue.path("component").asText("composant inconnu");
        String message = issue.path("message").asText("");
        String rawSeverity = issue.path("severity").asText(null);

        return new Vulnerability(
                id,
                "sonarqube",
                component,
                mapSeverity(rawSeverity),
                null, // SonarQube n'exprime pas ses issues en score CVSS
                message,
                Vulnerability.Status.NEW
        );
    }

    /**
     * SonarQube utilise BLOCKER/CRITICAL/MAJOR/MINOR/INFO ; on les fait
     * correspondre à notre échelle commune (LOW/MEDIUM/HIGH/CRITICAL) pour
     * que le futur dashboard puisse trier/filtrer toutes les sources
     * ensemble sans connaître leurs échelles d'origine.
     */
    public static Vulnerability.Severity mapSeverity(String sonarSeverity) {
        if (sonarSeverity == null) {
            return Vulnerability.Severity.UNKNOWN;
        }
        return switch (sonarSeverity.toUpperCase()) {
            case "BLOCKER", "CRITICAL" -> Vulnerability.Severity.CRITICAL;
            case "MAJOR" -> Vulnerability.Severity.HIGH;
            case "MINOR" -> Vulnerability.Severity.MEDIUM;
            case "INFO" -> Vulnerability.Severity.LOW;
            default -> Vulnerability.Severity.UNKNOWN;
        };
    }
}
