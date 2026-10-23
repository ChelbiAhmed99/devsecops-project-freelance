/**
 * Role : comparer des textes pour rapprocher des messages similaires.
 * Contenu : definition de TextSimilarityUtil et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Sprint 3 : petite brique de similarité textuelle utilisée pour regrouper
 * des messages d'erreur qui se ressemblent fortement sans être identiques
 * après normalisation (ex: un mot en plus, un ordre légèrement différent).
 * <p>
 * On reste volontairement simple (Jaccard sur des ensembles de mots) :
 * c'est peu coûteux, explicable facilement en soutenance, et suffisant
 * pour affiner le clustering du Sprint 1/2. Une approche plus avancée
 * (embeddings, TF-IDF + cosinus) pourrait être présentée comme piste
 * d'amélioration dans le rapport si le temps manque pour l'implémenter.
 */
public final class TextSimilarityUtil {

    private TextSimilarityUtil() {
    }

    /**
     * Calcule la similarité de Jaccard entre deux textes : taille de
     * l'intersection des mots divisée par la taille de l'union.
     * Résultat entre 0.0 (aucun mot commun) et 1.0 (mêmes mots).
     */
    public static double jaccardSimilarity(String textA, String textB) {
        Set<String> tokensA = tokenize(textA);
        Set<String> tokensB = tokenize(textB);

        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return 1.0;
        }

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);

        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);

        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new HashSet<>();
        }
        return new HashSet<>(Arrays.asList(
                text.toLowerCase().trim().split("\\s+")
        ));
    }
}
