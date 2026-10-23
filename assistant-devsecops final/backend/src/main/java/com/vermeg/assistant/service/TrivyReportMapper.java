/**
 * Role : convertir les resultats Trivy en vulnerabilites internes.
 * Contenu : definition de TrivyReportMapper et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.model.Vulnerability;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse la sortie JSON de Trivy (image, système de fichiers ou dépôt) en
 * {@link Vulnerability}. Récupère aussi la version corrigée (FixedVersion),
 * utilisée pour la priorisation et les recommandations de correction.
 */
public final class TrivyReportMapper {

    private TrivyReportMapper() {
    }

    public static List<Vulnerability> parse(String trivyJsonOutput) {
        List<Vulnerability> vulnerabilities = new ArrayList<>();
        if (trivyJsonOutput == null || trivyJsonOutput.isBlank()) {
            return vulnerabilities;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(trivyJsonOutput);
            for (JsonNode result : root.path("Results")) {
                String target = result.path("Target").asText("image inconnue");
                for (JsonNode vuln : result.path("Vulnerabilities")) {
                    vulnerabilities.add(toVulnerability(vuln, target));
                }
                // Mauvaises configurations (Dockerfile, IaC) détectées par Trivy
                for (JsonNode mis : result.path("Misconfigurations")) {
                    if ("PASS".equalsIgnoreCase(mis.path("Status").asText())) continue;
                    Vulnerability v = new Vulnerability(mis.path("ID").asText("misconfig"), "trivy",
                            target + " / config", Vulnerability.Severity.parse(mis.path("Severity").asText(null)),
                            null, mis.path("Title").asText("") + " - " + mis.path("Resolution").asText(""),
                            Vulnerability.Status.NEW);
                    vulnerabilities.add(v);
                }
            }
        } catch (Exception e) {
            // Sortie non-JSON : on renvoie ce qui a pu être extrait.
        }
        return vulnerabilities;
    }

    private static Vulnerability toVulnerability(JsonNode vuln, String target) {
        String pkgName = vuln.path("PkgName").asText("paquet inconnu");
        String installed = vuln.path("InstalledVersion").asText("");
        String description = vuln.path("Title").asText("");
        String longDescription = vuln.path("Description").asText("");
        if (description.isBlank()) {
            description = longDescription;
        } else if (!longDescription.isBlank()) {
            description = description + " - " + longDescription;
        }

        Double cvssScore = null;
        JsonNode cvss = vuln.path("CVSS");
        if (cvss.path("nvd").has("V3Score")) {
            cvssScore = cvss.path("nvd").path("V3Score").asDouble();
        } else if (cvss.path("redhat").has("V3Score")) {
            cvssScore = cvss.path("redhat").path("V3Score").asDouble();
        }

        Vulnerability v = new Vulnerability(
                vuln.path("VulnerabilityID").asText("inconnu"),
                "trivy",
                target + " / " + pkgName + (installed.isBlank() ? "" : "@" + installed),
                Vulnerability.Severity.parse(vuln.path("Severity").asText(null)),
                cvssScore,
                description,
                Vulnerability.Status.NEW);
        String fixed = vuln.path("FixedVersion").asText("");
        v.setFixedVersion(fixed.isBlank() ? null : fixed);
        return v;
    }
}
