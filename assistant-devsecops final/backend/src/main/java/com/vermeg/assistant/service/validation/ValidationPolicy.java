/**
 * Role : porter la politique de validation configurable (poids, seuils, regles bloquantes, bornes de l'IA).
 * Contenu : definition de ValidationPolicy et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.vermeg.assistant.model.ApplicationCriticality;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Politique de validation. Trois niveaux, appliqués dans cet ordre et tous tracés :
 * <ol>
 *   <li>valeurs par défaut (application.yml, section assistant.validation) ;</li>
 *   <li>adaptation à la criticité métier de l'application (plus stricte pour une application critique) ;</li>
 *   <li>surcharges propres à l'application (JSON saisi dans l'écran Applications).</li>
 * </ol>
 */
public class ValidationPolicy {

    public static final String TEST_SUCCESS = "TEST_SUCCESS";
    public static final String COVERAGE = "COVERAGE";
    public static final String TEST_STABILITY = "TEST_STABILITY";
    public static final String SECURITY = "SECURITY";
    public static final String LOG_HEALTH = "LOG_HEALTH";
    public static final String REGRESSION = "REGRESSION";
    public static final List<String> CRITERIA = List.of(TEST_SUCCESS, COVERAGE, TEST_STABILITY, SECURITY, LOG_HEALTH, REGRESSION);

    // Poids de base (relatifs, renormalisés sur les critères disponibles)
    public Map<String, Double> weights = new LinkedHashMap<>(Map.of(
            TEST_SUCCESS, 30.0, COVERAGE, 15.0, TEST_STABILITY, 10.0, SECURITY, 25.0, LOG_HEALTH, 15.0, REGRESSION, 5.0));

    // Seuils
    public double minSuccessRate = 95;
    /** En dessous de ce taux, l'échec des tests est bloquant (rejet). */
    public double blockingSuccessRate = 80;
    public double minCoverage = 50;
    public double maxFlakyPercent = 5;
    public long maxCriticalVulns = 0;
    public long maxHighVulns = 3;
    public long maxCriticalAnomalies = 3;
    public double maxErrorRatePercent = 5;
    public double maxTestAgeHours = 168;

    // Critères bloquants
    public boolean blockOnCriticalVulns = true;
    public boolean blockOnLowSuccessRate = true;
    public boolean blockOnCoverage = false;
    public boolean blockOnFatalServerErrors = false;

    // Preuves
    public int minTestsForFullConfidence = 30;
    public int minLogsForFullConfidence = 200;

    // Décision
    public double validateScore = 80;
    public double reservesScore = 60;
    public double minConfidence = 0.5;
    public double borderlineMargin = 3;
    /** Application CRITIQUE : une confirmation humaine est toujours exigée avant mise en production. */
    public boolean alwaysReviewCritical = true;

    // IA
    public boolean llmEnabled = true;
    public double llmMaxAdjustment = 5;
    public double llmReviewConfidence = 0.6;
    public int llmTrustMinSamples = 5;

    // Régression (référence = historique propre à l'application)
    public int regressionWindow = 5;
    public int regressionMinHistory = 3;
    public double regressionTolerance = 10;

    // Multiplicateurs de poids selon la criticité
    public double securityWeightFactor = 1.0;

    public ValidationPolicy copy() {
        ValidationPolicy p = new ValidationPolicy();
        p.weights = new LinkedHashMap<>(weights);
        p.minSuccessRate = minSuccessRate;
        p.blockingSuccessRate = blockingSuccessRate;
        p.minCoverage = minCoverage;
        p.maxFlakyPercent = maxFlakyPercent;
        p.maxCriticalVulns = maxCriticalVulns;
        p.maxHighVulns = maxHighVulns;
        p.maxCriticalAnomalies = maxCriticalAnomalies;
        p.maxErrorRatePercent = maxErrorRatePercent;
        p.maxTestAgeHours = maxTestAgeHours;
        p.blockOnCriticalVulns = blockOnCriticalVulns;
        p.blockOnLowSuccessRate = blockOnLowSuccessRate;
        p.blockOnCoverage = blockOnCoverage;
        p.blockOnFatalServerErrors = blockOnFatalServerErrors;
        p.minTestsForFullConfidence = minTestsForFullConfidence;
        p.minLogsForFullConfidence = minLogsForFullConfidence;
        p.validateScore = validateScore;
        p.reservesScore = reservesScore;
        p.minConfidence = minConfidence;
        p.borderlineMargin = borderlineMargin;
        p.alwaysReviewCritical = alwaysReviewCritical;
        p.llmEnabled = llmEnabled;
        p.llmMaxAdjustment = llmMaxAdjustment;
        p.llmReviewConfidence = llmReviewConfidence;
        p.llmTrustMinSamples = llmTrustMinSamples;
        p.regressionWindow = regressionWindow;
        p.regressionMinHistory = regressionMinHistory;
        p.regressionTolerance = regressionTolerance;
        p.securityWeightFactor = securityWeightFactor;
        return p;
    }

