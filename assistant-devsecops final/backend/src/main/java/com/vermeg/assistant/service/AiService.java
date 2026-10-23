/**
 * Role : orchestrer la generation et la gestion des recommandations IA.
 * Contenu : definition de AiService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.model.AiRecommendation;
import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.CorrelatedFailure;
import com.vermeg.assistant.model.Diagnostics;
import com.vermeg.assistant.model.TestExecutionSummary;
import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.security.CurrentUser;
import com.vermeg.assistant.service.validation.ValidationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestration de la brique IA : explication d'incidents avec cache par
 * incident, workflow de validation humaine et assistant conversationnel
 * ("copilote") alimenté par un instantané de l'état courant du projet.
 */
@Service
public class AiService {

    private static final Set<String> INCIDENT_TYPES = Set.of("log_anomaly", "test_failure", "vulnerability", "other");

    private final AiClient aiClient;
    private final AiRecommendationStore store;
    private final AuditService auditService;
    private final LogQueryService logQueryService;
    private final TestOrchestrationService testService;
    private final VulnerabilityService vulnerabilityService;
    private final QualityGateService qualityGateService;
    private final TestRunHistoryService historyService;
    private final JobService jobService;
    private final DiagnosticService diagnosticService;
    private final ApplicationService applicationService;
    private final ValidationService validationService;

    public AiService(AiClient aiClient, AiRecommendationStore store, AuditService auditService,
                     LogQueryService logQueryService, TestOrchestrationService testService,
                     VulnerabilityService vulnerabilityService, QualityGateService qualityGateService,
                     TestRunHistoryService historyService, JobService jobService,
                     DiagnosticService diagnosticService, ApplicationService applicationService,
                     ValidationService validationService) {
        this.aiClient = aiClient;
        this.store = store;
        this.auditService = auditService;
        this.logQueryService = logQueryService;
        this.testService = testService;
        this.vulnerabilityService = vulnerabilityService;
        this.qualityGateService = qualityGateService;
        this.historyService = historyService;
        this.jobService = jobService;
        this.diagnosticService = diagnosticService;
        this.applicationService = applicationService;
        this.validationService = validationService;
    }

    public AiRecommendation explain(ApiDtos.ExplainRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("Le champ 'message' est requis");
        }
        String type = request.incidentType() == null ? "other" : request.incidentType().trim().toLowerCase();
        if (!INCIDENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("incidentType invalide : " + INCIDENT_TYPES);
        }
        if (!Boolean.TRUE.equals(request.force())) {
            var cached = store.findActiveByIncidentRef(request.incidentRef());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        // Analyse d'origine : le diagnostic déterministe (corrélation multi-couches) est fourni à l'IA,
        // qui explique et propose une correction à partir de faits établis plutôt que de suppositions.
        String application = request.application() == null || request.application().isBlank() ? null
                : applicationService.find(request.application()).map(a -> a.getCode()).orElse(null);
        String diagnostic = diagnose(type, request.incidentRef(), application);
        String extraContext = request.extraContext();
        if (diagnostic != null) {
            extraContext = (extraContext == null || extraContext.isBlank() ? "" : extraContext + "\n")
                    + "Diagnostic de corrélation (déterministe) : " + diagnostic;
        }
        AiClient.AiExplanationResult result = aiClient.explain(type, request.source(), request.message(),
                request.occurrences(), truncate(extraContext, 7900), Boolean.TRUE.equals(request.force()));
        if (!result.success()) {
            throw new AiUnavailableException(result.errorMessage());
        }
        AiRecommendation recommendation = new AiRecommendation(UUID.randomUUID().toString(), type,
                truncate(request.message(), 1900), result.explanation(), result.suggestedFix(),
                result.severityAssessment(), AiRecommendation.ValidationStatus.PENDING_REVIEW, LocalDateTime.now());
        recommendation.setIncidentRef(request.incidentRef());
        recommendation.setSource(request.source());
        recommendation.setProvider(result.provider());
        recommendation.setModel(result.model());
        recommendation.setCreatedBy(CurrentUser.username());
        recommendation.setApplicationCode(application);
        recommendation.setDiagnostic(diagnostic);
        if (Boolean.TRUE.equals(request.force())) {
            // Régénération : la nouvelle fiche remplace l'ancienne en attente (les fiches déjà revues sont conservées)
            store.deletePendingByIncidentRef(request.incidentRef());
        }
        store.save(recommendation);
        auditService.record("AI_EXPLAIN", type + (request.incidentRef() == null ? "" : " " + request.incidentRef()),
                "fournisseur=" + result.provider());
        return recommendation;
    }

