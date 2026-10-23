/**
 * Role : calculer les criteres mesurables, le score et la decision explicable d'une application.
 * Contenu : definition de AdaptiveValidationEngine et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.validation;

import com.vermeg.assistant.model.ApplicationCriticality;
import com.vermeg.assistant.model.Diagnostics;
import com.vermeg.assistant.model.ValidationModels.CriterionResult;
import com.vermeg.assistant.model.ValidationModels.DecisionStep;
import com.vermeg.assistant.model.ValidationModels.LlmAssessment;
import com.vermeg.assistant.model.ValidationModels.ValidationOutcome;
import com.vermeg.assistant.model.ValidationModels.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Moteur de validation <b>hybride</b> et <b>adaptatif</b>.
 * <p>
 * Étape 1 — critères mesurables : six critères (réussite des tests, couverture, stabilité, sécurité,
 * santé des logs, régression) notés de 0 à 100, chacun avec sa valeur mesurée, son seuil, sa
 * justification et un indice de confiance des preuves (peu de tests = critère moins fiable).
 * <p>
 * Étape 2 — décision déterministe et tracée : règles bloquantes, puis confiance minimale, puis
 * seuils de score. L'avis du LLM ne peut qu'ajuster le score dans une marge bornée (et réduite
 * si l'IA a souvent été contredite par les humains) ; il ne lève jamais une règle bloquante.
 * Tout désaccord ou cas limite déclenche une revue humaine obligatoire.
 */
public class AdaptiveValidationEngine {

    public List<CriterionResult> evaluateCriteria(ValidationEvidence ev, ValidationPolicy p) {
        List<Partial> partials = new ArrayList<>();
        partials.add(testSuccess(ev, p));
        partials.add(coverage(ev, p));
        partials.add(stability(ev, p));
        partials.add(security(ev, p));
        partials.add(logHealth(ev, p));
        // La régression compare le score provisoire (sans elle) à l'historique de l'application.
        double provisional = aggregate(partials);
        partials.add(regression(ev, p, provisional));

        double totalEffective = partials.stream().filter(x -> !x.noData()).mapToDouble(Partial::effectiveWeight).sum();
        List<CriterionResult> results = new ArrayList<>();
        for (Partial x : partials) {
            double lost = x.noData() || totalEffective == 0 ? 0 : round1(x.effectiveWeight() / totalEffective * (100 - x.score));
            results.add(new CriterionResult(x.code, x.label, x.category, x.status, round1(x.score), x.baseWeight,
                    round2(x.noData() ? 0 : x.effectiveWeight()), round2(x.confidence), x.blocking, x.blockingFailed,
                    x.measured, x.threshold, x.justification, x.adaptation, lost));
        }
        return results;
    }

    /** Score pondéré (0-100) sur les critères disposant de données. */
    public double score(List<CriterionResult> criteria) {
        double w = 0;
        double s = 0;
        for (CriterionResult c : criteria) {
            if ("NO_DATA".equals(c.status())) continue;
            w += c.effectiveWeight();
            s += c.effectiveWeight() * c.score();
        }
        return w == 0 ? 0 : round1(s / w);
    }

    /** Confiance globale (0-1) : part du poids total couverte par des preuves fiables. */
    public double confidence(List<CriterionResult> criteria) {
        double total = criteria.stream().mapToDouble(CriterionResult::baseWeight).sum();
        if (total == 0) return 0;
        double covered = criteria.stream().filter(c -> !"NO_DATA".equals(c.status()))
                .mapToDouble(c -> c.baseWeight() * c.evidenceConfidence()).sum();
        return round2(covered / total);
    }

