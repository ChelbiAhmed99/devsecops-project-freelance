/**
 * Role : verifier le moteur de validation hybride (criteres, regles bloquantes, IA bornee, adaptation, rapports).
 * Contenu : tests unitaires de AdaptiveValidationEngine, ValidationPolicy, LlmAssessmentParser et ValidationReportRenderer.
 */

package com.vermeg.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.ApplicationCriticality;
import com.vermeg.assistant.model.ValidationModels;
import com.vermeg.assistant.model.ValidationModels.CriterionResult;
import com.vermeg.assistant.model.ValidationModels.LlmAssessment;
import com.vermeg.assistant.model.ValidationModels.ValidationOutcome;
import com.vermeg.assistant.model.ValidationModels.Verdict;
import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.service.validation.AdaptiveValidationEngine;
import com.vermeg.assistant.service.validation.LlmAssessmentParser;
import com.vermeg.assistant.service.validation.ValidationEvidence;
import com.vermeg.assistant.service.validation.ValidationInsights;
import com.vermeg.assistant.service.validation.ValidationPolicy;
import com.vermeg.assistant.service.validation.ValidationReportRenderer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveValidationEngineTests {

    private final AdaptiveValidationEngine engine = new AdaptiveValidationEngine();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Application saine : 200 tests à 100 %, couverture 75 %, aucune vulnérabilité, logs propres. */
    private ValidationEvidence healthy() {
        ValidationEvidence ev = new ValidationEvidence();
        ev.applicationCode = "portail";
        ev.applicationName = "Portail";
        ev.testsAvailable = true;
        ev.testRunAt = LocalDateTime.now().minusHours(2);
        ev.totalTests = 200;
        ev.failedTests = 0;
        ev.successRate = 100;
        ev.lineCoverage = 75.0;
        for (int i = 0; i < 4; i++) ev.testTrend.add(new ApiDtos.TestRunPoint((long) i, null, 200, 0, 100, 75.0, "CI", "main"));
        ev.previousRunAvailable = true;
        ev.securityScanned = true;
        ev.openBySeverity = Map.of("CRITICAL", 0L, "HIGH", 0L, "MEDIUM", 1L, "LOW", 2L);
        ev.openVulns = 3;
        ev.logsAvailable = true;
        ev.logCount = 5000;
        ev.errorCount = 20;
        ev.errorRatePercent = 0.4;
        return ev;
    }

    private ValidationOutcome run(ValidationEvidence ev, ValidationPolicy p, LlmAssessment llm) {
        List<CriterionResult> criteria = engine.evaluateCriteria(ev, p);
        return engine.decide(criteria, p, llm, List.of(), ev);
    }

    private LlmAssessment llm(String opinion, double confidence, double adjustment) {
        return new LlmAssessment("OK", opinion, confidence, adjustment, 0, 0, "ok", List.of(), List.of(), List.of(), List.of(),
                "heuristic", "rules", null);
    }

    @Test
    void healthyApplicationIsValidatedWithExplainedScore() {
        ValidationOutcome o = run(healthy(), new ValidationPolicy(), null);
        assertThat(o.verdict()).isEqualTo(Verdict.VALIDATED);
        assertThat(o.score()).isGreaterThanOrEqualTo(80);
        assertThat(o.blockingReasons()).isEmpty();
        assertThat(o.trace()).anyMatch(s -> s.rule().startsWith("R1") && "AUCUN".equals(s.outcome()));
        assertThat(o.trace()).anyMatch(s -> s.rule().startsWith("R4") && "SANS EFFET".equals(s.outcome()));
    }

    @Test
    void criticalVulnerabilityIsBlockingEvenIfTheLlmRecommendsValidation() {
        ValidationEvidence ev = healthy();
        ev.openBySeverity = Map.of("CRITICAL", 1L, "HIGH", 0L);
        ValidationOutcome o = run(ev, new ValidationPolicy(), llm("VALIDATE", 0.9, 10));
        assertThat(o.verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(o.blockingReasons()).hasSize(1);
        // l'IA est en désaccord avec une confiance élevée : revue humaine obligatoire
        assertThat(o.reviewRequired()).isTrue();
        assertThat(o.reviewReasons()).anyMatch(r -> r.startsWith("Désaccord"));
    }

    @Test
    void missingEvidenceLeadsToInconclusiveNotValidated() {
        ValidationEvidence ev = new ValidationEvidence();
        ev.applicationCode = "vide";
        ev.logsAvailable = true;
        ev.logCount = 10;
        ValidationOutcome o = run(ev, new ValidationPolicy(), null);
        assertThat(o.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(o.confidence()).isLessThan(0.5);
        assertThat(o.reviewRequired()).isTrue();
    }

    @Test
    void lowSuccessRateIsBlocking() {
        ValidationEvidence ev = healthy();
        ev.failedTests = 50;
        ev.successRate = 75;
        ValidationOutcome o = run(ev, new ValidationPolicy(), null);
        assertThat(o.verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(o.blockingReasons()).anyMatch(b -> b.startsWith("Réussite des tests"));
    }

    @Test
    void llmAdjustmentIsBoundedAndReducedByLowHumanAgreement() {
        ValidationEvidence ev = healthy();
        ev.successRate = 93; // sous le seuil de 95 % : réserves probables
        ev.failedTests = 14;
        ValidationOutcome full = run(ev, new ValidationPolicy(), llm("VALIDATE", 0.5, 20));
        assertThat(full.llmAdjustment()).isEqualTo(5.0);
        assertThat(full.score()).isEqualTo(Math.min(100, full.baseScore() + 5));

        ev.llmTrust = 0.4;
        ev.llmTrustSamples = 10;
        ValidationOutcome reduced = run(ev, new ValidationPolicy(), llm("VALIDATE", 0.5, 20));
        assertThat(reduced.llmAdjustment()).isEqualTo(2.0);
        assertThat(reduced.llm().allowedAdjustment()).isEqualTo(2.0);
    }

    @Test
    void llmCannotLiftInconclusiveDecision() {
        ValidationEvidence ev = new ValidationEvidence();
        ValidationOutcome o = run(ev, new ValidationPolicy(), llm("VALIDATE", 0.95, 5));
        assertThat(o.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void hallucinatedReferencesMakeTheLlmOpinionIgnored() throws Exception {
        String json = "{\"opinion\":\"REJECT\",\"confidence\":0.8,\"adjustment\":-5,"
                + "\"justification\":\"La faille CVE-2099-12345 est critique\",\"risks\":[],\"recommendations\":[\"Corriger test:FooTest#bar\"],"
                + "\"cited_refs\":[\"CVE-2021-44228\"]}";
        LlmAssessment parsed = LlmAssessmentParser.parse(mapper.readTree(json),
                Set.of("vuln:dependency-check:CVE-2021-44228:log4j-core-2.14.1.jar"));
        assertThat(parsed.ungroundedRefs()).contains("CVE-2099-12345", "test:FooTest#bar");
        assertThat(parsed.ungroundedRefs()).doesNotContain("CVE-2021-44228");

        ValidationOutcome o = run(healthy(), new ValidationPolicy(), parsed);
        assertThat(o.llm().status()).isEqualTo("IGNORED");
        assertThat(o.llmAdjustment()).isEqualTo(0.0);
        assertThat(o.reviewRequired()).isTrue();
    }

    @Test
    void parserRejectsUnknownOpinionAndClampsValues() throws Exception {
        LlmAssessment parsed = LlmAssessmentParser.parse(mapper.readTree("{\"opinion\":\"SHIP IT\",\"confidence\":7,\"adjustment\":999}"), Set.of());
        assertThat(parsed.opinion()).isNull();
        assertThat(parsed.confidence()).isEqualTo(1.0);
        assertThat(parsed.proposedAdjustment()).isEqualTo(50.0);
    }

    @Test
    void criticalApplicationsGetStricterThresholdsAndSystematicReview() {
        ValidationEvidence ev = healthy();
        ev.successRate = 96;
        ev.failedTests = 8;
        List<String> notes = new ArrayList<>();
        ValidationPolicy medium = new ValidationPolicy().adaptTo(ApplicationCriticality.MEDIUM, notes);
        ValidationPolicy critical = new ValidationPolicy().adaptTo(ApplicationCriticality.CRITICAL, notes);
        assertThat(critical.minSuccessRate).isEqualTo(99.0);
        assertThat(critical.maxHighVulns).isEqualTo(0L);

        CriterionResult mediumTests = engine.evaluateCriteria(ev, medium).get(0);
        CriterionResult criticalTests = engine.evaluateCriteria(ev, critical).get(0);
        assertThat(mediumTests.status()).isEqualTo("PASS");
        assertThat(criticalTests.status()).isEqualTo("WARN");

        ev.criticality = ApplicationCriticality.CRITICAL;
        ValidationOutcome o = engine.decide(engine.evaluateCriteria(healthy(), critical), critical, null, notes, ev);
        assertThat(o.reviewRequired()).isTrue();
        assertThat(o.trace()).anyMatch(s -> s.rule().startsWith("R0") && s.detail().contains("CRITIQUE"));
    }

    @Test
    void regressionIsMeasuredAgainstTheApplicationsOwnHistory() {
        ValidationEvidence ev = healthy();
        ev.previousScores = List.of(97.0, 96.0, 98.0);
        ev.lineCoverage = 20.0;
        ev.openBySeverity = Map.of("HIGH", 3L);
        CriterionResult regression = engine.evaluateCriteria(ev, new ValidationPolicy()).stream()
                .filter(c -> c.code().equals(ValidationPolicy.REGRESSION)).findFirst().orElseThrow();
        assertThat(regression.status()).isEqualTo("WARN");
        assertThat(regression.justification()).contains("médiane");

        ValidationEvidence first = healthy();
        first.previousRunAvailable = false;
        CriterionResult none = engine.evaluateCriteria(first, new ValidationPolicy()).stream()
                .filter(c -> c.code().equals(ValidationPolicy.REGRESSION)).findFirst().orElseThrow();
        assertThat(none.status()).isEqualTo("NO_DATA");
    }

    @Test
    void newFailingTestsAreFlaggedAsRegression() {
        ValidationEvidence ev = healthy();
        ev.newFailures = List.of("test:PaymentTest#refund");
        CriterionResult regression = engine.evaluateCriteria(ev, new ValidationPolicy()).get(5);
        assertThat(regression.code()).isEqualTo(ValidationPolicy.REGRESSION);
        assertThat(regression.score()).isEqualTo(75.0);
    }

    @Test
    void applicationOverridesAreAppliedAndTraced() throws Exception {
        List<String> notes = new ArrayList<>();
        ValidationPolicy p = new ValidationPolicy().withOverrides(mapper.readTree(
                "{\"minCoverage\": 80, \"blockOnCoverage\": true, \"weights\": {\"SECURITY\": 50}, \"foo\": 1}"), notes);
        assertThat(p.minCoverage).isEqualTo(80.0);
        assertThat(p.weights.get("SECURITY")).isEqualTo(50.0);
        assertThat(notes.get(0)).contains("minCoverage=80", "paramètre inconnu ignoré : foo");
        ValidationOutcome o = run(healthy(), p, null);
        assertThat(o.verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(o.blockingReasons()).anyMatch(b -> b.startsWith("Couverture"));
    }

    @Test
    void pointsLostExplainTheGapToOneHundred() {
        ValidationEvidence ev = healthy();
        ev.lineCoverage = 30.0;
        List<CriterionResult> criteria = engine.evaluateCriteria(ev, new ValidationPolicy());
        double lost = criteria.stream().mapToDouble(CriterionResult::pointsLost).sum();
        assertThat(Math.abs((100 - engine.score(criteria)) - lost) < 0.5).isTrue();
    }

    @Test
    void insightsPrioritiseSecurityFixesAndReportsRenderEveryApplicationSection() {
        ValidationEvidence ev = healthy();
        ev.openBySeverity = Map.of("CRITICAL", 1L);
        Vulnerability v = new Vulnerability("CVE-2021-44228", "dependency-check", "log4j-core-2.14.1.jar",
                Vulnerability.Severity.CRITICAL, 10.0, "Log4Shell <script>", Vulnerability.Status.NEW);
        v.setFixedVersion("2.17.1");
        v.setPriorityScore(100);
        ev.topVulnerabilities = List.of(v);
        ValidationPolicy p = new ValidationPolicy();
        List<CriterionResult> criteria = engine.evaluateCriteria(ev, p);
        ValidationOutcome o = engine.decide(criteria, p, null, List.of("note"), ev);
        ValidationInsights insights = new ValidationInsights();
        List<ValidationModels.Recommendation> recs = insights.recommendations(ev, criteria, o);
        assertThat(recs.get(0).action()).contains("CVE-2021-44228", "2.17.1");
        assertThat(recs.get(0).priority()).isEqualTo(1);
        assertThat(insights.risks(ev, criteria, o).get(0).level()).isEqualTo("CRITICAL");

        ValidationModels.ApplicationValidationReport report = new ValidationModels.ApplicationValidationReport(
                "portail", "Portail <Paiement>", "HIGH", "équipe A", "WILDFLY", 7L, 12L, LocalDateTime.now(), "qualite",
                "LATEST_RESULTS", o.verdict(), o.score(), o.baseScore(), o.llmAdjustment(), o.confidence(), o.reviewRequired(),
                o.reviewReasons(), o.blockingReasons(), criteria, o.trace(), p.snapshot(),
                new ValidationModels.TestSection(true, 1L, LocalDateTime.now(), "CI", 200, 0, 0, 100, 75.0, null, 12, 2, List.of(),
                        List.of(), List.of(), List.of(), List.of()),
                new ValidationModels.LogSection(true, 5000, 20, 3, 0.4, Map.of("SERVER", 100L), Map.of("SERVER", 5L), 0, 0, List.of(), List.of()),
                List.of(), new ValidationModels.SecuritySection(true, 1, Map.of("CRITICAL", 1L), List.of(v)),
                insights.risks(ev, criteria, o), recs, o.llm(), 88.0, "VALIDATED");
        String md = ValidationReportRenderer.markdown(report);
        String html = ValidationReportRenderer.html(report);
        for (String section : List.of("1. Décision", "2. Explication du score", "3. Raisonnement", "4. Tests", "5. Logs",
                "6. Incidents", "7. Sécurité", "8. Risques", "9. Recommandations", "10. Analyse IA", "11. Politique", "12. Décision humaine")) {
            assertThat(md).contains(section);
            assertThat(html).contains(section);
        }
        // échappement HTML des données
        assertThat(html).contains("Portail &lt;Paiement&gt;").doesNotContain("<script>");
        String updated = ValidationReportRenderer.replaceHuman(html,
                ValidationReportRenderer.humanHtml("OVERRIDDEN", "Validée avec réserves", "qualite", LocalDateTime.now(), "dérogation"));
        assertThat(updated).contains("Décision corrigée", "dérogation").doesNotContain("En attente de validation");
        String updatedMd = ValidationReportRenderer.replaceHuman(md,
                ValidationReportRenderer.humanMarkdown("CONFIRMED", "Rejetée", "qualite", LocalDateTime.now(), null));
        assertThat(updatedMd).contains("Décision confirmée").doesNotContain("En attente de validation");
    }
}
