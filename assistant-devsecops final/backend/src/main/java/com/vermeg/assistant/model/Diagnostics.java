/**
 * Role : definir les donnees du diagnostic explicable (incidents correles, cause racine, facteurs).
 * Contenu : definition de Diagnostics et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Modèle du diagnostic explicable. Chaque conclusion (cause racine, score) est
 * accompagnée des facteurs chiffrés qui l'expliquent : l'utilisateur voit POURQUOI
 * un signal est désigné comme cause probable, pas seulement QUE c'est le cas.
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /**
     * Facteur d'explication : valeur observée (0-1), poids dans le calcul et contribution
     * effective (valeur × poids). La somme des contributions donne le score.
     */
    public record ExplanationFactor(String code, String label, double value, double weight, double contribution,
                                    String detail) { }

    /** Signal = famille d'erreurs (message normalisé) apparue dans un incident, sur une couche donnée. */
    public record IncidentSignal(String key, String message, String layer, String source, String level,
                                 String errorCode, String category, int occurrences, LocalDateTime firstSeen,
                                 LocalDateTime lastSeen, String example) { }

    /** Candidat cause racine, avec son score (0-1), sa confiance (%) et ses facteurs d'explication. */
    public record RootCauseCandidate(IncidentSignal signal, double score, int confidencePercent,
                                     List<ExplanationFactor> factors, String rule, String rationale) { }

    /** Lien de causalité supposé entre deux signaux (règle de connaissance ou simple précédence temporelle). */
    public record CausalLink(String fromKey, String fromLabel, String toKey, String toLabel, String rule,
                             String explanation) { }

    /** Échec de test fourni à la corrélation (référence « test:Suite#methode »). */
    public record TestFailureRef(String ref, String suiteName, String testName, String message, String stackTrace) { }

    /** Incident : rafale d'erreurs proches dans le temps, éventuellement sur plusieurs couches. */
    public record CorrelatedIncident(String id, LocalDateTime start, LocalDateTime end, String severity,
                                     Set<String> layers, Set<String> sources, int eventCount,
                                     List<IncidentSignal> signals, RootCauseCandidate rootCause,
                                     List<RootCauseCandidate> alternatives, List<CausalLink> chain,
                                     List<String> impactedTests, String category, String summary) { }
}
