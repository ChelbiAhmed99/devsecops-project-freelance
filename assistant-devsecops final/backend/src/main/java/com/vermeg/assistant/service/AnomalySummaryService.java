package com.vermeg.assistant.service;

import com.vermeg.assistant.model.AnomalyGroup;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Résumé en langage naturel d'un groupe d'anomalies, à base de règles (sans
 * appel externe, donc instantané et toujours disponible). L'explication
 * approfondie est ensuite demandée à la brique IA à la demande de l'utilisateur.
 */
@Service
public class AnomalySummaryService {

    private static final Map<String, String> CATEGORY_HINTS = Map.of(
            "DATABASE", "Piste : disponibilité de la base, dimensionnement du pool de connexions ou requêtes lentes.",
            "NETWORK", "Piste : joignabilité du service distant, timeouts réseau ou surcharge de la dépendance.",
            "SECURITY", "Piste : validité des jetons/certificats, configuration des clés ou tentative d'accès illégitime.",
            "CODE_DEFECT", "Piste : défaut de code (valeur nulle ou état inattendu) à corriger et à couvrir par un test.",
            "RESOURCE", "Piste : saturation de ressources (mémoire, threads, disque) à surveiller et redimensionner.",
            "CONFIGURATION", "Piste : paramètre ou ressource manquant(e) dans la configuration de l'environnement.",
            "PERFORMANCE", "Piste : dégradation de performance, à corréler avec la charge et les derniers déploiements.");

    public String summarize(AnomalyGroup group) {
        Set<String> sources = group.getAffectedSources();
        String sourceLabel = sources.size() == 1
                ? "le service " + sources.iterator().next()
                : sources.size() + " services (" + String.join(", ", sources) + ")";

        StringBuilder sb = new StringBuilder();
        sb.append("L'erreur \"").append(group.getNormalizedMessage()).append("\" ")
          .append("est survenue ").append(group.getOccurrences()).append(" fois")
          .append(", affectant ").append(sourceLabel);

        if (group.getFirstSeen() != null && group.getLastSeen() != null && group.getOccurrences() > 1) {
            long minutes = Duration.between(group.getFirstSeen(), group.getLastSeen()).toMinutes();
            sb.append(", sur une période de ").append(formatDuration(minutes));
        }
        sb.append(". ");

        if (group.isCritical()) {
            sb.append("Sa fréquence dépasse le seuil d'alerte : elle nécessite une attention prioritaire.");
        } else {
            sb.append("Sa fréquence reste sous le seuil d'alerte, mais elle mérite d'être surveillée.");
        }

        String hint = group.getCategory() == null ? null : CATEGORY_HINTS.get(group.getCategory());
        if (hint != null) {
            sb.append(' ').append(hint);
        }
        return sb.toString();
    }

    private String formatDuration(long minutes) {
        if (minutes < 1) return "moins d'une minute";
        if (minutes < 60) return minutes + " min";
        if (minutes < 60 * 48) return (minutes / 60) + " h " + (minutes % 60) + " min";
        return (minutes / (60 * 24)) + " jours";
    }
}
