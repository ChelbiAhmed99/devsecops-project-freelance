/**
 * Role : definir les donnees de la validation hybride (criteres, decision, avis IA, rapport par application).
 * Contenu : definition de ValidationModels et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Modèle de la validation d'une application. Principe directeur : la décision est
 * <b>déterministe et explicable</b> (critères mesurés, poids, seuils, règles tracées) ;
 * l'avis du LLM est consultatif et <b>borné</b> ; un humain confirme ou corrige.
 */
public final class ValidationModels {

    private ValidationModels() {
    }

    /** Décision possible pour une application. */
    public enum Verdict {
        VALIDATED, VALIDATED_WITH_RESERVES, REJECTED, INCONCLUSIVE;

        public int rank() {
            return switch (this) {
                case VALIDATED -> 3;
                case VALIDATED_WITH_RESERVES -> 2;
                case INCONCLUSIVE -> 1;
                case REJECTED -> 0;
            };
        }

        public String label() {
            return switch (this) {
                case VALIDATED -> "Validée";
                case VALIDATED_WITH_RESERVES -> "Validée avec réserves";
                case REJECTED -> "Rejetée";
                case INCONCLUSIVE -> "Non concluante";
            };
        }
    }

    /**
     * Résultat d'un critère mesurable. {@code score} 0-100 ; {@code effectiveWeight} = poids de base
     * × multiplicateur de criticité × confiance des preuves ; {@code pointsLost} = part du score
     * global perdue à cause de ce critère (explication « en cascade » du score).
     */
    public record CriterionResult(String code, String label, String category, String status, double score,
                                  double baseWeight, double effectiveWeight, double evidenceConfidence,
                                  boolean blocking, boolean blockingFailed, String measured, String threshold,
                                  String justification, String adaptation, double pointsLost) { }

    /** Étape tracée du raisonnement de décision (règle appliquée et son effet). */
    public record DecisionStep(String rule, String outcome, String detail) { }

    /**
     * Avis du LLM. {@code proposedAdjustment} est ce que le modèle suggère, {@code appliedAdjustment}
     * ce que la politique autorise réellement (borné, et nul si l'avis n'est pas ancré dans les preuves).
     */
    public record LlmAssessment(String status, String opinion, double confidence, double proposedAdjustment,
                                double appliedAdjustment, double allowedAdjustment, String justification,
                                List<String> risks, List<String> recommendations, List<String> citedRefs,
                                List<String> ungroundedRefs, String provider, String model, String note) {

        public static LlmAssessment unavailable(String status, String note) {
            return new LlmAssessment(status, null, 0, 0, 0, 0, null, List.of(), List.of(), List.of(), List.of(),
                    null, null, note);
        }

        public LlmAssessment withApplied(double applied, double allowed, String newNote) {
            return new LlmAssessment(status, opinion, confidence, proposedAdjustment, applied, allowed, justification,
                    risks, recommendations, citedRefs, ungroundedRefs, provider, model, newNote);
        }

        public boolean usable() {
            return "USED".equals(status) || "IGNORED".equals(status);
        }
    }

    /** Résultat de la décision. */
    public record ValidationOutcome(Verdict verdict, double score, double baseScore, double llmAdjustment,
                                    double confidence, boolean reviewRequired, List<String> reviewReasons,
                                    List<String> blockingReasons, List<DecisionStep> trace, LlmAssessment llm) { }

    public record RiskItem(String level, String title, String detail, String source) { }

    public record Recommendation(int priority, String action, String rationale, String source, String ref) { }

    /** Échec de test présenté dans le rapport, avec les logs les plus proches. */
    public record TestFailureView(String ref, String suite, String test, String status, String message,
                                  String stackExcerpt, List<String> relatedLogs, boolean newFailure) { }

    public record TestSection(boolean available, Long runId, LocalDateTime executedAt, String trigger, int total,
                              int failed, int skipped, double successRate, Double lineCoverage, Double branchCoverage,
                              double durationSeconds, double ageHours, List<TestFailureView> failures,
                              List<String> newFailures, List<String> fixedTests, List<ApiDtos.FlakyTest> flaky,
                              List<ApiDtos.TestRunPoint> trend) { }

    public record AnomalyView(String key, String message, String level, String layer, String category,
                              String errorCode, int occurrences, boolean critical, List<String> sources,
                              LocalDateTime lastSeen, String example) { }

    public record LogSection(boolean available, long total, long errors, long warnings, double errorRatePercent,
                             Map<String, Long> byLayer, Map<String, Long> errorsByLayer, int criticalAnomalies,
                             int spikes, List<AnomalyView> anomalies, List<String> excerpts) { }

    public record SecuritySection(boolean available, long open, Map<String, Long> openBySeverity,
                                  List<Vulnerability> top) { }

    /** Rapport complet d'UNE application (un rapport par application, jamais un rapport global unique). */
    public record ApplicationValidationReport(String applicationCode, String applicationName, String criticality,
                                              String owner, String serverType, Long campaignId, Long validationId,
                                              LocalDateTime generatedAt, String generatedBy, String mode,
                                              Verdict verdict, double score, double baseScore, double llmAdjustment,
                                              double confidence, boolean reviewRequired, List<String> reviewReasons,
                                              List<String> blockingReasons, List<CriterionResult> criteria,
                                              List<DecisionStep> trace, Map<String, String> policy,
                                              TestSection tests, LogSection logs,
                                              List<Diagnostics.CorrelatedIncident> incidents,
                                              SecuritySection security, List<RiskItem> risks,
                                              List<Recommendation> recommendations, LlmAssessment llm,
                                              Double previousScore, String previousVerdict) { }
}