    public ValidationOutcome decide(List<CriterionResult> criteria, ValidationPolicy p, LlmAssessment llm,
                                    List<String> policyNotes, ValidationEvidence ev) {
        List<DecisionStep> trace = new ArrayList<>();
        for (String note : policyNotes) trace.add(new DecisionStep("R0 · Politique adaptative", "ADAPTÉE", note));

        double baseScore = score(criteria);
        double confidence = confidence(criteria);

        // R1 — critères bloquants : rejet, quel que soit le score ou l'avis de l'IA.
        List<String> blocking = criteria.stream().filter(CriterionResult::blockingFailed)
                .map(c -> c.label() + " : " + c.measured() + " (seuil " + c.threshold() + ")").toList();
        if (blocking.isEmpty()) {
            trace.add(new DecisionStep("R1 · Critères bloquants", "AUCUN", "Aucun critère bloquant n'est en échec."));
        } else {
            blocking.forEach(b -> trace.add(new DecisionStep("R1 · Critères bloquants", "REJET", b)));
        }

        // R2 — confiance minimale des preuves.
        boolean lowConfidence = confidence < p.minConfidence;
        String missing = criteria.stream().filter(c -> "NO_DATA".equals(c.status())).map(CriterionResult::label)
                .collect(Collectors.joining(", "));
        trace.add(new DecisionStep("R2 · Confiance des preuves", lowConfidence ? "INSUFFISANTE" : "SUFFISANTE",
                "Confiance " + pct(confidence) + " (minimum " + pct(p.minConfidence) + ")"
                        + (missing.isEmpty() ? "." : ". Sans données : " + missing + ".")));

        // R3 — décision déterministe par le score.
        Verdict base = blocking.isEmpty() ? (lowConfidence ? Verdict.INCONCLUSIVE : byScore(baseScore, p)) : Verdict.REJECTED;
        trace.add(new DecisionStep("R3 · Score déterministe", base.name(), "Score " + fmt(baseScore) + "/100 (validation ≥ "
                + fmt(p.validateScore) + ", réserves ≥ " + fmt(p.reservesScore) + ") → " + base.label()
                + (blocking.isEmpty() ? "" : " (imposé par R1)") + (lowConfidence && blocking.isEmpty() ? " (imposé par R2)" : "") + "."));

        // R4 — avis de l'IA, borné et conditionné à son ancrage dans les preuves.
        LlmAssessment effectiveLlm = llm;
        double applied = 0;
        if (llm == null || !"OK".equals(llm.status())) {
            String why = llm == null ? "non demandé" : llm.note() == null ? llm.status() : llm.note();
            trace.add(new DecisionStep("R4 · Avis IA (consultatif)", "SANS EFFET", "Avis IA indisponible ou désactivé : " + why + "."));
            effectiveLlm = llm == null ? LlmAssessment.unavailable("DISABLED", "Analyse IA non demandée") : llm;
        } else {
            double allowed = round1(p.llmMaxAdjustment * clamp(ev.llmTrust, 0, 1));
            if (!llm.ungroundedRefs().isEmpty()) {
                effectiveLlm = new LlmAssessment("IGNORED", llm.opinion(), llm.confidence(), llm.proposedAdjustment(), 0,
                        allowed, llm.justification(), llm.risks(), llm.recommendations(), llm.citedRefs(), llm.ungroundedRefs(),
                        llm.provider(), llm.model(), "Avis ignoré : il cite des éléments absents des preuves ("
                        + String.join(", ", llm.ungroundedRefs()) + ").");
                trace.add(new DecisionStep("R4 · Avis IA (consultatif)", "IGNORÉ", effectiveLlm.note()));
            } else {
                applied = round1(clamp(llm.proposedAdjustment(), -allowed, allowed));
                String trustNote = ev.llmTrustSamples >= p.llmTrustMinSamples
                        ? " Marge réduite à ± " + fmt(allowed) + " car l'IA a été confirmée par les humains dans "
                        + pct(ev.llmTrust) + " des " + ev.llmTrustSamples + " dernières décisions."
                        : " Marge maximale ± " + fmt(allowed) + " point(s).";
                effectiveLlm = new LlmAssessment("USED", llm.opinion(), llm.confidence(), llm.proposedAdjustment(), applied,
                        allowed, llm.justification(), llm.risks(), llm.recommendations(), llm.citedRefs(), llm.ungroundedRefs(),
                        llm.provider(), llm.model(), "Ajustement proposé " + signed(llm.proposedAdjustment()) + ", appliqué "
                        + signed(applied) + "." + trustNote);
                trace.add(new DecisionStep("R4 · Avis IA (consultatif)", applied == 0 ? "SANS EFFET" : "AJUSTEMENT " + signed(applied),
                        "Avis « " + opinionLabel(llm.opinion()) + " » (confiance " + pct(llm.confidence()) + "). "
                                + effectiveLlm.note() + " L'IA ne peut lever ni une règle bloquante ni un manque de preuves."));
            }
        }
        double finalScore = round1(clamp(baseScore + applied, 0, 100));
        Verdict verdict = blocking.isEmpty() ? (lowConfidence ? Verdict.INCONCLUSIVE : byScore(finalScore, p)) : Verdict.REJECTED;

        // R5 — revue humaine obligatoire dans les cas sensibles.
        List<String> review = new ArrayList<>();
        if (verdict != base) {
            review.add("L'avis IA fait passer la décision de « " + base.label() + " » à « " + verdict.label() + " ».");
        }
        if ("USED".equals(effectiveLlm.status()) || "IGNORED".equals(effectiveLlm.status())) {
            Verdict llmVerdict = opinionVerdict(effectiveLlm.opinion());
            if (llmVerdict != null && llmVerdict != verdict && effectiveLlm.confidence() >= p.llmReviewConfidence) {
                review.add("Désaccord : l'IA recommande « " + llmVerdict.label() + " » (confiance " + pct(effectiveLlm.confidence())
                        + ") alors que la décision est « " + verdict.label() + " ».");
            }
            if ("IGNORED".equals(effectiveLlm.status())) review.add("Avis IA non ancré dans les preuves : à examiner.");
        }
        if (verdict == Verdict.INCONCLUSIVE) review.add("Preuves insuffisantes : un humain doit compléter ou trancher.");
        if (blocking.isEmpty() && !lowConfidence && (Math.abs(finalScore - p.validateScore) <= p.borderlineMargin
                || Math.abs(finalScore - p.reservesScore) <= p.borderlineMargin)) {
            review.add("Score proche d'un seuil de décision (" + fmt(finalScore) + "/100) : cas limite.");
        }
        if (p.alwaysReviewCritical && ev.criticality == ApplicationCriticality.CRITICAL && verdict != Verdict.REJECTED) {
            review.add("Application critique : confirmation humaine systématique avant mise en production.");
        }
        trace.add(new DecisionStep("R5 · Revue humaine", review.isEmpty() ? "RECOMMANDÉE" : "OBLIGATOIRE",
                review.isEmpty() ? "Décision automatique cohérente : confirmation humaine recommandée mais non bloquante."
                        : String.join(" ", review)));
        trace.add(new DecisionStep("Décision", verdict.name(), verdict.label() + " · score " + fmt(finalScore) + "/100 · confiance "
                + pct(confidence) + "."));
        return new ValidationOutcome(verdict, finalScore, baseScore, applied, confidence, !review.isEmpty(), review,
                blocking, trace, effectiveLlm);
    }

