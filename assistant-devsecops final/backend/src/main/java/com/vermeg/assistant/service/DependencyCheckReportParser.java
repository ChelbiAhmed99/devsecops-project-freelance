/**
 * Role : lire les rapports OWASP Dependency-Check et en extraire les vulnerabilites.
 * Contenu : definition de DependencyCheckReportParser et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.model.Vulnerability;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Parse le rapport JSON d'OWASP Dependency-Check en {@link Vulnerability} normalisées.
 * Lecture "défensive" en arbre JsonNode : le schéma évolue selon les versions.
 */
@Service
public class DependencyCheckReportParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Liste vide (pas d'exception) si le rapport n'existe pas encore. */
    public List<Vulnerability> parseReport(String reportPath) {
        File reportFile = new File(reportPath);
        if (!reportFile.exists()) {
            return new ArrayList<>();
        }
        try {
            return parseTree(objectMapper.readTree(reportFile));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Parse un contenu JSON reçu directement (upload / pipeline CI). */
    public List<Vulnerability> parseJson(String json) {
        try {
            return parseTree(objectMapper.readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException("Rapport Dependency-Check invalide : " + e.getMessage());
        }
    }

    private List<Vulnerability> parseTree(JsonNode root) {
        List<Vulnerability> vulnerabilities = new ArrayList<>();
        for (JsonNode dependency : root.path("dependencies")) {
            String componentName = dependency.path("fileName").asText(
                    dependency.path("filePath").asText("dépendance inconnue"));
            for (JsonNode vuln : dependency.path("vulnerabilities")) {
                vulnerabilities.add(toVulnerability(vuln, componentName));
            }
        }
        return vulnerabilities;
    }

    private Vulnerability toVulnerability(JsonNode vulnNode, String componentName) {
        String id = vulnNode.path("name").asText("inconnu");
        String description = vulnNode.path("description").asText("");

        Double cvssScore = null;
        String rawSeverity = null;
        // CVSS v3 prioritaire, puis v2, puis le champ "severity" générique.
        if (vulnNode.hasNonNull("cvssv3")) {
            JsonNode v3 = vulnNode.path("cvssv3");
            cvssScore = v3.hasNonNull("baseScore") ? v3.path("baseScore").asDouble() : null;
            rawSeverity = v3.path("baseSeverity").asText(null);
        } else if (vulnNode.hasNonNull("cvssv2")) {
            JsonNode v2 = vulnNode.path("cvssv2");
            cvssScore = v2.hasNonNull("score") ? v2.path("score").asDouble() : null;
            rawSeverity = v2.path("severity").asText(null);
        }
        if (rawSeverity == null) {
            rawSeverity = vulnNode.path("severity").asText(null);
        }
        Vulnerability.Severity severity = Vulnerability.Severity.parse(rawSeverity);
        if (severity == Vulnerability.Severity.UNKNOWN && "MODERATE".equalsIgnoreCase(rawSeverity)) {
            severity = Vulnerability.Severity.MEDIUM;
        }
        return new Vulnerability(id, "dependency-check", componentName, severity, cvssScore, description,
                Vulnerability.Status.NEW);
    }
}
