/**
 * Role : produire le rapport de validation d'une application en Markdown et en HTML imprimable.
 * Contenu : definition de ValidationReportRenderer et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.validation;

import com.vermeg.assistant.model.Diagnostics;
import com.vermeg.assistant.model.ValidationModels.AnomalyView;
import com.vermeg.assistant.model.ValidationModels.ApplicationValidationReport;
import com.vermeg.assistant.model.ValidationModels.CriterionResult;
import com.vermeg.assistant.model.ValidationModels.DecisionStep;
import com.vermeg.assistant.model.ValidationModels.LlmAssessment;
import com.vermeg.assistant.model.ValidationModels.Recommendation;
import com.vermeg.assistant.model.ValidationModels.RiskItem;
import com.vermeg.assistant.model.ValidationModels.TestFailureView;
import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.service.LogTypeClassifier;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Un rapport autonome par application. Le HTML est autoporteur (aucune ressource externe) et
 * prévu pour l'impression (« Enregistrer en PDF » du navigateur). La section « Décision humaine »
 * est délimitée par des marqueurs pour être mise à jour sans régénérer le reste du rapport
 * (le contenu automatique reste l'instantané fidèle du moment de la validation).
 */
public final class ValidationReportRenderer {

    public static final String HUMAN_START = "<!-- HUMAN_DECISION_START -->";
    public static final String HUMAN_END = "<!-- HUMAN_DECISION_END -->";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private ValidationReportRenderer() {
    }

    // =============================================================== Markdown