    // ----------------------------------------------------------------- critères

    private static final class Partial {
        String code;
        String label;
        String category;
        String status = "PASS";
        double score = 100;
        double baseWeight;
        double weightFactor = 1.0;
        double confidence = 1.0;
        boolean blocking;
        boolean blockingFailed;
        String measured = "—";
        String threshold = "—";
        String justification = "";
        String adaptation;

        Partial(String code, String label, String category, ValidationPolicy p) {
            this.code = code;
            this.label = label;
            this.category = category;
            this.baseWeight = p.weights.getOrDefault(code, 0.0);
        }

        boolean noData() {
            return "NO_DATA".equals(status);
        }

        double effectiveWeight() {
            return baseWeight * weightFactor * confidence;
        }

        Partial noData(String why) {
            status = "NO_DATA";
            score = 0;
            confidence = 0;
            measured = "aucune donnée";
            justification = why;
            return this;
        }
    }

    private Partial testSuccess(ValidationEvidence ev, ValidationPolicy p) {
        Partial x = new Partial(ValidationPolicy.TEST_SUCCESS, "Réussite des tests", "TESTS", p);
        x.blocking = p.blockOnLowSuccessRate;
        x.threshold = "≥ " + fmt(p.minSuccessRate) + " %" + (p.blockOnLowSuccessRate ? " (bloquant < " + fmt(p.blockingSuccessRate) + " %)" : "");
        int executed = ev.totalTests - ev.skippedTests;
        if (!ev.testsAvailable || executed <= 0) return x.noData("Aucun résultat de tests pour cette application : importez des rapports JUnit ou lancez les tests.");
        x.confidence = testConfidence(ev, p, executed);
        double rate = ev.successRate;
        if (rate >= p.minSuccessRate) {
            x.score = p.minSuccessRate >= 100 ? 100 : 70 + 30 * (rate - p.minSuccessRate) / (100 - p.minSuccessRate);
        } else if (rate >= p.blockingSuccessRate && p.minSuccessRate > p.blockingSuccessRate) {
            x.score = 30 + 40 * (rate - p.blockingSuccessRate) / (p.minSuccessRate - p.blockingSuccessRate);
        } else {
            x.score = p.blockingSuccessRate <= 0 ? 0 : Math.max(0, 30 * rate / p.blockingSuccessRate);
        }
        x.blockingFailed = p.blockOnLowSuccessRate && rate < p.blockingSuccessRate;
        x.status = x.blockingFailed ? "FAIL" : rate < p.minSuccessRate ? "WARN" : "PASS";
        x.measured = fmt(rate) + " % (" + (executed - ev.failedTests) + "/" + executed + ")";
        String names = ev.failures.stream().limit(3).map(f -> f.test()).collect(Collectors.joining(", "));
        x.justification = ev.failedTests == 0 ? "Tous les tests exécutés réussissent."
                : ev.failedTests + " test(s) en échec sur " + executed + " : " + names + (ev.failedTests > 3 ? "…" : "") + ".";
        if (ev.testAgeHours() > p.maxTestAgeHours) {
            x.justification += " Résultats anciens (" + Math.round(ev.testAgeHours()) + " h) : confiance réduite.";
        }
        if (x.confidence < 1) x.adaptation = "Poids réduit (confiance " + pct(x.confidence) + ") : " + executed + " test(s) seulement"
                + (ev.testAgeHours() > p.maxTestAgeHours ? " et résultats anciens" : "") + ".";
        return x;
    }

