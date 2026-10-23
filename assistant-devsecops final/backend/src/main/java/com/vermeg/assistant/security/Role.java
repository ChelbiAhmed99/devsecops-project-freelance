/**
 * Role : fournir le composant de securite Role, utilise pour l authentification, les autorisations ou la gestion des acces.
 * Contenu : definition de Role et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.security;

/**
 * Rôles applicatifs (cahier des charges : développeur, responsable qualité/sécurité),
 * complétés par un administrateur et un rôle technique pour le pipeline CI/CD.
 */
public enum Role {
    DEVELOPER,
    QUALITY_MANAGER,
    ADMIN,
    CI;

    public String authority() {
        return "ROLE_" + name();
    }
}