    public static String markdown(ApplicationValidationReport r) {
        StringBuilder md = new StringBuilder();
        md.append("# Rapport de validation — ").append(r.applicationName()).append("\n\n");
        md.append("| Application | Criticité | Serveur | Responsable | Généré le | Par | Campagne |\n|---|---|---|---|---|---|---|\n");
        md.append("| `").append(r.applicationCode()).append("` | ").append(r.criticality()).append(" | ").append(nz(r.serverType()))
                .append(" | ").append(nz(r.owner())).append(" | ").append(date(r.generatedAt())).append(" | ").append(nz(r.generatedBy()))
                .append(" | #").append(r.campaignId()).append(" |\n\n");

        md.append("## 1. Décision\n\n");
        md.append("**").append(verdictIcon(r.verdict().name())).append(' ').append(r.verdict().label()).append("** — score **")
                .append(fmt(r.score())).append("/100**");
        if (r.llmAdjustment() != 0) md.append(" (déterministe ").append(fmt(r.baseScore())).append(", ajustement IA ").append(signed(r.llmAdjustment())).append(')');
        md.append(" · confiance des preuves ").append(pct(r.confidence()));
        if (r.previousScore() != null) md.append(" · validation précédente : ").append(fmt(r.previousScore())).append("/100 (").append(nz(r.previousVerdict())).append(')');
        md.append("\n\n");
        if (!r.blockingReasons().isEmpty()) {
            md.append("**Critères bloquants en échec :**\n\n");
            r.blockingReasons().forEach(b -> md.append("- ").append(md(b)).append('\n'));
            md.append('\n');
        }
        md.append(r.reviewRequired() ? "> ⚠️ **Revue humaine obligatoire** : " + md(String.join(" ", r.reviewReasons())) + "\n\n"
                : "> Décision automatique cohérente : une confirmation humaine reste recommandée.\n\n");

        md.append("## 2. Explication du score\n\n");
        md.append("| Critère | Mesure | Seuil | Score | Poids effectif | Points perdus | Statut |\n|---|---|---|---|---|---|---|\n");
        for (CriterionResult c : r.criteria()) {
            md.append("| ").append(c.label()).append(" | ").append(md(c.measured())).append(" | ").append(md(c.threshold())).append(" | ")
                    .append("NO_DATA".equals(c.status()) ? "—" : fmt(c.score())).append(" | ").append(fmt(c.effectiveWeight())).append(" | ")
                    .append(fmt(c.pointsLost())).append(" | ").append(statusLabel(c.status())).append(c.blockingFailed() ? " (bloquant)" : "").append(" |\n");
        }
        md.append("\n");
        for (CriterionResult c : r.criteria()) {
            md.append("- **").append(c.label()).append("** : ").append(md(c.justification()));
            if (c.adaptation() != null) md.append(" _").append(md(c.adaptation())).append('_');
            md.append('\n');
        }
        md.append("\n## 3. Raisonnement de décision (trace)\n\n| Règle | Effet | Détail |\n|---|---|---|\n");
        for (DecisionStep s : r.trace()) md.append("| ").append(s.rule()).append(" | ").append(s.outcome()).append(" | ").append(md(s.detail())).append(" |\n");

        md.append("\n## 4. Tests\n\n");
        var t = r.tests();
        if (!t.available()) {
            md.append("_Aucun résultat de tests pour cette application._\n");
        } else {
            md.append("- Exécution #").append(t.runId()).append(" du ").append(date(t.executedAt())).append(" (").append(nz(t.trigger())).append(")\n")
                    .append("- ").append(t.total()).append(" tests, ").append(t.failed()).append(" en échec, ").append(t.skipped()).append(" ignorés — réussite ")
                    .append(fmt(t.successRate())).append(" %\n")
                    .append("- Couverture : ").append(t.lineCoverage() == null ? "n/d" : fmt(t.lineCoverage()) + " % lignes")
                    .append(t.branchCoverage() == null ? "" : ", " + fmt(t.branchCoverage()) + " % branches").append('\n');
            if (!t.newFailures().isEmpty()) md.append("- **Nouveaux échecs (régression)** : ").append(String.join(", ", t.newFailures())).append('\n');
            if (!t.fixedTests().isEmpty()) md.append("- Tests corrigés depuis l'exécution précédente : ").append(String.join(", ", t.fixedTests())).append('\n');
            if (!t.flaky().isEmpty()) {
                md.append("- Tests instables : ");
                t.flaky().stream().limit(5).forEach(f -> md.append('`').append(f.suiteName()).append('#').append(f.testName()).append("` "));
                md.append('\n');
            }
            if (!t.failures().isEmpty()) {
                md.append("\n### Échecs\n\n");
                for (TestFailureView f : t.failures()) {
                    md.append("#### `").append(f.suite()).append('#').append(f.test()).append("` — ").append(f.status())
                            .append(f.newFailure() ? " (nouvel échec)" : "").append("\n\n");
                    if (f.message() != null) md.append("> ").append(md(f.message())).append("\n\n");
                    if (f.stackExcerpt() != null) md.append("```\n").append(f.stackExcerpt()).append("\n```\n\n");
                    if (!f.relatedLogs().isEmpty()) {
                        md.append("Logs liés :\n\n```\n");
                        f.relatedLogs().forEach(l -> md.append(l).append('\n'));
                        md.append("```\n\n");
                    }
                }
            }
        }

        md.append("\n## 5. Logs et erreurs\n\n");
        var l = r.logs();
        if (!l.available()) {
            md.append("_Aucun log rattaché à cette application._\n");
        } else {
            md.append("- ").append(l.total()).append(" lignes, ").append(l.errors()).append(" erreurs, ").append(l.warnings())
                    .append(" avertissements — taux d'erreur ").append(fmt(l.errorRatePercent())).append(" %\n");
            md.append("- Par couche : ").append(layers(l.byLayer())).append(" · erreurs par couche : ").append(layers(l.errorsByLayer())).append('\n');
            md.append("- Anomalies critiques : ").append(l.criticalAnomalies()).append(" · pics d'erreurs : ").append(l.spikes()).append("\n\n");
            if (!l.anomalies().isEmpty()) {
                md.append("| Couche | Catégorie | Code | Occurrences | Message |\n|---|---|---|---|---|\n");
                for (AnomalyView a : l.anomalies()) {
                    md.append("| ").append(LogTypeClassifier.label(a.layer())).append(" | ").append(nz(a.category())).append(" | ")
                            .append(nz(a.errorCode())).append(" | ").append(a.occurrences()).append(a.critical() ? " ⚠" : "").append(" | ")
                            .append(md(truncate(a.message(), 140))).append(" |\n");
                }
            }
            if (!l.excerpts().isEmpty()) {
                md.append("\nExtraits :\n\n```\n");
                l.excerpts().forEach(e -> md.append(e).append('\n'));
                md.append("```\n");
            }
        }

        md.append("\n## 6. Incidents corrélés et cause racine\n\n");
        if (r.incidents().isEmpty()) md.append("_Aucun incident corrélé (rafale d'erreurs) détecté._\n");
        int n = 1;
        for (Diagnostics.CorrelatedIncident i : r.incidents()) {
            md.append("### Incident ").append(n++).append(" — ").append(i.severity()).append(" · ").append(nz(i.category())).append("\n\n")
                    .append(md(i.summary())).append("\n\n");
            Diagnostics.RootCauseCandidate rc = i.rootCause();
            md.append("**Cause racine probable** (").append(rc.confidencePercent()).append(" %) : `").append(md(truncate(rc.signal().message(), 200)))
                    .append("`\n\n").append(md(rc.rationale())).append("\n\n| Facteur | Valeur | Poids | Contribution | Détail |\n|---|---|---|---|---|\n");
            for (Diagnostics.ExplanationFactor f : rc.factors()) {
                md.append("| ").append(f.label()).append(" | ").append(fmt(f.value())).append(" | ").append(fmt(f.weight())).append(" | ")
                        .append(fmt(f.contribution())).append(" | ").append(md(f.detail())).append(" |\n");
            }
            if (!i.chain().isEmpty()) {
                md.append("\nChaîne : \n\n");
                i.chain().forEach(c -> md.append("- ").append(md(c.fromLabel())).append(" → ").append(md(c.toLabel())).append(" _(").append(c.rule()).append(")_\n"));
            }
            if (!i.impactedTests().isEmpty()) md.append("\nTests impactés : ").append(String.join(", ", i.impactedTests())).append('\n');
            md.append('\n');
        }

        md.append("\n## 7. Sécurité\n\n");
        var s = r.security();
        if (!s.available()) {
            md.append("_Aucun résultat de scan de sécurité pour cette application._\n");
        } else {
            md.append("- Vulnérabilités ouvertes : ").append(s.open()).append(" — ").append(s.openBySeverity()).append("\n\n");
            if (!s.top().isEmpty()) {
                md.append("| Priorité | ID | Sévérité | Composant | Correctif | Source |\n|---|---|---|---|---|---|\n");
                for (Vulnerability v : s.top()) {
                    md.append("| ").append(v.getPriorityScore()).append(" | ").append(v.getId()).append(" | ").append(v.getSeverity()).append(" | ")
                            .append(md(v.getAffectedComponent())).append(" | ").append(nz(v.getFixedVersion())).append(" | ").append(v.getSource()).append(" |\n");
                }
            }
        }

        md.append("\n## 8. Risques\n\n");
        if (r.risks().isEmpty()) md.append("_Aucun risque significatif identifié._\n");
        for (RiskItem k : r.risks()) md.append("- **[").append(k.level()).append("] ").append(md(k.title())).append("** — ").append(md(k.detail())).append(" _(").append(k.source()).append(")_\n");

        md.append("\n## 9. Recommandations (par priorité)\n\n");
        if (r.recommendations().isEmpty()) md.append("_Aucune action requise._\n");
        for (Recommendation k : r.recommendations()) md.append(k.priority()).append(". **").append(md(k.action())).append("** — ").append(md(k.rationale())).append(" _(").append(k.source()).append(")_\n");

        md.append("\n## 10. Analyse IA (consultative)\n\n");
        LlmAssessment llm = r.llm();
        if (llm == null || !llm.usable()) {
            md.append("_").append(llm == null ? "Non demandée." : md(nz(llm.note()))).append("_\n");
        } else {
            md.append("- Statut : ").append(llm.status()).append(" · fournisseur ").append(nz(llm.provider())).append(' ').append(nz(llm.model())).append('\n')
                    .append("- Avis : **").append(nz(llm.opinion())).append("** (confiance ").append(pct(llm.confidence())).append(") · ajustement proposé ")
                    .append(signed(llm.proposedAdjustment())).append(", appliqué ").append(signed(llm.appliedAdjustment())).append(" (borne ± ")
                    .append(fmt(llm.allowedAdjustment())).append(")\n");
            if (llm.note() != null) md.append("- ").append(md(llm.note())).append('\n');
            if (llm.justification() != null && !llm.justification().isBlank()) md.append("\n> ").append(md(llm.justification())).append('\n');
            if (!llm.ungroundedRefs().isEmpty()) md.append("\n**Références non ancrées (ignorées)** : ").append(String.join(", ", llm.ungroundedRefs())).append('\n');
        }
        md.append("\n_Le modèle de langage ne décide jamais seul : son avis ne peut ajuster le score que dans une marge bornée, "
                + "il ne lève aucune règle bloquante et tout désaccord impose une revue humaine._\n");

        md.append("\n## 11. Politique appliquée\n\n| Paramètre | Valeur |\n|---|---|\n");
        r.policy().forEach((k, v) -> md.append("| ").append(k).append(" | ").append(md(v)).append(" |\n"));

        md.append("\n## 12. Décision humaine\n\n").append(HUMAN_START).append('\n')
                .append(humanMarkdown(null, null, null, null, null)).append('\n').append(HUMAN_END).append('\n');
        return md.toString();
    }