    private Partial coverage(ValidationEvidence ev, ValidationPolicy p) {
        Partial x = new Partial(ValidationPolicy.COVERAGE, "Couverture de code", "TESTS", p);
        x.blocking = p.blockOnCoverage;
        x.threshold = "≥ " + fmt(p.minCoverage) + " %" + (p.blockOnCoverage ? " (bloquant)" : "");
        if (!ev.testsAvailable || ev.lineCoverage == null) return x.noData("Couverture inconnue : joignez le rapport JaCoCo (jacoco.xml).");
        double cov = ev.lineCoverage;
        x.confidence = ev.testAgeHours() > p.maxTestAgeHours ? 0.6 : 1.0;
        x.score = cov >= p.minCoverage ? (p.minCoverage >= 100 ? 100 : 80 + 20 * (cov - p.minCoverage) / (100 - p.minCoverage))
                : p.minCoverage <= 0 ? 100 : 80 * cov / p.minCoverage;
        boolean passed = cov >= p.minCoverage;
        x.blockingFailed = p.blockOnCoverage && !passed;
        x.status = passed ? "PASS" : x.blockingFailed ? "FAIL" : "WARN";
        x.measured = fmt(cov) + " % lignes" + (ev.branchCoverage != null ? ", " + fmt(ev.branchCoverage) + " % branches" : "");
        x.justification = passed ? "Couverture conforme au seuil." : "Il manque " + fmt(p.minCoverage - cov) + " point(s) de couverture de lignes.";
        return x;
    }

