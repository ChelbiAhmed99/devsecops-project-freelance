/**
 * Role : deduire les risques et recommandations priorisees d'une validation.
 * Contenu : definition de ValidationInsights et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.validation;

import com.vermeg.assistant.model.Diagnostics;
import com.vermeg.assistant.model.ValidationModels.CriterionResult;
import com.vermeg.assistant.model.ValidationModels.LlmAssessment;
import com.vermeg.assistant.model.ValidationModels.Recommendation;
import com.vermeg.assistant.model.ValidationModels.RiskItem;
import com.vermeg.assistant.model.ValidationModels.ValidationOutcome;
import com.vermeg.assistant.model.Vulnerability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Risques et recommandations : d'abord déduits par règles des critères et preuves (source
 * « Règle »), puis complétés par ceux de l'IA (source « IA », à valider), sans doublon.
 */
public class ValidationInsights {

    public List<RiskItem> risks(ValidationEvidence ev, List<CriterionResult> criteria, ValidationOutcome outcome) {
        List<RiskItem> risks = new ArrayList<>();
        for (CriterionResult c : criteria) {
            if (c.blockingFailed()) {
                risks.add(new RiskItem("CRITICAL", c.label() + " : critère bloquant en échec", c.measured() + " — " + c.justification(), "Règle"));
            } else if ("WARN".equals(c.status()) || "FAIL".equals(c.status())) {
                risks.add(new RiskItem("HIGH".equals(levelOf(c)) ? "HIGH" : "MEDIUM", c.label() + " sous le seuil",
                        c.measured() + " (seuil " + c.threshold() + ")", "Règle"));
            }
        }
        for (Diagnostics.CorrelatedIncident i : ev.incidents.stream().limit(3).toList()) {
            if ("LOW".equals(i.severity())) continue;
            risks.add(new RiskItem(i.severity(), "Incident " + (i.layers().size() > 1 ? "multi-couches" : "") + " : "
                    + truncate(i.rootCause().signal().message(), 90), i.summary(), "Corrélation"));
        }
        if (!ev.newFailures.isEmpty()) {
            risks.add(new RiskItem("HIGH", "Régression fonctionnelle", ev.newFailures.size() + " test(s) réussi(s) auparavant échoue(nt) : "
                    + String.join(", ", ev.newFailures.stream().limit(5).toList()), "Règle"));
        }
        if (ev.testsAvailable && ev.testAgeHours() > 168) {
            risks.add(new RiskItem("MEDIUM", "Résultats de tests anciens", "Dernière exécution il y a " + Math.round(ev.testAgeHours() / 24) + " jour(s) : "
                    + "la décision peut ne pas refléter la version actuelle.", "Règle"));
        }
        if (outcome.confidence() < 0.7) {
            risks.add(new RiskItem("MEDIUM", "Preuves incomplètes", "Confiance de la décision : " + Math.round(outcome.confidence() * 100)
                    + " %. Critères sans données : " + criteria.stream().filter(c -> "NO_DATA".equals(c.status()))
                    .map(CriterionResult::label).collect(Collectors.joining(", ")) + ".", "Règle"));
        }
        LlmAssessment llm = outcome.llm();
        if (llm != null && "USED".equals(llm.status())) {
            Set<String> seen = risks.stream().map(r -> r.title().toLowerCase()).collect(Collectors.toSet());
            for (String r : llm.risks()) {
                if (r == null || r.isBlank() || seen.contains(r.toLowerCase())) continue;
                risks.add(new RiskItem("MEDIUM", truncate(r, 140), "Risque identifié par l'analyse IA (à confirmer).", "IA"));
            }
        }
        risks.sort((a, b) -> Integer.compare(rank(b.level()), rank(a.level())));
        return risks;
    }

