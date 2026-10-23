/**
 * Role : appliquer les ajustements de schema que la mise a jour automatique d'Hibernate ne sait pas faire.
 * Contenu : definition de LegacySchemaMigration et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passage au multi-applications : l'ancienne contrainte d'unicité des vulnérabilités
 * (source, CVE, composant) empêcherait qu'une même CVE soit suivie dans deux
 * applications. Elle est remplacée par uk_vuln_app (application incluse), créée par
 * Hibernate ; l'ancienne est supprimée ici (syntaxe compatible H2 et PostgreSQL).
 */
@Component
public class LegacySchemaMigration {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void dropLegacyVulnerabilityConstraint() {
        entityManager.createNativeQuery("ALTER TABLE vulnerability_record DROP CONSTRAINT IF EXISTS uk_vuln")
                .executeUpdate();
    }
}
