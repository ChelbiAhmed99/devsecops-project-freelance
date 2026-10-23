/**
 * Role : acceder aux resultats de validation par application.
 * Contenu : definition de ApplicationValidationRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.ApplicationValidationRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationValidationRepository extends JpaRepository<ApplicationValidationRecord, Long> {

    List<ApplicationValidationRecord> findByCampaignIdOrderByApplicationNameAsc(Long campaignId);

    List<ApplicationValidationRecord> findByApplicationCodeAndStateOrderByFinishedAtDesc(String applicationCode, String state, Pageable pageable);

    Optional<ApplicationValidationRecord> findFirstByApplicationCodeAndStateOrderByFinishedAtDesc(String applicationCode, String state);

    /** Validations revues par un humain (apprentissage de la confiance accordée à l'IA). */
    List<ApplicationValidationRecord> findByHumanDecisionIsNotNullAndLlmOpinionIsNotNullOrderByDecidedAtDesc(Pageable pageable);

    long countByReviewRequiredTrueAndHumanDecisionIsNullAndState(String state);
}