    public List<Recommendation> recommendations(ValidationEvidence ev, List<CriterionResult> criteria, ValidationOutcome outcome) {
        List<Recommendation> recs = new ArrayList<>();
        // 1. Sécurité bloquante d'abord, avec la version corrective quand elle est connue
        for (Vulnerability v : ev.topVulnerabilities.stream()
                .filter(v -> v.getSeverity() == Vulnerability.Severity.CRITICAL || v.getSeverity() == Vulnerability.Severity.HIGH)
                .limit(5).toList()) {
            recs.add(new Recommendation(0, "Corriger " + v.getId() + " sur " + v.getAffectedComponent()
                    + (v.getFixedVersion() != null ? " (mettre à jour vers " + v.getFixedVersion() + ")" : ""),
                    "Vulnérabilité " + v.getSeverity() + " ouverte, priorité " + v.getPriorityScore() + "/100.", "Règle", "vuln:" + v.getSource() + ":" + v.getId()));
        }
        // 2. Cause racine des incidents (une action par incident)
        for (Diagnostics.CorrelatedIncident i : ev.incidents.stream().limit(3).toList()) {
            Diagnostics.RootCauseCandidate rc = i.rootCause();
            recs.add(new Recommendation(0, "Traiter la cause racine : " + truncate(rc.signal().message(), 110),
                    rc.rationale() + (i.impactedTests().isEmpty() ? "" : " Tests liés : " + String.join(", ", i.impactedTests()) + "."),
                    "Corrélation", i.id()));
        }
        // 3. Tests en échec (nouveaux échecs en premier)
        ev.failures.stream().sorted((a, b) -> Boolean.compare(b.newFailure(), a.newFailure())).limit(5).forEach(f ->
                recs.add(new Recommendation(0, "Corriger le test " + f.suite() + "#" + f.test(),
                        (f.newFailure() ? "Régression : ce test réussissait à l'exécution précédente. " : "")
                                + truncate(f.message() == null ? "" : f.message(), 160), "Règle", f.ref())));
        for (CriterionResult c : criteria) {
            switch (c.code()) {
                case ValidationPolicy.COVERAGE -> {
                    if (!"PASS".equals(c.status()) && !"NO_DATA".equals(c.status()))
                        recs.add(new Recommendation(0, "Augmenter la couverture de tests", c.justification() + " Cibler en priorité le code modifié récemment.", "Règle", c.code()));
                }
                case ValidationPolicy.TEST_STABILITY -> {
                    if (!"PASS".equals(c.status()) && !"NO_DATA".equals(c.status()))
                        recs.add(new Recommendation(0, "Stabiliser les tests instables", c.justification() + " Isoler les dépendances temporelles et externes (horloge, réseau, données partagées).", "Règle", c.code()));
                }
                default -> {
                    if ("NO_DATA".equals(c.status()))
                        recs.add(new Recommendation(0, "Compléter les preuves : " + c.label().toLowerCase(), c.justification(), "Règle", c.code()));
                }
            }
        }
        if (ev.testsAvailable && ev.testAgeHours() > 168) {
            recs.add(new Recommendation(0, "Relancer les tests avant mise en production", "Les derniers résultats datent de plus de 7 jours.", "Règle", ValidationPolicy.TEST_SUCCESS));
        }
        LlmAssessment llm = outcome.llm();
        if (llm != null && "USED".equals(llm.status())) {
            for (String r : llm.recommendations()) {
                if (r != null && !r.isBlank()) recs.add(new Recommendation(0, truncate(r, 200), "Proposition de l'analyse IA, à valider par un humain.", "IA", null));
            }
        }
        // Dédoublonnage + numérotation des priorités
        Set<String> seen = new LinkedHashSet<>();
        List<Recommendation> unique = new ArrayList<>();
        for (Recommendation r : recs) {
            if (seen.add(r.action().toLowerCase())) unique.add(r);
        }
        List<Recommendation> numbered = new ArrayList<>();
        for (int i = 0; i < unique.size() && i < 15; i++) {
            Recommendation r = unique.get(i);
            numbered.add(new Recommendation(i + 1, r.action(), r.rationale(), r.source(), r.ref()));
        }
        return numbered;
    }

    private static String levelOf(CriterionResult c) {
        return c.score() < 40 ? "HIGH" : "MEDIUM";
    }

    private static int rank(String level) {
        return switch (level) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            case "MEDIUM" -> 1;
            default -> 0;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