    /**
     * Adaptation à la criticité métier : renvoie la politique adaptée et la liste lisible des
     * ajustements (affichée dans la trace de décision).
     */
    public ValidationPolicy adaptTo(ApplicationCriticality criticality, List<String> notes) {
        ValidationPolicy p = copy();
        ApplicationCriticality c = criticality == null ? ApplicationCriticality.MEDIUM : criticality;
        switch (c) {
            case LOW -> {
                p.minSuccessRate = Math.max(0, minSuccessRate - 2);
                p.minCoverage = Math.max(0, minCoverage - 10);
                p.maxHighVulns = maxHighVulns + 2;
                notes.add("Criticité FAIBLE : seuils assouplis (réussite ≥ " + fmt(p.minSuccessRate) + " %, couverture ≥ "
                        + fmt(p.minCoverage) + " %, vulnérabilités hautes ≤ " + p.maxHighVulns + ").");
            }
            case MEDIUM -> notes.add("Criticité MOYENNE : politique de référence appliquée sans ajustement.");
            case HIGH -> {
                p.minSuccessRate = Math.min(100, minSuccessRate + 2);
                p.minCoverage = Math.min(100, minCoverage + 10);
                p.maxHighVulns = Math.max(0, maxHighVulns - 2);
                p.securityWeightFactor = 1.2;
                notes.add("Criticité HAUTE : réussite ≥ " + fmt(p.minSuccessRate) + " %, couverture ≥ " + fmt(p.minCoverage)
                        + " %, vulnérabilités hautes ≤ " + p.maxHighVulns + ", poids sécurité ×1,2.");
            }
            case CRITICAL -> {
                p.minSuccessRate = Math.min(100, minSuccessRate + 4);
                p.blockingSuccessRate = Math.min(100, blockingSuccessRate + 10);
                p.minCoverage = Math.min(100, minCoverage + 15);
                p.maxHighVulns = 0;
                p.maxCriticalAnomalies = Math.max(0, maxCriticalAnomalies - 2);
                p.blockOnFatalServerErrors = true;
                p.securityWeightFactor = 1.5;
                p.minConfidence = Math.max(minConfidence, 0.7);
                p.validateScore = Math.min(100, validateScore + 5);
                notes.add("Criticité CRITIQUE : réussite ≥ " + fmt(p.minSuccessRate) + " % (bloquant sous "
                        + fmt(p.blockingSuccessRate) + " %), couverture ≥ " + fmt(p.minCoverage)
                        + " %, aucune vulnérabilité haute, erreurs FATAL du serveur bloquantes, poids sécurité ×1,5, "
                        + "confiance minimale " + fmt(p.minConfidence * 100) + " %, validation à partir de " + fmt(p.validateScore) + "/100.");
            }
        }
        return p;
    }