    /**
     * Origine probable d'un incident d'après le moteur de corrélation : l'anomalie est-elle la cause
     * racine d'un incident, ou la conséquence d'une autre défaillance ? Null si rien de concluant.
     */
    public String diagnose(String type, String incidentRef, String application) {
        if (incidentRef == null || incidentRef.isBlank() || "vulnerability".equals(type)) return null;
        try {
            for (Diagnostics.CorrelatedIncident incident : diagnosticService.incidents(application)) {
                Diagnostics.RootCauseCandidate root = incident.rootCause();
                boolean isSignal = incident.signals().stream().anyMatch(s -> incidentRef.equals(s.key()));
                boolean isImpactedTest = incident.impactedTests().contains(incidentRef);
                if (!isSignal && !isImpactedTest) continue;
                StringBuilder sb = new StringBuilder();
                if (incidentRef.equals(root.signal().key())) {
                    sb.append("cet incident est la CAUSE RACINE probable (confiance ").append(root.confidencePercent())
                            .append(" %) d'une rafale de ").append(incident.eventCount()).append(" erreurs sur les couches ")
                            .append(String.join(", ", incident.layers())).append(". ");
                } else {
                    sb.append("cet incident est probablement une CONSÉQUENCE de : [").append(LogTypeClassifier.label(root.signal().layer()))
                            .append("] ").append(truncate(root.signal().message(), 200)).append(" (confiance ")
                            .append(root.confidencePercent()).append(" %). ");
                }
                sb.append(root.rationale());
                if (!incident.chain().isEmpty()) {
                    sb.append(" Chaîne : ").append(incident.chain().stream().limit(3)
                            .map(c -> truncate(c.fromLabel(), 80) + " → " + truncate(c.toLabel(), 80))
                            .collect(java.util.stream.Collectors.joining(" ; "))).append('.');
                }
                if (!incident.impactedTests().isEmpty()) {
                    sb.append(" Tests impactés : ").append(String.join(", ", incident.impactedTests())).append('.');
                }
                return truncate(sb.toString(), 3000);
            }
        } catch (RuntimeException e) {
            // le diagnostic enrichit l'explication mais ne doit jamais la bloquer
        }
        return null;
    }

