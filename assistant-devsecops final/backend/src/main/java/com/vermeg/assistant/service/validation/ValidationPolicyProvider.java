/**
 * Role : construire la politique de validation effective d'une application a partir de la configuration.
 * Contenu : definition de ValidationPolicyProvider et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.domain.ManagedApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Politique par défaut lue dans application.yml (section assistant.validation), puis adaptée
 * à la criticité de l'application et à ses surcharges JSON. Chaque adaptation est notée pour
 * figurer dans la trace de décision du rapport.
 */
@Component
public class ValidationPolicyProvider {

    private final ObjectMapper objectMapper;

    @Value("${assistant.validation.weights.test-success:30}") private double wTests;
    @Value("${assistant.validation.weights.coverage:15}") private double wCoverage;
    @Value("${assistant.validation.weights.test-stability:10}") private double wStability;
    @Value("${assistant.validation.weights.security:25}") private double wSecurity;
    @Value("${assistant.validation.weights.log-health:15}") private double wLogs;
    @Value("${assistant.validation.weights.regression:5}") private double wRegression;

    @Value("${assistant.validation.min-success-rate:95}") private double minSuccessRate;
    @Value("${assistant.validation.blocking-success-rate:80}") private double blockingSuccessRate;
    @Value("${assistant.validation.min-line-coverage:50}") private double minCoverage;
    @Value("${assistant.validation.max-flaky-percent:5}") private double maxFlakyPercent;
    @Value("${assistant.validation.max-critical-vulnerabilities:0}") private long maxCriticalVulns;
    @Value("${assistant.validation.max-high-vulnerabilities:3}") private long maxHighVulns;
    @Value("${assistant.validation.max-critical-anomalies:3}") private long maxCriticalAnomalies;
    @Value("${assistant.validation.max-error-rate-percent:5}") private double maxErrorRate;
    @Value("${assistant.validation.max-test-age-hours:168}") private double maxTestAgeHours;
    @Value("${assistant.validation.block-on-critical-vulnerabilities:true}") private boolean blockOnCriticalVulns;
    @Value("${assistant.validation.block-on-low-success-rate:true}") private boolean blockOnLowSuccessRate;
    @Value("${assistant.validation.block-on-coverage:false}") private boolean blockOnCoverage;
    @Value("${assistant.validation.validate-score:80}") private double validateScore;
    @Value("${assistant.validation.reserves-score:60}") private double reservesScore;
    @Value("${assistant.validation.min-confidence:0.5}") private double minConfidence;
    @Value("${assistant.validation.always-review-critical:true}") private boolean alwaysReviewCritical;
    @Value("${assistant.validation.llm.enabled:true}") private boolean llmEnabled;
    @Value("${assistant.validation.llm.max-adjustment:5}") private double llmMaxAdjustment;
    @Value("${assistant.validation.llm.review-confidence:0.6}") private double llmReviewConfidence;
    @Value("${assistant.validation.regression.window:5}") private int regressionWindow;
    @Value("${assistant.validation.regression.tolerance:10}") private double regressionTolerance;

    public ValidationPolicyProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationPolicy base() {
        ValidationPolicy p = new ValidationPolicy();
        p.weights.put(ValidationPolicy.TEST_SUCCESS, wTests);
        p.weights.put(ValidationPolicy.COVERAGE, wCoverage);
        p.weights.put(ValidationPolicy.TEST_STABILITY, wStability);
        p.weights.put(ValidationPolicy.SECURITY, wSecurity);
        p.weights.put(ValidationPolicy.LOG_HEALTH, wLogs);
        p.weights.put(ValidationPolicy.REGRESSION, wRegression);
        p.minSuccessRate = minSuccessRate;
        p.blockingSuccessRate = blockingSuccessRate;
        p.minCoverage = minCoverage;
        p.maxFlakyPercent = maxFlakyPercent;
        p.maxCriticalVulns = maxCriticalVulns;
        p.maxHighVulns = maxHighVulns;
        p.maxCriticalAnomalies = maxCriticalAnomalies;
        p.maxErrorRatePercent = maxErrorRate;
        p.maxTestAgeHours = maxTestAgeHours;
        p.blockOnCriticalVulns = blockOnCriticalVulns;
        p.blockOnLowSuccessRate = blockOnLowSuccessRate;
        p.blockOnCoverage = blockOnCoverage;
        p.validateScore = validateScore;
        p.reservesScore = reservesScore;
        p.minConfidence = minConfidence;
        p.alwaysReviewCritical = alwaysReviewCritical;
        p.llmEnabled = llmEnabled;
        p.llmMaxAdjustment = llmMaxAdjustment;
        p.llmReviewConfidence = llmReviewConfidence;
        p.regressionWindow = regressionWindow;
        p.regressionTolerance = regressionTolerance;
        return p;
    }

    /** Politique effective d'une application ; {@code notes} reçoit la description de chaque adaptation. */
    public ValidationPolicy forApplication(ManagedApplication app, List<String> notes) {
        ValidationPolicy p = base().adaptTo(app.getCriticality(), notes);
        if (app.getPolicyOverrides() != null && !app.getPolicyOverrides().isBlank()) {
            try {
                p = p.withOverrides(objectMapper.readTree(app.getPolicyOverrides()), notes);
            } catch (Exception e) {
                notes.add("Surcharges de politique illisibles, ignorées : " + e.getMessage());
            }
        }
        return p;
    }
}
