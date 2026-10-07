package com.vermeg.assistant.service;

import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.TestExecutionSummary;
import com.vermeg.assistant.model.Vulnerability;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Exports : CSV des vulnérabilités et rapport de synthèse Markdown (comités qualité, soutenance). */
@Service
public class ReportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final VulnerabilityService vulnerabilityService;
    private final LogQueryService logQueryService;
    private final TestOrchestrationService testService;
    private final TestRunHistoryService historyService;
    private final QualityGateService qualityGateService;

    public ReportService(VulnerabilityService vulnerabilityService, LogQueryService logQueryService,
                         TestOrchestrationService testService, TestRunHistoryService historyService,
                         QualityGateService qualityGateService) {
        this.vulnerabilityService = vulnerabilityService;
        this.logQueryService = logQueryService;
        this.testService = testService;
        this.historyService = historyService;
        this.qualityGateService = qualityGateService;
    }

    public String vulnerabilitiesCsv() {
        StringBuilder sb = new StringBuilder("id;source;composant;severite;cvss;priorite;statut;version_corrigee;")
                .append("premiere_detection;derniere_detection;assigne;description\n");
        for (Vulnerability v : vulnerabilityService.list(null, null, null, null)) {
            sb.append(csv(v.getId())).append(';').append(csv(v.getSource())).append(';')
                    .append(csv(v.getAffectedComponent())).append(';').append(v.getSeverity()).append(';')
                    .append(v.getCvssScore() == null ? "" : v.getCvssScore()).append(';')
                    .append(v.getPriorityScore()).append(';').append(v.getStatus()).append(';')
                    .append(csv(v.getFixedVersion())).append(';').append(date(v.getFirstDetectedAt())).append(';')
                    .append(date(v.getLastDetectedAt())).append(';').append(csv(v.getAssignee())).append(';')
                    .append(csv(v.getDescription())).append('\n');
        }
        return sb.toString();
    }

    public String markdownSummary() {
        StringBuilder md = new StringBuilder();
        md.append("# Rapport DevSecOps - Assistant Vermeg\n\n")
                .append("_Généré le ").append(LocalDateTime.now().format(FMT)).append("_\n\n");

        ApiDtos.QualityGateResult gate = qualityGateService.evaluate();
        md.append("## Quality gate : **").append(gate.status()).append("**\n\n")
                .append("| Condition | Valeur | Seuil | Résultat |\n|---|---|---|---|\n");
        gate.conditions().forEach(c -> md.append("| ").append(c.name()).append(" | ").append(c.actual())
                .append(" | ").append(c.threshold()).append(" | ").append(c.passed() ? "✅" : "❌").append(" |\n"));

        TestExecutionSummary tests = testService.currentResults();
        md.append("\n## Tests automatisés\n\n")
                .append("- Tests : ").append(tests.getTotalTests()).append('\n')
                .append("- En échec : ").append(tests.getTotalFailures() + tests.getTotalErrors()).append('\n')
                .append("- Taux de réussite : ").append(tests.getSuccessRatePercent()).append(" %\n")
                .append("- Couverture de lignes : ").append(tests.getLineCoverage() == null ? "n/d" : tests.getLineCoverage() + " %").append('\n');
        List<ApiDtos.FlakyTest> flaky = historyService.flakyTests();
        if (!flaky.isEmpty()) {
            md.append("- Tests instables : ");
            flaky.stream().limit(5).forEach(f -> md.append('`').append(f.testName()).append("` "));
            md.append('\n');
        }

        ApiDtos.VulnerabilityStats stats = vulnerabilityService.stats();
        md.append("\n## Vulnérabilités\n\n- Ouvertes : ").append(stats.open())
                .append(" / Corrigées : ").append(stats.fixed()).append('\n');
        for (Map.Entry<String, Long> e : stats.openBySeverity().entrySet()) {
            if (e.getValue() > 0) md.append("  - ").append(e.getKey()).append(" : ").append(e.getValue()).append('\n');
        }
        if (stats.meanTimeToFixDays() != null) {
            md.append("- Délai moyen de correction : ").append(stats.meanTimeToFixDays()).append(" jours\n");
        }
        md.append("\n| Priorité | ID | Sévérité | Composant | Correctif |\n|---|---|---|---|---|\n");
        vulnerabilityService.list("OPEN", null, null, null).stream().limit(15).forEach(v -> md.append("| ")
                .append(v.getPriorityScore()).append(" | ").append(v.getId()).append(" | ").append(v.getSeverity())
                .append(" | ").append(md(v.getAffectedComponent())).append(" | ")
                .append(v.getFixedVersion() == null ? "-" : v.getFixedVersion()).append(" |\n"));

        List<AnomalyGroup> anomalies = logQueryService.anomalies("local").groups();
        md.append("\n## Anomalies de logs\n\n| Criticité | Catégorie | Message | Occurrences | Services |\n|---|---|---|---|---|\n");
        anomalies.stream().limit(15).forEach(a -> md.append("| ").append(a.isCritical() ? "CRITIQUE" : a.getLevel())
                .append(" | ").append(a.getCategory()).append(" | ").append(md(a.getNormalizedMessage())).append(" | ")
                .append(a.getOccurrences()).append(" | ").append(String.join(", ", a.getAffectedSources())).append(" |\n"));
        return md.toString();
    }

    private String csv(String value) {
        if (value == null) return "";
        String v = value.replace("\r", " ").replace("\n", " ");
        // Neutralise l'injection de formules dans les tableurs (=, +, -, @)
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return v.contains(";") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private String md(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private String date(LocalDateTime d) {
        return d == null ? "" : d.format(FMT);
    }
}
