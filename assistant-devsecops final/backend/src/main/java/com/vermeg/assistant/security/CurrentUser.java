package com.vermeg.assistant.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Accès simplifié à l'utilisateur courant (audit, traçabilité des actions). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }

    public static boolean hasRole(Role role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> role.authority().equals(a.getAuthority()));
    }
}