    public static String humanMarkdown(String decision, String verdictLabel, String by, LocalDateTime at, String comment) {
        if (decision == null) return "_En attente de validation par un responsable qualité._";
        return "**" + ("CONFIRMED".equals(decision) ? "Décision confirmée" : "Décision corrigée") + "** : " + verdictLabel
                + " — par " + by + " le " + date(at) + (comment == null || comment.isBlank() ? "" : "\n\n> " + md(comment));
    }

    // =================================================================== HTML

    public static String html(ApplicationValidationReport r) {
        StringBuilder h = new StringBuilder(32_000);
        h.append("<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Validation ").append(esc(r.applicationName())).append("</title><style>").append(CSS).append("</style></head><body><main>");
        h.append("<header class=\"top\"><div><p class=\"eyebrow\">Assistant DevSecOps · Rapport de validation</p><h1>")
                .append(esc(r.applicationName())).append("</h1><p class=\"muted\"><code>").append(esc(r.applicationCode())).append("</code> · criticité <b>")
                .append(esc(r.criticality())).append("</b>").append(r.serverType() == null ? "" : " · " + esc(r.serverType()))
                .append(r.owner() == null ? "" : " · responsable " + esc(r.owner())).append("<br>Généré le ").append(date(r.generatedAt()))
                .append(" par ").append(esc(nz(r.generatedBy()))).append(" · campagne #").append(r.campaignId()).append(" · mode ").append(esc(nz(r.mode())))
                .append("</p></div><div class=\"verdict v-").append(r.verdict().name()).append("\"><span class=\"score\">").append(fmt(r.score()))
                .append("<small>/100</small></span><span>").append(esc(r.verdict().label())).append("</span></div></header>");

        h.append("<section><h2>1. Décision</h2><p>Score déterministe <b>").append(fmt(r.baseScore())).append("</b>");
        if (r.llmAdjustment() != 0) h.append(", ajustement IA borné <b>").append(signed(r.llmAdjustment())).append("</b>");
        h.append(", score final <b>").append(fmt(r.score())).append("/100</b> · confiance des preuves <b>").append(pct(r.confidence())).append("</b>");
        if (r.previousScore() != null) h.append(" · validation précédente ").append(fmt(r.previousScore())).append("/100 (").append(esc(nz(r.previousVerdict()))).append(')');
        h.append("</p>");
        if (!r.blockingReasons().isEmpty()) {
            h.append("<div class=\"alert crit\"><b>Critères bloquants en échec</b><ul>");
            r.blockingReasons().forEach(b -> h.append("<li>").append(esc(b)).append("</li>"));
            h.append("</ul></div>");
        }
        h.append(r.reviewRequired() ? "<div class=\"alert warn\"><b>Revue humaine obligatoire.</b> " + esc(String.join(" ", r.reviewReasons())) + "</div>"
                : "<div class=\"alert info\">Décision automatique cohérente : une confirmation humaine reste recommandée.</div>");
        h.append("</section>");

        h.append("<section><h2>2. Explication du score</h2>").append(waterfall(r))
                .append("<table><thead><tr><th>Critère</th><th>Mesure</th><th>Seuil</th><th>Score</th><th>Poids eff.</th><th>Points perdus</th><th>Statut</th></tr></thead><tbody>");
        for (CriterionResult c : r.criteria()) {
            h.append("<tr><td><b>").append(esc(c.label())).append("</b><div class=\"muted small\">").append(esc(c.justification()))
                    .append(c.adaptation() == null ? "" : "<br><i>" + esc(c.adaptation()) + "</i>").append("</div></td><td>").append(esc(c.measured()))
                    .append("</td><td>").append(esc(c.threshold())).append("</td><td>").append("NO_DATA".equals(c.status()) ? "—" : fmt(c.score()))
                    .append("</td><td>").append(fmt(c.effectiveWeight())).append("</td><td>").append(fmt(c.pointsLost())).append("</td><td><span class=\"badge s-")
                    .append(c.status()).append("\">").append(statusLabel(c.status())).append(c.blockingFailed() ? " · bloquant" : "").append("</span></td></tr>");
        }
        h.append("</tbody></table></section>");

        h.append("<section><h2>3. Raisonnement de décision</h2><ol class=\"trace\">");
        for (DecisionStep s : r.trace()) {
            h.append("<li><b>").append(esc(s.rule())).append("</b> <span class=\"badge\">").append(esc(s.outcome())).append("</span><div>")
                    .append(esc(s.detail())).append("</div></li>");
        }
        h.append("</ol></section>");

        var t = r.tests();
        h.append("<section><h2>4. Tests</h2>");
        if (!t.available()) {
            h.append("<p class=\"muted\">Aucun résultat de tests pour cette application.</p>");
        } else {
            h.append("<div class=\"kpis\">").append(kpi(t.total(), "tests")).append(kpi(t.failed(), "en échec")).append(kpi(fmt(t.successRate()) + " %", "réussite"))
                    .append(kpi(t.lineCoverage() == null ? "n/d" : fmt(t.lineCoverage()) + " %", "couverture lignes")).append(kpi(t.flaky().size(), "instables")).append("</div>");
            h.append("<p class=\"muted\">Exécution #").append(t.runId()).append(" du ").append(date(t.executedAt())).append(" (").append(esc(nz(t.trigger()))).append(")</p>");
            if (!t.newFailures().isEmpty()) h.append("<div class=\"alert warn\"><b>Nouveaux échecs :</b> ").append(esc(String.join(", ", t.newFailures()))).append("</div>");
            for (TestFailureView f : t.failures()) {
                h.append("<details class=\"failure\"><summary><code>").append(esc(f.suite())).append('#').append(esc(f.test())).append("</code> ")
                        .append(f.newFailure() ? "<span class=\"badge s-FAIL\">nouvel échec</span>" : "").append("</summary>");
                if (f.message() != null) h.append("<p>").append(esc(f.message())).append("</p>");
                if (f.stackExcerpt() != null) h.append("<pre>").append(esc(f.stackExcerpt())).append("</pre>");
                if (!f.relatedLogs().isEmpty()) h.append("<p class=\"small\"><b>Logs liés</b></p><pre>").append(esc(String.join("\n", f.relatedLogs()))).append("</pre>");
                h.append("</details>");
            }
        }
        h.append("</section>");

        var l = r.logs();
        h.append("<section><h2>5. Logs et erreurs</h2>");
        if (!l.available()) {
            h.append("<p class=\"muted\">Aucun log rattaché à cette application.</p>");
        } else {
            h.append("<div class=\"kpis\">").append(kpi(l.total(), "lignes")).append(kpi(l.errors(), "erreurs")).append(kpi(fmt(l.errorRatePercent()) + " %", "taux d'erreur"))
                    .append(kpi(l.criticalAnomalies(), "anomalies critiques")).append(kpi(l.spikes(), "pics")).append("</div>");
            h.append("<p>Par couche : ").append(esc(layers(l.byLayer()))).append(" · erreurs : ").append(esc(layers(l.errorsByLayer()))).append("</p>");
            if (!l.anomalies().isEmpty()) {
                h.append("<table><thead><tr><th>Couche</th><th>Catégorie</th><th>Code</th><th>Occ.</th><th>Message</th></tr></thead><tbody>");
                for (AnomalyView a : l.anomalies()) {
                    h.append("<tr><td>").append(esc(LogTypeClassifier.label(a.layer()))).append("</td><td>").append(esc(nz(a.category()))).append("</td><td>")
                            .append(esc(nz(a.errorCode()))).append("</td><td>").append(a.occurrences()).append(a.critical() ? " <span class=\"badge s-FAIL\">critique</span>" : "")
                            .append("</td><td><code>").append(esc(truncate(a.message(), 180))).append("</code></td></tr>");
                }
                h.append("</tbody></table>");
            }
            if (!l.excerpts().isEmpty()) h.append("<details><summary>Extraits de logs</summary><pre>").append(esc(String.join("\n", l.excerpts()))).append("</pre></details>");
        }
        h.append("</section>");

        h.append("<section><h2>6. Incidents corrélés et cause racine</h2>");
        if (r.incidents().isEmpty()) h.append("<p class=\"muted\">Aucun incident corrélé détecté.</p>");
        for (Diagnostics.CorrelatedIncident i : r.incidents()) {
            Diagnostics.RootCauseCandidate rc = i.rootCause();
            h.append("<div class=\"incident sev-").append(i.severity()).append("\"><p><span class=\"badge\">").append(esc(i.severity())).append("</span> <span class=\"badge\">")
                    .append(esc(nz(i.category()))).append("</span> ").append(esc(i.summary())).append("</p><p><b>Cause racine probable (")
                    .append(rc.confidencePercent()).append(" %)</b> : <code>").append(esc(truncate(rc.signal().message(), 220))).append("</code></p><p class=\"small\">")
                    .append(esc(rc.rationale())).append("</p><div class=\"factors\">");
            for (Diagnostics.ExplanationFactor f : rc.factors()) {
                h.append("<div class=\"factor\"><span>").append(esc(f.label())).append("</span><span class=\"bar\"><i style=\"width:")
                        .append(Math.round(f.value() * 100)).append("%\"></i></span><span class=\"small\">").append(fmt(f.contribution())).append(" · ")
                        .append(esc(f.detail())).append("</span></div>");
            }
            h.append("</div>");
            if (!i.chain().isEmpty()) {
                h.append("<ul class=\"chain\">");
                i.chain().forEach(c -> h.append("<li>").append(esc(c.fromLabel())).append(" → ").append(esc(c.toLabel())).append(" <span class=\"muted small\">(")
                        .append(esc(c.rule())).append(")</span></li>"));
                h.append("</ul>");
            }
            if (!i.impactedTests().isEmpty()) h.append("<p class=\"small\">Tests impactés : ").append(esc(String.join(", ", i.impactedTests()))).append("</p>");
            h.append("</div>");
        }
        h.append("</section>");

        var s = r.security();
        h.append("<section><h2>7. Sécurité</h2>");
        if (!s.available()) {
            h.append("<p class=\"muted\">Aucun résultat de scan de sécurité pour cette application.</p>");
        } else {
            h.append("<div class=\"kpis\">").append(kpi(s.open(), "ouvertes"));
            for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) h.append(kpi(s.openBySeverity().getOrDefault(sev, 0L), sev.toLowerCase()));
            h.append("</div>");
            if (!s.top().isEmpty()) {
                h.append("<table><thead><tr><th>Priorité</th><th>ID</th><th>Sévérité</th><th>Composant</th><th>Correctif</th></tr></thead><tbody>");
                for (Vulnerability v : s.top()) {
                    h.append("<tr><td>").append(v.getPriorityScore()).append("</td><td>").append(esc(v.getId())).append("</td><td><span class=\"badge sev-")
                            .append(v.getSeverity()).append("\">").append(v.getSeverity()).append("</span></td><td>").append(esc(v.getAffectedComponent()))
                            .append("</td><td>").append(esc(nz(v.getFixedVersion()))).append("</td></tr>");
                }
                h.append("</tbody></table>");
            }
        }
        h.append("</section>");