    private Partial stability(ValidationEvidence ev, ValidationPolicy p) {
        Partial x = new Partial(ValidationPolicy.TEST_STABILITY, "Stabilité des tests", "TESTS", p);
        x.threshold = "≤ " + fmt(p.maxFlakyPercent) + " % de tests instables";
        if (!ev.testsAvailable || ev.totalTests <= 0) return x.noData("Aucun résultat de tests.");
        double flakyPct = 100.0 * ev.flaky.size() / ev.totalTests;
        x.confidence = ev.testTrend.size() >= 3 ? 1.0 : 0.5;
        x.score = p.maxFlakyPercent <= 0 ? (ev.flaky.isEmpty() ? 100 : 50) : Math.max(0, 100 - 25 * flakyPct / p.maxFlakyPercent);
        x.status = flakyPct <= p.maxFlakyPercent ? "PASS" : "WARN";
        x.measured = ev.flaky.size() + " test(s) instable(s) (" + fmt(flakyPct) + " %)";
        x.justification = ev.flaky.isEmpty() ? "Aucun test n'alterne entre succès et échec sur l'historique récent."
                : "Tests instables : " + ev.flaky.stream().limit(3).map(f -> f.testName()).collect(Collectors.joining(", ")) + ".";
        if (x.confidence < 1) x.adaptation = "Historique court (" + ev.testTrend.size() + " exécution(s)) : détection d'instabilité peu fiable.";
        return x;
    }

    private Partial security(ValidationEvidence ev, ValidationPolicy p) {
        Partial x = new Partial(ValidationPolicy.SECURITY, "Sécurité", "SECURITY", p);
        x.weightFactor = p.securityWeightFactor;
        x.blocking = p.blockOnCriticalVulns;
        x.threshold = "critiques ≤ " + p.maxCriticalVulns + (p.blockOnCriticalVulns ? " (bloquant)" : "") + ", hautes ≤ " + p.maxHighVulns;
        if (!ev.securityScanned) return x.noData("Aucun résultat de scan de sécurité (Dependency-Check, Trivy, Sonar) pour cette application.");
        long crit = ev.openCount("CRITICAL");
        long high = ev.openCount("HIGH");
        long medium = ev.openCount("MEDIUM");
        long low = ev.openCount("LOW");
        x.score = Math.max(0, 100 - (crit * 40 + high * 12 + medium * 3 + low * 0.5));
        boolean passed = crit <= p.maxCriticalVulns && high <= p.maxHighVulns;
        x.blockingFailed = p.blockOnCriticalVulns && crit > p.maxCriticalVulns;
        x.status = x.blockingFailed ? "FAIL" : passed ? "PASS" : "WARN";
        x.measured = crit + " critique(s), " + high + " haute(s), " + medium + " moyenne(s), " + low + " faible(s)";
        x.justification = ev.openVulns == 0 ? "Aucune vulnérabilité ouverte."
                : "Priorités : " + ev.topVulnerabilities.stream().limit(3).map(v -> v.getId() + " (" + v.getAffectedComponent() + ")")
                .collect(Collectors.joining(", ")) + ".";
        if (p.securityWeightFactor != 1.0) x.adaptation = "Poids sécurité ×" + fmt(p.securityWeightFactor) + " (criticité de l'application).";
        return x;
    }

