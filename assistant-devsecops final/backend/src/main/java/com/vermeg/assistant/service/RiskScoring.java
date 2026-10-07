package com.vermeg.assistant.service;

import com.vermeg.assistant.model.Vulnerability;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Score de priorité (0-100) d'une vulnérabilité, pour trier ce qu'il faut traiter
 * en premier. Combine :
 * - la gravité (score CVSS si disponible, sinon niveau de sévérité) : jusqu'à 80 pts ;
 * - l'exposition : les images de conteneur et dépendances sont exploitables à distance (+5) ;
 * - l'actionnabilité : une version corrigée existe (+8) ;
 * - l'ancienneté : +1 pt par semaine sans correction (max +7).
 */
public final class RiskScoring {

    private RiskScoring() {
    }

    public static int score(Vulnerability.Severity severity, Double cvss, String source,
                            String fixedVersion, LocalDateTime firstDetectedAt, LocalDateTime now) {
        double base;
        if (cvss != null && cvss > 0) {
            base = cvss * 8.0;
        } else {
            base = switch (severity == null ? Vulnerability.Severity.UNKNOWN : severity) {
                case CRITICAL -> 76;
                case HIGH -> 60;
                case MEDIUM -> 40;
                case LOW -> 16;
                case UNKNOWN -> 30;
            };
        }
        double score = base;
        if ("trivy".equals(source) || "dependency-check".equals(source)) score += 5;
        if (fixedVersion != null && !fixedVersion.isBlank()) score += 8;
        if (firstDetectedAt != null && now != null) {
            long weeks = Math.max(0, Duration.between(firstDetectedAt, now).toDays() / 7);
            score += Math.min(7, weeks);
        }
        return (int) Math.max(0, Math.min(100, Math.round(score)));
    }
}