        h.append("<section><h2>8. Risques</h2>");
        if (r.risks().isEmpty()) h.append("<p class=\"muted\">Aucun risque significatif identifié.</p>");
        h.append("<ul class=\"risks\">");
        for (RiskItem k : r.risks()) {
            h.append("<li class=\"sev-").append(k.level()).append("\"><b>").append(esc(k.title())).append("</b> <span class=\"badge\">").append(k.level())
                    .append("</span> <span class=\"muted small\">").append(esc(k.source())).append("</span><div class=\"small\">").append(esc(k.detail())).append("</div></li>");
        }
        h.append("</ul></section>");

        h.append("<section><h2>9. Recommandations</h2><ol class=\"recs\">");
        for (Recommendation k : r.recommendations()) {
            h.append("<li><b>").append(esc(k.action())).append("</b> <span class=\"muted small\">").append(esc(k.source())).append("</span><div class=\"small\">")
                    .append(esc(k.rationale())).append("</div></li>");
        }
        h.append("</ol>").append(r.recommendations().isEmpty() ? "<p class=\"muted\">Aucune action requise.</p>" : "").append("</section>");

        LlmAssessment llm = r.llm();
        h.append("<section><h2>10. Analyse IA (consultative)</h2>");
        if (llm == null || !llm.usable()) {
            h.append("<p class=\"muted\">").append(esc(llm == null ? "Non demandée." : nz(llm.note()))).append("</p>");
        } else {
            h.append("<p>Statut <b>").append(esc(llm.status())).append("</b> · ").append(esc(nz(llm.provider()))).append(' ').append(esc(nz(llm.model())))
                    .append(" · avis <b>").append(esc(nz(llm.opinion()))).append("</b> (confiance ").append(pct(llm.confidence())).append(") · ajustement proposé ")
                    .append(signed(llm.proposedAdjustment())).append(", appliqué <b>").append(signed(llm.appliedAdjustment())).append("</b> (borne ± ")
                    .append(fmt(llm.allowedAdjustment())).append(")</p>");
            if (llm.note() != null) h.append("<p class=\"small\">").append(esc(llm.note())).append("</p>");
            if (llm.justification() != null && !llm.justification().isBlank()) h.append("<blockquote>").append(esc(llm.justification())).append("</blockquote>");
            if (!llm.ungroundedRefs().isEmpty()) h.append("<div class=\"alert warn\">Références non ancrées dans les preuves (avis ignoré) : ")
                    .append(esc(String.join(", ", llm.ungroundedRefs()))).append("</div>");
        }
        h.append("<p class=\"small muted\">Le modèle de langage ne décide jamais seul : son avis ne peut ajuster le score que dans une marge bornée, "
                + "il ne lève aucune règle bloquante et tout désaccord impose une revue humaine.</p></section>");

