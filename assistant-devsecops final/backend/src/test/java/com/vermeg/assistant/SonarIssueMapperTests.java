/**
 * Role : documenter le comportement attendu en verifiant la conversion des problemes SonarQube en modeles internes.
 * Contenu : definition de SonarIssueMapperTests et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.service.SonarIssueMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires du mapping JSON Sonar -> Vulnerability (Sprint 7).
 * Aucun serveur Sonar requis : on construit directement le JSON attendu.
 */
class SonarIssueMapperTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldMapBlockerSeverityToCritical() throws Exception {
        JsonNode issue = objectMapper.readTree("""
                {
                  "key": "AY_example123",
                  "component": "backend:src/main/java/com/vermeg/assistant/AssistantApplication.java",
                  "message": "Fix this potential security hotspot",
                  "severity": "BLOCKER"
                }
                """);

        Vulnerability vuln = SonarIssueMapper.toVulnerability(issue);

        assertThat(vuln.getId()).isEqualTo("AY_example123");
        assertThat(vuln.getSource()).isEqualTo("sonarqube");
        assertThat(vuln.getSeverity()).isEqualTo(Vulnerability.Severity.CRITICAL);
        assertThat(vuln.getCvssScore()).isNull();
        assertThat(vuln.getStatus()).isEqualTo(Vulnerability.Status.NEW);
    }

    @Test
    void shouldMapMinorSeverityToMedium() {
        assertThat(SonarIssueMapper.mapSeverity("MINOR")).isEqualTo(Vulnerability.Severity.MEDIUM);
    }

    @Test
    void shouldMapUnknownSeverityWhenMissing() {
        assertThat(SonarIssueMapper.mapSeverity(null)).isEqualTo(Vulnerability.Severity.UNKNOWN);
        assertThat(SonarIssueMapper.mapSeverity("SOMETHING_ELSE")).isEqualTo(Vulnerability.Severity.UNKNOWN);
    }
}
