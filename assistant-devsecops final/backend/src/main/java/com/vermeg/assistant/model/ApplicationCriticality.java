/**
 * Role : definir le niveau de criticite metier d'une application.
 * Contenu : definition de ApplicationCriticality et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.model;

import java.util.Locale;

/**
 * Criticité métier d'une application. Elle pilote le moteur de validation adaptative :
 * plus une application est critique (paiement, titres...), plus les seuils sont exigeants
 * et plus la sécurité pèse dans le score.
 */
public enum ApplicationCriticality {
    LOW, MEDIUM, HIGH, CRITICAL;

    public static ApplicationCriticality parse(String raw) {
        if (raw == null || raw.isBlank()) return MEDIUM;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }
}
