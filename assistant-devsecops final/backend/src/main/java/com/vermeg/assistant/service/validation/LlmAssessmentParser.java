/**
 * Role : interpreter et controler l'avis du LLM sur une validation (format, bornes, ancrage dans les preuves).
 * Contenu : definition de LlmAssessmentParser et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.vermeg.assistant.model.ValidationModels.LlmAssessment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Garde-fous appliqués à la réponse du LLM avant qu'elle n'influence quoi que ce soit :
 * <ul>
 *   <li>format : avis limité à VALIDATE / RESERVES / REJECT, confiance 0-1, ajustement numérique ;</li>
 *   <li>ancrage : les identifiants cités (CVE, tests, incidents, anomalies) doivent exister dans les
 *       preuves transmises ; sinon l'avis est marqué « non ancré » et n'a aucun effet sur le score
 *       (détection d'hallucination).</li>
 * </ul>
 */
public final class LlmAssessmentParser {

    private static final Pattern REFS = Pattern.compile(
            "(CVE-\\d{4}-\\d{4,7}|GHSA-[\\w-]{14}|test:[\\w.$]+#[\\w$\\[\\]()-]+|incident-[0-9a-f]{16}|anomaly-[0-9a-f]{16})");

    private LlmAssessmentParser() {
    }

    public static LlmAssessment parse(JsonNode root, Set<String> allowedRefs) {
        if (root == null || !root.isObject()) {
            return LlmAssessment.unavailable("UNAVAILABLE", "Réponse IA illisible");
        }
        String opinion = root.path("opinion").asText("").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("VALIDATE", "RESERVES", "REJECT").contains(opinion)) opinion = null;
        double confidence = clamp(root.path("confidence").asDouble(0.5), 0, 1);
        double adjustment = clamp(root.path("adjustment").asDouble(0), -50, 50);
        String justification = root.path("justification").asText("");
        List<String> risks = strings(root.path("risks"));
        List<String> recommendations = strings(root.path("recommendations"));
        List<String> cited = strings(root.path("cited_refs"));

        // Références citées explicitement ET identifiants repérés dans le texte libre.
        Set<String> mentioned = new LinkedHashSet<>(cited);
        StringBuilder all = new StringBuilder(justification);
        risks.forEach(r -> all.append('\n').append(r));
        recommendations.forEach(r -> all.append('\n').append(r));
        Matcher m = REFS.matcher(all);
        while (m.find()) mentioned.add(m.group(1));
        List<String> ungrounded = new ArrayList<>();
        for (String ref : mentioned) {
            if (!isAllowed(ref, allowedRefs)) ungrounded.add(ref);
        }
        return new LlmAssessment("OK", opinion, confidence, adjustment, 0, 0, justification, risks, recommendations,
                new ArrayList<>(mentioned), ungrounded, root.path("provider").asText(null), root.path("model").asText(null), null);
    }

    private static boolean isAllowed(String ref, Set<String> allowed) {
        if (allowed.contains(ref)) return true;
        // Une CVE est ancrée si elle apparaît dans une référence de vulnérabilité transmise (vuln:source:CVE:...)
        for (String a : allowed) {
            if (a.contains(ref)) return true;
        }
        return false;
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode n : node) {
            String t = n.asText("").trim();
            if (!t.isEmpty()) out.add(t.length() > 400 ? t.substring(0, 397) + "..." : t);
            if (out.size() >= 8) break;
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? 0 : Math.max(lo, Math.min(hi, v));
    }
}
