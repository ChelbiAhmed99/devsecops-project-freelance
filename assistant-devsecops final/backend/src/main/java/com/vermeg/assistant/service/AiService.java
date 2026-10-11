package com.vermeg.assistant.service;

import com.vermeg.assistant.model.AiRecommendation;
import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.CorrelatedFailure;
import com.vermeg.assistant.model.TestExecutionSummary;
import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    public AiService(AiClient aiClient, AiRecommendationStore store, AuditService auditService,
                     LogQueryService logQueryService, TestOrchestrationService testService,
                     VulnerabilityService vulnerabilityService) {
        this.aiClient = aiClient;
        this.store = store;
        this.auditService = auditService;
        this.logQueryService = logQueryService;
        this.testService = testService;
        this.vulnerabilityService = vulnerabilityService;
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
        AiClient.AiExplanationResult result = aiClient.explain(type, request.source(), request.message(),
                request.occurrences(), request.extraContext(), Boolean.TRUE.equals(request.force()));
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
        if (Boolean.TRUE.equals(request.force())) {
            // Régénération : la nouvelle fiche remplace l'ancienne en attente (les fiches déjà revues sont conservées)
            store.deletePendingByIncidentRef(request.incidentRef());
        }
        store.save(recommendation);
        auditService.record("AI_EXPLAIN", type + (request.incidentRef() == null ? "" : " " + request.incidentRef()),
                "fournisseur=" + result.provider());
        return recommendation;
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

    /** Instantané textuel compact de l'état du projet, injecté dans le prompt du copilote. */
    public String buildContext() {
        StringBuilder sb = new StringBuilder();
        try {
            List<AnomalyGroup> anomalies = logQueryService.anomalies("local").groups();
            sb.append("## Anomalies de logs (").append(anomalies.size()).append(" groupes)\n");
            anomalies.stream().limit(8).forEach(a -> sb.append("- [").append(a.isCritical() ? "CRITIQUE" : a.getLevel())
                    .append("][").append(a.getCategory()).append("] ").append(a.getNormalizedMessage())
                    .append(" | ").append(a.getOccurrences()).append(" occurrences | services: ")
                    .append(String.join(", ", a.getAffectedSources())).append('\n'));
        } catch (RuntimeException e) {
            sb.append("Anomalies indisponibles\n");
        }
        try {
            TestExecutionSummary tests = testService.currentResults();
            sb.append("\n## Tests (dernière exécution)\n").append(tests.getTotalTests()).append(" tests, ")
                    .append(tests.getTotalFailures() + tests.getTotalErrors()).append(" en échec, réussite ")
                    .append(tests.getSuccessRatePercent()).append("%");
            if (tests.getLineCoverage() != null) sb.append(", couverture lignes ").append(tests.getLineCoverage()).append('%');
            sb.append('\n');
            List<CorrelatedFailure> failures = testService.correlatedFailures();
            failures.stream().limit(6).forEach(f -> sb.append("- ").append(f.getSuiteName()).append('#')
                    .append(f.getTestCaseName()).append(" : ").append(truncate(String.valueOf(f.getFailureMessage()), 200)).append('\n'));
        } catch (RuntimeException e) {
            sb.append("Tests indisponibles\n");
        }
        try {
            List<Vulnerability> vulns = vulnerabilityService.list("OPEN", null, null, null);
            sb.append("\n## Vulnérabilités ouvertes (").append(vulns.size()).append(")\n");
            vulns.stream().limit(10).forEach(v -> sb.append("- ").append(v.getId()).append(" [").append(v.getSeverity())
                    .append(", priorité ").append(v.getPriorityScore()).append("] ").append(v.getAffectedComponent())
                    .append(v.getFixedVersion() != null ? " (corrigée en " + v.getFixedVersion() + ")" : "")
                    .append(" - source ").append(v.getSource()).append('\n'));
        } catch (RuntimeException e) {
            sb.append("Vulnérabilités indisponibles\n");
        }
        return truncate(sb.toString(), 7000);
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