    private Partial logHealth(ValidationEvidence ev, ValidationPolicy p) {
        Partial x = new Partial(ValidationPolicy.LOG_HEALTH, "Santé des logs (application, serveur, CI)", "LOGS", p);
        x.blocking = p.blockOnFatalServerErrors;
        x.threshold = "anomalies critiques ≤ " + p.maxCriticalAnomalies + ", taux d'erreur ≤ " + fmt(p.maxErrorRatePercent) + " %"
                + (p.blockOnFatalServerErrors ? ", aucune erreur FATAL serveur (bloquant)" : "");
        if (!ev.logsAvailable || ev.logCount == 0) return x.noData("Aucun log rattaché à cette application (paramètre app à l'ingestion ou sources déclarées).");
        x.confidence = clamp(0.4 + 0.6 * Math.min(1.0, ev.logCount / (double) Math.max(1, p.minLogsForFullConfidence)), 0, 1);
        long criticalIncidents = ev.incidents.stream().filter(i -> "CRITICAL".equals(i.severity())).count();
        long multiLayer = ev.incidents.stream().filter(i -> i.layers().size() >= 2).count();
        double penalty = ev.criticalAnomalies * 10 + ev.spikes * 4 + Math.max(0, ev.errorRatePercent - p.maxErrorRatePercent) * 2
                + ev.fatalServerCount * 15 + criticalIncidents * 10;
        x.score = Math.max(0, 100 - penalty);
        boolean passed = ev.criticalAnomalies <= p.maxCriticalAnomalies && ev.errorRatePercent <= p.maxErrorRatePercent;
        x.blockingFailed = p.blockOnFatalServerErrors && ev.fatalServerCount > 0;
        x.status = x.blockingFailed ? "FAIL" : passed && criticalIncidents == 0 ? "PASS" : "WARN";
        x.measured = ev.criticalAnomalies + " anomalie(s) critique(s), " + fmt(ev.errorRatePercent) + " % d'erreurs, "
                + ev.incidents.size() + " incident(s) dont " + multiLayer + " multi-couches";
        StringBuilder j = new StringBuilder();
        j.append(ev.errorCount).append(" erreur(s) sur ").append(ev.logCount).append(" ligne(s)");
        if (!ev.errorsByLayer.isEmpty()) {
            j.append(" (").append(ev.errorsByLayer.entrySet().stream().filter(e -> e.getValue() > 0)
                    .map(e -> e.getKey().toLowerCase(Locale.ROOT) + " " + e.getValue()).collect(Collectors.joining(", "))).append(')');
        }
        j.append('.');
        if (!ev.incidents.isEmpty()) {
            Diagnostics.CorrelatedIncident top = ev.incidents.get(0);
            j.append(" Incident principal : cause racine probable « ").append(truncate(top.rootCause().signal().message(), 100))
                    .append(" » (confiance ").append(top.rootCause().confidencePercent()).append(" %).");
        }
        if (ev.fatalServerCount > 0) j.append(" ").append(ev.fatalServerCount).append(" erreur(s) FATAL côté serveur.");
        x.justification = j.toString();
        if (x.confidence < 1) x.adaptation = "Poids réduit (confiance " + pct(x.confidence) + ") : volume de logs limité (" + ev.logCount + ").";
        return x;
    }

