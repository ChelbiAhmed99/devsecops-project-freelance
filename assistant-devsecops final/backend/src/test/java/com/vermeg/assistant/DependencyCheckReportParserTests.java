/**
 * Role : documenter le comportement attendu en verifiant l interpretation des rapports Dependency-Check.
 * Contenu : definition de DependencyCheckReportParserTests et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant;

import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.service.DependencyCheckReportParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires du parsing du rapport OWASP Dependency-Check (Sprint 6).
 * On simule un rapport JSON minimal plutôt que de dépendre d'un vrai scan
 * (long et nécessitant une base NVD à jour) dans les tests unitaires.
 */
class DependencyCheckReportParserTests {

    private final DependencyCheckReportParser parser = new DependencyCheckReportParser();

    @Test
    void shouldReturnEmptyListWhenReportDoesNotExist() {
        List<Vulnerability> result = parser.parseReport("/chemin/inexistant/report.json");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldParseVulnerabilitiesWithCvssV3(@TempDir Path tempDir) throws IOException {
        String json = """
                {
                  "dependencies": [
                    {
                      "fileName": "example-lib-1.2.3.jar",
                      "vulnerabilities": [
                        {
                          "name": "CVE-2024-99999",
                          "description": "Une vulnérabilité de test",
                          "cvssv3": {
                            "baseScore": 8.6,
                            "baseSeverity": "HIGH"
                          }
                        }
                      ]
                    },
                    {
                      "fileName": "safe-lib-2.0.0.jar"
                    }
                  ]
                }
                """;

        Path reportFile = tempDir.resolve("dependency-check-report.json");
        Files.writeString(reportFile, json);

        List<Vulnerability> result = parser.parseReport(reportFile.toString());

        assertThat(result).hasSize(1);
        Vulnerability vuln = result.get(0);
        assertThat(vuln.getId()).isEqualTo("CVE-2024-99999");
        assertThat(vuln.getAffectedComponent()).isEqualTo("example-lib-1.2.3.jar");
        assertThat(vuln.getSeverity()).isEqualTo(Vulnerability.Severity.HIGH);
        assertThat(vuln.getCvssScore()).isEqualTo(8.6);
        assertThat(vuln.getSource()).isEqualTo("dependency-check");
        assertThat(vuln.getStatus()).isEqualTo(Vulnerability.Status.NEW);
    }

    @Test
    void shouldMarkSeverityAsUnknownWhenMissing(@TempDir Path tempDir) throws IOException {
        String json = """
                {
                  "dependencies": [
                    {
                      "fileName": "mystery-lib-0.1.jar",
                      "vulnerabilities": [
                        { "name": "CVE-2024-11111", "description": "sans score" }
                      ]
                    }
                  ]
                }
                """;

        Path reportFile = tempDir.resolve("dependency-check-report.json");
        Files.writeString(reportFile, json);

        List<Vulnerability> result = parser.parseReport(reportFile.toString());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSeverity()).isEqualTo(Vulnerability.Severity.UNKNOWN);
        assertThat(result.get(0).getCvssScore()).isNull();
    }
}