    /** Surcharges JSON propres à l'application (clés inconnues signalées, jamais silencieuses). */
    public ValidationPolicy withOverrides(JsonNode overrides, List<String> notes) {
        if (overrides == null || !overrides.isObject() || overrides.size() == 0) return this;
        ValidationPolicy p = copy();
        List<String> applied = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = overrides.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            String key = f.getKey();
            JsonNode v = f.getValue();
            if ("weights".equals(key) && v.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> w = v.fields();
                while (w.hasNext()) {
                    Map.Entry<String, JsonNode> e = w.next();
                    String code = e.getKey().toUpperCase(Locale.ROOT);
                    if (CRITERIA.contains(code)) {
                        p.weights.put(code, Math.max(0, e.getValue().asDouble()));
                        applied.add("poids " + code + "=" + fmt(e.getValue().asDouble()));
                    } else {
                        applied.add("critère inconnu ignoré : " + e.getKey());
                    }
                }
                continue;
            }
            boolean known = true;
            switch (key) {
                case "minSuccessRate" -> p.minSuccessRate = v.asDouble(p.minSuccessRate);
                case "blockingSuccessRate" -> p.blockingSuccessRate = v.asDouble(p.blockingSuccessRate);
                case "minCoverage" -> p.minCoverage = v.asDouble(p.minCoverage);
                case "maxFlakyPercent" -> p.maxFlakyPercent = v.asDouble(p.maxFlakyPercent);
                case "maxCriticalVulns" -> p.maxCriticalVulns = v.asLong(p.maxCriticalVulns);
                case "maxHighVulns" -> p.maxHighVulns = v.asLong(p.maxHighVulns);
                case "maxCriticalAnomalies" -> p.maxCriticalAnomalies = v.asLong(p.maxCriticalAnomalies);
                case "maxErrorRatePercent" -> p.maxErrorRatePercent = v.asDouble(p.maxErrorRatePercent);
                case "maxTestAgeHours" -> p.maxTestAgeHours = v.asDouble(p.maxTestAgeHours);
                case "blockOnCriticalVulns" -> p.blockOnCriticalVulns = v.asBoolean(p.blockOnCriticalVulns);
                case "blockOnLowSuccessRate" -> p.blockOnLowSuccessRate = v.asBoolean(p.blockOnLowSuccessRate);
                case "blockOnCoverage" -> p.blockOnCoverage = v.asBoolean(p.blockOnCoverage);
                case "blockOnFatalServerErrors" -> p.blockOnFatalServerErrors = v.asBoolean(p.blockOnFatalServerErrors);
                case "validateScore" -> p.validateScore = v.asDouble(p.validateScore);
                case "reservesScore" -> p.reservesScore = v.asDouble(p.reservesScore);
                case "minConfidence" -> p.minConfidence = v.asDouble(p.minConfidence);
                case "llmEnabled" -> p.llmEnabled = v.asBoolean(p.llmEnabled);
                case "llmMaxAdjustment" -> p.llmMaxAdjustment = Math.max(0, Math.min(20, v.asDouble(p.llmMaxAdjustment)));
                case "regressionTolerance" -> p.regressionTolerance = v.asDouble(p.regressionTolerance);
                default -> known = false;
            }
            applied.add(known ? key + "=" + v.asText() : "paramètre inconnu ignoré : " + key);
        }
        notes.add("Surcharges propres à l'application : " + String.join(", ", applied) + ".");
        return p;
    }

    /** Instantané lisible des seuils effectivement utilisés (joint au rapport). */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Réussite des tests minimale", fmt(minSuccessRate) + " %");
        m.put("Réussite bloquante en dessous de", fmt(blockingSuccessRate) + " %");
        m.put("Couverture de lignes minimale", fmt(minCoverage) + " %" + (blockOnCoverage ? " (bloquant)" : ""));
        m.put("Tests instables maximum", fmt(maxFlakyPercent) + " % des tests");
        m.put("Vulnérabilités critiques ouvertes maximum", maxCriticalVulns + (blockOnCriticalVulns ? " (bloquant)" : ""));
        m.put("Vulnérabilités hautes ouvertes maximum", String.valueOf(maxHighVulns));
        m.put("Anomalies de logs critiques maximum", String.valueOf(maxCriticalAnomalies));
        m.put("Taux d'erreur des logs maximum", fmt(maxErrorRatePercent) + " %");
        m.put("Ancienneté maximale des tests", fmt(maxTestAgeHours) + " h");
        m.put("Erreurs FATAL du serveur bloquantes", blockOnFatalServerErrors ? "oui" : "non");
        m.put("Score de validation", "≥ " + fmt(validateScore) + " (réserves ≥ " + fmt(reservesScore) + ")");
        m.put("Confiance minimale des preuves", fmt(minConfidence * 100) + " %");
        m.put("Ajustement maximal par l'IA", "± " + fmt(llmMaxAdjustment) + " points" + (llmEnabled ? "" : " (IA désactivée)"));
        m.put("Poids", weights.entrySet().stream().map(e -> e.getKey() + "=" + fmt(e.getValue()))
                .reduce((a, b) -> a + ", " + b).orElse("") + (securityWeightFactor != 1.0 ? " (sécurité ×" + fmt(securityWeightFactor) + ")" : ""));
        return m;
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.FRANCE, "%.1f", v);
    }
}
