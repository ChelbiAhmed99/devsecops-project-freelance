/**
 * Role : documenter le comportement attendu en verifiant la conversion des resultats Trivy en modeles internes.
 * Contenu : definition de TrivyReportMapperTests et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant;

import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.service.TrivyReportMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires du parsing de la sortie JSON de Trivy (Sprint 7).
 * Aucun binaire Trivy requis : on simule directement sa sortie JSON.
 */
class TrivyReportMapperTests {

    @Test
    void shouldReturnEmptyListForBlankInput() {
        assertThat(TrivyReportMapper.parse(null)).isEmpty();
        assertThat(TrivyReportMapper.parse("")).isEmpty();
    }

    @Test
    void shouldParseVulnerabilitiesFromTrivyOutput() {
        String json = """
                {
                  "Results": [
                    {
                      "Target": "assistant-devsecops-backend:latest (alpine 3.19)",
                      "Vulnerabilities": [
                        {
                          "VulnerabilityID": "CVE-2025-12345",
                          "PkgName": "openssl",
                          "Severity": "HIGH",
                          "Description": "Une faille de test dans OpenSSL",
                          "CVSS": { "nvd": { "V3Score": 7.5 } }
                        }
                      ]
                    }
                  ]
                }
                """;

        List<Vulnerability> result = TrivyReportMapper.parse(json);

        assertThat(result).hasSize(1);
        Vulnerability vuln = result.get(0);
        assertThat(vuln.getId()).isEqualTo("CVE-2025-12345");
        assertThat(vuln.getSource()).isEqualTo("trivy");
        assertThat(vuln.getAffectedComponent()).contains("openssl");
        assertThat(vuln.getSeverity()).isEqualTo(Vulnerability.Severity.HIGH);
        assertThat(vuln.getCvssScore()).isEqualTo(7.5);
    }

    @Test
    void shouldHandleTargetWithoutVulnerabilities() {
        String json = """
                { "Results": [ { "Target": "clean-image:latest" } ] }
                """;

        List<Vulnerability> result = TrivyReportMapper.parse(json);
        assertThat(result).isEmpty();
    }
}