    private Partial regression(ValidationEvidence ev, ValidationPolicy p, double provisional) {
        Partial x = new Partial(ValidationPolicy.REGRESSION, "Non-régression", "HISTORY", p);
        x.threshold = "aucun nouvel échec, baisse ≤ " + fmt(p.regressionTolerance) + " pts vs l'historique";
        boolean history = ev.previousScores.size() >= p.regressionMinHistory;
        if (!history && !ev.previousRunAvailable) return x.noData("Pas encore d'historique pour cette application (première validation).");
        double sNew = ev.newFailures.isEmpty() ? 100 : Math.max(0, 100 - 25 * ev.newFailures.size());
        double sHist = 100;
        String histText = "historique insuffisant (" + ev.previousScores.size() + " validation(s))";
        double drop = 0;
        if (history) {
            List<Double> window = ev.previousScores.subList(0, Math.min(p.regressionWindow, ev.previousScores.size()));
            double baseline = median(window);
            drop = baseline - provisional;
            sHist = drop <= p.regressionTolerance ? 100 : Math.max(0, 100 - (drop - p.regressionTolerance) * 4);
            histText = "référence propre à l'application = médiane " + fmt(baseline) + "/100 des " + window.size()
                    + " dernières validations, score provisoire " + fmt(provisional) + " (" + signed(-drop) + ")";
        }
        x.score = Math.min(sNew, sHist);
        boolean passed = ev.newFailures.isEmpty() && (!history || drop <= p.regressionTolerance);
        x.status = passed ? "PASS" : "WARN";
        x.measured = ev.newFailures.size() + " nouvel(s) échec(s)" + (history ? ", écart " + signed(-drop) + " pts" : "");
        x.justification = (ev.newFailures.isEmpty() ? "Aucun test précédemment réussi n'échoue désormais"
                : "Nouveaux échecs : " + ev.newFailures.stream().limit(3).collect(Collectors.joining(", ")))
                + " ; " + histText + "." + (ev.fixedTests.isEmpty() ? "" : " " + ev.fixedTests.size() + " test(s) corrigé(s) depuis l'exécution précédente.");
        x.adaptation = "Seuil adaptatif : comparaison à l'historique de l'application elle-même, pas à un seuil absolu.";
        if (!ev.previousRunAvailable) x.confidence = 0.5;
        return x;
    }

    // ----------------------------------------------------------------- utilitaires

    private static double testConfidence(ValidationEvidence ev, ValidationPolicy p, int executed) {
        double c = 0.3 + 0.7 * Math.min(1.0, executed / (double) Math.max(1, p.minTestsForFullConfidence));
        if (ev.testAgeHours() > p.maxTestAgeHours) c *= 0.6;
        return clamp(c, 0, 1);
    }

    private static double aggregate(List<Partial> partials) {
        double w = 0;
        double s = 0;
        for (Partial x : partials) {
            if (x.noData()) continue;
            w += x.effectiveWeight();
            s += x.effectiveWeight() * x.score;
        }
        return w == 0 ? 0 : s / w;
    }

    static Verdict byScore(double score, ValidationPolicy p) {
        return score >= p.validateScore ? Verdict.VALIDATED
                : score >= p.reservesScore ? Verdict.VALIDATED_WITH_RESERVES : Verdict.REJECTED;
    }

    public static Verdict opinionVerdict(String opinion) {
        if (opinion == null) return null;
        return switch (opinion.toUpperCase(Locale.ROOT)) {
            case "VALIDATE", "VALIDATED" -> Verdict.VALIDATED;
            case "RESERVES", "VALIDATED_WITH_RESERVES" -> Verdict.VALIDATED_WITH_RESERVES;
            case "REJECT", "REJECTED" -> Verdict.REJECTED;
            default -> null;
        };
    }

    private static String opinionLabel(String opinion) {
        Verdict v = opinionVerdict(opinion);
        return v == null ? String.valueOf(opinion) : v.label();
    }

    static double median(List<Double> values) {
        double[] a = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (a.length == 0) return 0;
        int m = a.length / 2;
        return a.length % 2 == 0 ? (a[m - 1] + a[m]) / 2 : a[m];
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String fmt(double v) {
        return ValidationPolicy.fmt(Math.round(v * 10.0) / 10.0);
    }

    static String signed(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "±") + fmt(Math.abs(v));
    }

    static String pct(double ratio) {
        return Math.round(ratio * 100) + " %";
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