    public AiRecommendation review(String id, ApiDtos.ReviewRequest request) {
        if (request.status() == null) {
            throw new IllegalArgumentException("Champ 'status' manquant");
        }
        AiRecommendation.ValidationStatus status;
        try {
            status = AiRecommendation.ValidationStatus.valueOf(request.status().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Statut invalide. Valeurs attendues : APPROVED, REJECTED");
        }
        if (status == AiRecommendation.ValidationStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Un développeur ne peut que valider (APPROVED) ou rejeter (REJECTED) une recommandation");
        }
        if (!store.review(id, status, CurrentUser.username(), request.comment())) {
            throw new NoSuchElementException("Recommandation introuvable : " + id);
        }
        auditService.record("AI_REVIEW", id, status.name() + (request.comment() == null ? "" : " : " + request.comment()));
        return store.findById(id).orElseThrow();
    }

    public ApiDtos.ChatResponse chat(ApiDtos.ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("La question est vide");
        }
        List<Map<String, String>> history = request.history() == null ? List.of()
                : request.history().stream()
                .filter(m -> m.role() != null && m.content() != null)
                .skip(Math.max(0, request.history().size() - 10))
                .map(m -> Map.of("role", "assistant".equals(m.role()) ? "assistant" : "user",
                        "content", truncate(m.content(), "assistant".equals(m.role()) ? 600 : 2000)))
                .toList();
        AiClient.ChatResult result = aiClient.chat(truncate(request.question(), 2000), history, buildContext());
        if (!result.success()) {
            throw new AiUnavailableException(result.errorMessage());
        }
        return new ApiDtos.ChatResponse(result.answer(), result.provider(), result.model(), LocalDateTime.now());
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    /** Taille maximale de l'instantané (≈ 3 500 jetons) : riche, mais compatible avec les quotas Groq. */
    private static final int CONTEXT_MAX_CHARS = 12_000;

    /**
     * Instantané textuel de l'état du projet, injecté dans le prompt du copilote.
     * Il couvre tout ce que montrent les écrans (synthèse, logs, tests, sécurité, IA,
     * activité) afin que l'on puisse poser n'importe quelle question sur le projet.
     * Chaque bloc est isolé : une brique indisponible n'empêche pas les autres.
     */
    public String buildContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("Instantané généré le ").append(LocalDateTime.now().format(TS))
                .append(" · utilisateur connecté : ").append(CurrentUser.username()).append('\n');

        List<AnomalyGroup> anomalies = List.of();
        ApiDtos.SpikeReport spikes = null;
        TestExecutionSummary tests = null;
        ApiDtos.VulnerabilityStats vulnStats = null;
        try { anomalies = logQueryService.anomalies("local").groups(); } catch (RuntimeException ignored) { }
        try { spikes = logQueryService.spikes(); } catch (RuntimeException ignored) { }
        try { tests = testService.currentResults(); } catch (RuntimeException ignored) { }
        try { vulnStats = vulnerabilityService.stats(); } catch (RuntimeException ignored) { }

        // ---- Synthèse : score de santé + quality gate
        final List<AnomalyGroup> groups = anomalies;
        final ApiDtos.SpikeReport spikeReport = spikes;
        final TestExecutionSummary t = tests;
        final ApiDtos.VulnerabilityStats vs = vulnStats;
        section(sb, "Synthèse", () -> {
            long crit = vs == null ? 0 : vs.openBySeverity().getOrDefault("CRITICAL", 0L);
            long high = vs == null ? 0 : vs.openBySeverity().getOrDefault("HIGH", 0L);
            long critAnomalies = groups.stream().filter(AnomalyGroup::isCritical).count();
            int nbSpikes = spikeReport == null ? 0 : spikeReport.spikes().size();
            int health = DashboardService.computeHealth(crit, high, t, critAnomalies, nbSpikes);
            sb.append("- Score de santé : ").append(health).append("/100 (").append(DashboardService.healthLabel(health)).append(")\n");
            ApiDtos.QualityGateResult gate = qualityGateService.evaluate();
            sb.append("- Quality gate (feu vert de livraison) : ").append(gate.status()).append('\n');
            gate.conditions().forEach(c -> sb.append("  - ").append(c.passed() ? "OK " : "KO ").append(c.name())
                    .append(" : ").append(c.actual()).append(" (seuil ").append(c.threshold()).append(")\n"));
        });

        // ---- Journaux d'activité
        section(sb, "Journaux (logs)", () -> {
            ApiDtos.LogStats st = logQueryService.stats();
            sb.append("- ").append(st.total()).append(" lignes, taux d'erreur ").append(st.errorRatePercent()).append("%");
            if (st.firstAt() != null) sb.append(", du ").append(st.firstAt().format(TS)).append(" au ").append(st.lastAt().format(TS));
            sb.append('\n').append("- Par niveau : ").append(st.byLevel()).append('\n');
            sb.append("- Par service : ").append(topMap(st.bySource(), 10)).append('\n');
            if (spikeReport != null) {
                sb.append("- Pics d'erreurs détectés : ").append(spikeReport.spikes().size()).append('\n');
                spikeReport.spikes().stream().sorted(Comparator.comparingDouble(ApiDtos.FrequencySpike::score).reversed()).limit(5)
                        .forEach(p -> sb.append("  - ").append(p.source()).append(" le ").append(p.bucketStart().format(TS))
                                .append(" : ").append(p.count()).append(" erreurs (habituel ≈ ")
                                .append(Math.round(p.baseline())).append(")\n"));
            }
        });

        section(sb, "Anomalies de logs (" + groups.size() + " groupes, " + groups.stream().filter(AnomalyGroup::isCritical).count() + " critiques)", () ->
                groups.stream().limit(15).forEach(a -> sb.append("- [").append(a.isCritical() ? "CRITIQUE" : a.getLevel())
                        .append("][").append(a.getCategory()).append("] ").append(truncate(a.getNormalizedMessage(), 160))
                        .append(" | ").append(a.getOccurrences()).append(" occurrences | services: ")
                        .append(String.join(", ", a.getAffectedSources()))
                        .append(a.getLastSeen() != null ? " | dernière fois " + a.getLastSeen().format(TS) : "").append('\n')));

        // ---- Tests
        section(sb, "Tests (dernière exécution)", () -> {
            sb.append(t.getTotalTests()).append(" tests, ").append(t.getTotalFailures() + t.getTotalErrors())
                    .append(" en échec, ").append(t.getTotalSkipped()).append(" ignorés, réussite ").append(t.getSuccessRatePercent()).append("%");
            if (t.getLineCoverage() != null) sb.append(", couverture lignes ").append(t.getLineCoverage()).append('%');
            if (t.getBranchCoverage() != null) sb.append(", branches ").append(t.getBranchCoverage()).append('%');
            if (t.getExecutedAt() != null) sb.append(", exécutés le ").append(t.getExecutedAt().format(TS));
            if (t.getTrigger() != null) sb.append(" (").append(t.getTrigger()).append(t.getBranch() != null ? ", branche " + t.getBranch() : "").append(')');
            sb.append('\n');
            List<CorrelatedFailure> failures = testService.correlatedFailures();
            failures.stream().limit(10).forEach(f -> sb.append("- ").append(f.getSuiteName()).append('#')
                    .append(f.getTestCaseName()).append(" : ").append(truncate(String.valueOf(f.getFailureMessage()), 160))
                    .append(f.getCorrelationNote() != null ? " | " + truncate(f.getCorrelationNote(), 120) : "").append('\n'));
        });
        section(sb, "Historique des tests", () -> historyService.history(6).forEach(r -> sb.append("- ")
                .append(r.executedAt() != null ? r.executedAt().format(TS) : "?").append(" : ").append(r.total()).append(" tests, ")
                .append(r.failed()).append(" échecs, ").append(r.successRate()).append("%")
                .append(r.lineCoverage() != null ? ", couverture " + r.lineCoverage() + "%" : "").append('\n')));
        section(sb, "Tests instables (flaky)", () -> {
            List<ApiDtos.FlakyTest> flaky = historyService.flakyTests();
            if (flaky.isEmpty()) sb.append("- aucun\n");
            flaky.stream().limit(5).forEach(f -> sb.append("- ").append(f.suiteName()).append('#').append(f.testName())
                    .append(" : ").append(f.failures()).append(" échecs sur ").append(f.runsObserved()).append(" exécutions\n"));
        });

        // ---- Sécurité
        section(sb, "Statistiques de sécurité", () -> {
            sb.append("- Ouvertes : ").append(vs.open()).append(", corrigées : ").append(vs.fixed()).append(", total : ").append(vs.total()).append('\n');
            sb.append("- Ouvertes par gravité : ").append(vs.openBySeverity()).append('\n');
            sb.append("- Ouvertes par outil : ").append(vs.openBySource()).append('\n');
            sb.append("- Par statut : ").append(vs.byStatus()).append('\n');
            if (vs.meanTimeToFixDays() != null) sb.append("- Délai moyen de correction : ").append(vs.meanTimeToFixDays()).append(" jours\n");
        });
        section(sb, "Vulnérabilités ouvertes (" + (vs != null ? vs.open() : "?") + ")", () -> {
            List<Vulnerability> vulns = vulnerabilityService.list("OPEN", null, null, null);
            vulns.stream().limit(20).forEach(v -> sb.append("- ").append(v.getId()).append(" [").append(v.getSeverity())
                    .append(", priorité ").append(v.getPriorityScore()).append("] ").append(v.getAffectedComponent())
                    .append(v.getFixedVersion() != null ? " (corrigée en " + v.getFixedVersion() + ")" : " (pas de version corrective connue)")
                    .append(" - source ").append(v.getSource())
                    .append(v.getStatus() != null ? " - statut " + v.getStatus() : "")
                    .append(v.getAssignee() != null ? " - assignée à " + v.getAssignee() : "")
                    .append(v.getDescription() != null ? " - " + truncate(v.getDescription(), 90) : "").append('\n'));
            Map<String, Long> byComponent = new java.util.TreeMap<>();
            vulns.forEach(v -> byComponent.merge(String.valueOf(v.getAffectedComponent()), 1L, Long::sum));
            sb.append("- Composants les plus touchés : ").append(topMap(byComponent, 8)).append('\n');
        });

        // ---- Validation multi-applications
        section(sb, "Validation des applications (dernière décision par application)", () -> {
            ApiDtos.PortfolioSummary portfolio = validationService.portfolio();
            sb.append("- ").append(portfolio.applications()).append(" application(s) : ").append(portfolio.validated()).append(" validée(s), ")
                    .append(portfolio.reserves()).append(" avec réserves, ").append(portfolio.rejected()).append(" rejetée(s), ")
                    .append(portfolio.inconclusive()).append(" non concluante(s), ").append(portfolio.neverValidated())
                    .append(" jamais validée(s) ; revues humaines en attente : ").append(portfolio.pendingReviews()).append('\n');
            portfolio.entries().forEach(e -> {
                sb.append("  - ").append(e.application().name()).append(" (").append(e.application().code()).append(", criticité ")
                        .append(e.application().criticality()).append(")");
                if (e.lastValidation() == null) {
                    sb.append(" : jamais validée\n");
                } else {
                    ApiDtos.ApplicationValidationSummary v = e.lastValidation();
                    sb.append(" : ").append(v.finalVerdict()).append(", score ").append(v.score()).append("/100")
                            .append(v.reviewRequired() && v.humanDecision() == null ? ", revue humaine requise" : "")
                            .append(v.finishedAt() != null ? ", le " + v.finishedAt().format(TS) : "").append('\n');
                }
            });
        });

        // ---- IA, activité
        section(sb, "Recommandations IA", () -> {
            List<AiRecommendation> recs = store.getAll();
            sb.append("- À valider : ").append(recs.stream().filter(r -> r.getStatus() == AiRecommendation.ValidationStatus.PENDING_REVIEW).count())
                    .append(", validées : ").append(recs.stream().filter(r -> r.getStatus() == AiRecommendation.ValidationStatus.APPROVED).count())
                    .append(", rejetées : ").append(recs.stream().filter(r -> r.getStatus() == AiRecommendation.ValidationStatus.REJECTED).count()).append('\n');
            recs.stream().limit(6).forEach(r -> sb.append("- [").append(r.getStatus()).append("] ")
                    .append(truncate(r.getIncidentSummary(), 100)).append(" → ").append(truncate(r.getSuggestedFix(), 140)).append('\n'));
        });
        section(sb, "Activité récente", () -> auditService.latest(8).forEach(a -> sb.append("- ")
                .append(a.at() != null ? a.at().format(TS) : "?").append(' ').append(a.username()).append(" : ")
                .append(a.action()).append(a.target() != null ? " " + truncate(a.target(), 60) : "").append('\n')));
        section(sb, "Traitements récents (scans, tests, imports)", () -> jobService.recent(5).forEach(j -> sb.append("- ")
                .append(j.getLabel()).append(" : ").append(j.getStatus())
                .append(j.getStartedAt() != null ? " le " + j.getStartedAt().format(TS) : "")
                .append(j.getMessage() != null ? " - " + truncate(j.getMessage(), 100) : "").append('\n')));

        return truncate(sb.toString(), CONTEXT_MAX_CHARS);
    }

    /** Ajoute une section « ## titre » ; si la brique est indisponible, le signale sans interrompre le reste. */
    private static void section(StringBuilder sb, String title, Runnable body) {
        int mark = sb.length();
        sb.append("\n## ").append(title).append('\n');
        try {
            body.run();
        } catch (RuntimeException e) {
            sb.setLength(mark);
            sb.append("\n## ").append(title).append("\n- indisponible pour le moment\n");
        }
    }

    private static String topMap(Map<String, Long> map, int limit) {
        if (map == null || map.isEmpty()) return "aucun";
        return map.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit)
                .map(e -> e.getKey() + " (" + e.getValue() + ")").collect(java.util.stream.Collectors.joining(", "));
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }

    /** Levée quand le service IA est injoignable ou en erreur (renvoyée en 502 par l'API). */
    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message) {
            super(message);
        }
    }
}
