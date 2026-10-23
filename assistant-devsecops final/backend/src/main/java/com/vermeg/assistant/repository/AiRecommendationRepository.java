/**
 * Role : fournir l acces aux donnees persistees de AiRecommendation via Spring Data.
 * Contenu : definition de AiRecommendationRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.AiRecommendationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiRecommendationRepository extends JpaRepository<AiRecommendationRecord, String> {

    List<AiRecommendationRecord> findByIncidentRefOrderByCreatedAtDesc(String incidentRef);

    List<AiRecommendationRecord> findByIncidentRefAndStatus(String incidentRef,
            com.vermeg.assistant.model.AiRecommendation.ValidationStatus status);
}
