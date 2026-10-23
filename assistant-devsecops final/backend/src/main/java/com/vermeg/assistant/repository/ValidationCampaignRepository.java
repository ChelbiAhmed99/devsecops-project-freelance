/**
 * Role : acceder aux campagnes de validation persistees.
 * Contenu : definition de ValidationCampaignRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.ValidationCampaignRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ValidationCampaignRepository extends JpaRepository<ValidationCampaignRecord, Long> {

    List<ValidationCampaignRecord> findAllByOrderByStartedAtDesc(Pageable pageable);
}