        h.append("<section><h2>11. Politique appliquée</h2><table><tbody>");
        for (Map.Entry<String, String> e : r.policy().entrySet()) {
            h.append("<tr><th>").append(esc(e.getKey())).append("</th><td>").append(esc(e.getValue())).append("</td></tr>");
        }
        h.append("</tbody></table></section>");

        h.append("<section><h2>12. Décision humaine</h2>").append(HUMAN_START).append(humanHtml(null, null, null, null, null)).append(HUMAN_END).append("</section>");
        h.append("<footer>Rapport généré automatiquement par l'Assistant DevSecOps — Vermeg. Les propositions de l'IA doivent être validées par un humain ; "
                + "aucune modification n'est appliquée automatiquement au code ou à la production.</footer></main></body></html>");
        return h.toString();
    }

    public static String humanHtml(String decision, String verdictLabel, String by, LocalDateTime at, String comment) {
        if (decision == null) return "<p class=\"muted\">En attente de validation par un responsable qualité.</p>";
        return "<div class=\"alert " + ("CONFIRMED".equals(decision) ? "ok" : "warn") + "\"><b>"
                + ("CONFIRMED".equals(decision) ? "Décision confirmée" : "Décision corrigée") + " : " + esc(verdictLabel) + "</b> — par "
                + esc(by) + " le " + date(at) + (comment == null || comment.isBlank() ? "" : "<blockquote>" + esc(comment) + "</blockquote>") + "</div>";
    }

    /** Remplace la section « Décision humaine » d'un rapport déjà généré. */
    public static String replaceHuman(String document, String section) {
        if (document == null) return null;
        int a = document.indexOf(HUMAN_START);
        int b = document.indexOf(HUMAN_END);
        if (a < 0 || b < a) return document;
        String sep = document.startsWith("<!doctype") ? "" : "\n";
        return document.substring(0, a + HUMAN_START.length()) + sep + section + sep + document.substring(b);
    }

    /** Explication « en cascade » : 100 points, moins les points perdus par critère, puis l'ajustement IA. */
    private static String waterfall(ApplicationValidationReport r) {
        StringBuilder w = new StringBuilder("<div class=\"waterfall\"><div class=\"wf-row\"><span>Départ</span><span class=\"wf-bar\"><i style=\"width:100%\"></i></span><b>100</b></div>");
        double remaining = 100;
        for (CriterionResult c : r.criteria()) {
            if ("NO_DATA".equals(c.status()) || c.pointsLost() <= 0) continue;
            remaining -= c.pointsLost();
            w.append("<div class=\"wf-row\"><span>− ").append(esc(c.label())).append("</span><span class=\"wf-bar\"><i class=\"lost\" style=\"width:")
                    .append(Math.max(1, Math.round(c.pointsLost()))).append("%\"></i></span><b>−").append(fmt(c.pointsLost())).append("</b></div>");
        }
        w.append("<div class=\"wf-row total\"><span>Score déterministe</span><span class=\"wf-bar\"><i style=\"width:").append(Math.round(r.baseScore()))
                .append("%\"></i></span><b>").append(fmt(r.baseScore())).append("</b></div>");
        if (r.llmAdjustment() != 0) {
            w.append("<div class=\"wf-row\"><span>Ajustement IA (borné)</span><span class=\"wf-bar\"></span><b>").append(signed(r.llmAdjustment())).append("</b></div>");
        }
        w.append("<div class=\"wf-row total\"><span>Score final</span><span class=\"wf-bar\"><i style=\"width:").append(Math.round(r.score()))
                .append("%\"></i></span><b>").append(fmt(r.score())).append("</b></div></div>");
        return w.toString();
    }

    private static final String CSS = """
            :root{--red:#f62e30;--ink:#1a1a1a;--muted:#6b7280;--line:#e2e4e8;--bg:#f5f6f8;--ok:#1f9d55;--high:#e07b00;--med:#0e8a84}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 Inter,system-ui,-apple-system,Segoe UI,sans-serif}
            main{max-width:1040px;margin:0 auto;padding:24px 16px 48px}h1{font-size:24px;margin:0 0 4px}h2{font-size:16px;margin:0 0 12px;border-bottom:2px solid var(--red);display:inline-block;padding-bottom:2px}
            section{background:#fff;border:1px solid var(--line);border-radius:8px;padding:18px 20px;margin:14px 0;break-inside:avoid-page}
            .top{display:flex;justify-content:space-between;gap:16px;align-items:center;background:#fff;border:1px solid var(--line);border-left:4px solid var(--red);border-radius:8px;padding:18px 20px}
            .eyebrow{font-size:11px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);margin:0 0 4px;font-weight:600}.muted{color:var(--muted)}.small{font-size:12.5px}
            .verdict{min-width:170px;text-align:center;border-radius:8px;padding:12px;color:#fff;display:flex;flex-direction:column;font-weight:600}
            .verdict .score{font-size:36px;font-weight:800;line-height:1}.verdict small{font-size:14px;opacity:.8}
            .v-VALIDATED{background:var(--ok)}.v-VALIDATED_WITH_RESERVES{background:var(--high)}.v-REJECTED{background:var(--red)}.v-INCONCLUSIVE{background:#6b7280}
            table{width:100%;border-collapse:collapse;margin:8px 0}th,td{text-align:left;padding:7px 8px;border-bottom:1px solid var(--line);vertical-align:top}
            th{font-size:11px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted);background:#fafafb}
            code,pre{font-family:'JetBrains Mono',Consolas,monospace;font-size:12px}pre{background:#1d2127;color:#e6e6e6;padding:10px;border-radius:6px;white-space:pre-wrap;word-break:break-word;max-height:300px;overflow:auto}
            .badge{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11px;font-weight:600;background:#eef0f3;color:#3a3f47}
            .s-PASS{background:#dcf3e5;color:#17773f}.s-WARN{background:#fdefd9;color:#9a5200}.s-FAIL,.sev-CRITICAL.badge{background:#fde3e3;color:#b3141a}.s-NO_DATA{background:#eef0f3;color:#5b6470}
            .sev-HIGH.badge{background:#fdefd9;color:#9a5200}
            .alert{border-radius:6px;padding:10px 12px;margin:10px 0}.alert ul{margin:6px 0 0 18px;padding:0}.crit{background:#fde3e3;color:#8f1116}.warn{background:#fdf3e2;color:#7a4300}.info{background:#e8f4f3;color:#0a5450}.ok{background:#dcf3e5;color:#17773f}
            .kpis{display:flex;flex-wrap:wrap;gap:10px;margin:6px 0 10px}.kpi{border:1px solid var(--line);border-radius:6px;padding:8px 12px;min-width:110px}.kpi b{display:block;font-size:20px}
            .trace li{margin-bottom:8px}.waterfall{margin:8px 0 14px}.wf-row{display:grid;grid-template-columns:220px 1fr 70px;gap:10px;align-items:center;padding:3px 0}
            .wf-bar{height:10px;background:#eef0f3;border-radius:999px;overflow:hidden}.wf-bar i{display:block;height:100%;background:var(--med)}.wf-bar i.lost{background:var(--red)}
            .wf-row.total span:first-child{font-weight:700}.incident{border:1px solid var(--line);border-left:4px solid var(--med);border-radius:6px;padding:10px 12px;margin:10px 0}
            .incident.sev-CRITICAL{border-left-color:var(--red)}.incident.sev-HIGH{border-left-color:var(--high)}
            .factor{display:grid;grid-template-columns:190px 160px 1fr;gap:10px;align-items:center;padding:2px 0}.bar{height:8px;background:#eef0f3;border-radius:999px;overflow:hidden}.bar i{display:block;height:100%;background:var(--red)}
            .risks{list-style:none;padding:0}.risks li{border-left:3px solid var(--line);padding:6px 10px;margin-bottom:6px;background:#fafafb}.risks li.sev-CRITICAL{border-left-color:var(--red)}.risks li.sev-HIGH{border-left-color:var(--high)}
            .recs li{margin-bottom:8px}blockquote{margin:8px 0;padding:6px 12px;border-left:3px solid var(--line);color:#3a3f47}
            details.failure{border:1px solid var(--line);border-radius:6px;padding:6px 10px;margin:6px 0}summary{cursor:pointer}
            footer{color:var(--muted);font-size:12px;text-align:center;margin-top:20px}
            @media print{body{background:#fff}section,.top{border-color:#ccc}details{display:block}details>summary{list-style:none}pre{max-height:none}}
            @media (max-width:640px){.top{flex-direction:column;align-items:flex-start}.wf-row{grid-template-columns:120px 1fr 50px}.factor{grid-template-columns:1fr}}
            """;

    // ------------------------------------------------------------ utilitaires

    private static String kpi(Object value, String label) {
        return "<div class=\"kpi\"><b>" + esc(String.valueOf(value)) + "</b><span class=\"muted small\">" + esc(label) + "</span></div>";
    }

    private static String layers(Map<String, Long> m) {
        if (m == null || m.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(LogTypeClassifier.label(k)).append(' ').append(v));
        return sb.toString();
    }

    static String statusLabel(String status) {
        return switch (status) {
            case "PASS" -> "Conforme";
            case "WARN" -> "Sous le seuil";
            case "FAIL" -> "Échec";
            default -> "Sans données";
        };
    }

    private static String verdictIcon(String verdict) {
        return switch (verdict) {
            case "VALIDATED" -> "✅";
            case "VALIDATED_WITH_RESERVES" -> "🟠";
            case "REJECTED" -> "❌";
            default -> "⚪";
        };
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String md(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    static String date(LocalDateTime d) {
        return d == null ? "—" : d.format(FMT);
    }

    private static String fmt(double v) {
        return AdaptiveValidationEngine.fmt(v);
    }

    private static String signed(double v) {
        return AdaptiveValidationEngine.signed(v);
    }

    private static String pct(double v) {
        return AdaptiveValidationEngine.pct(v);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
