/**
 * Role : enregistrer et retrouver les recommandations generees par l assistant.
 * Contenu : definition de AiRecommendationStore et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.domain.AiRecommendationRecord;
import com.vermeg.assistant.model.AiRecommendation;
import com.vermeg.assistant.repository.AiRecommendationRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Recommandations IA persistées. Toute recommandation naît en PENDING_REVIEW ;
 * seule une validation humaine explicite la fait passer à APPROVED ou REJECTED.
 */
@Component
public class AiRecommendationStore {

    private final AiRecommendationRepository repository;

    public AiRecommendationStore(AiRecommendationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void save(AiRecommendation recommendation) {
        repository.save(AiRecommendationRecord.from(recommendation));
    }

    @Transactional(readOnly = true)
    public List<AiRecommendation> getAll() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(AiRecommendationRecord::toModel).toList();
    }

    @Transactional(readOnly = true)
    public Optional<AiRecommendation> findById(String id) {
        return repository.findById(id).map(AiRecommendationRecord::toModel);
    }

    /** Dernière recommandation non rejetée pour un même incident (évite de rappeler le LLM inutilement). */
    @Transactional(readOnly = true)
    public Optional<AiRecommendation> findActiveByIncidentRef(String incidentRef) {
        if (incidentRef == null || incidentRef.isBlank()) return Optional.empty();
        return repository.findByIncidentRefOrderByCreatedAtDesc(incidentRef).stream()
                .filter(r -> r.getStatus() != AiRecommendation.ValidationStatus.REJECTED)
                .map(AiRecommendationRecord::toModel)
                .findFirst();
    }

    /** Supprime les fiches encore « À valider » d'un incident (utilisé lors d'une régénération). */
    @Transactional
    public void deletePendingByIncidentRef(String incidentRef) {
        if (incidentRef == null || incidentRef.isBlank()) return;
        repository.deleteAll(repository.findByIncidentRefAndStatus(incidentRef,
                AiRecommendation.ValidationStatus.PENDING_REVIEW));
    }

    @Transactional
    public boolean updateStatus(String id, AiRecommendation.ValidationStatus status) {
        return review(id, status, null, null);
    }

    @Transactional
    public boolean review(String id, AiRecommendation.ValidationStatus status, String reviewer, String comment) {
        Optional<AiRecommendationRecord> record = repository.findById(id);
        if (record.isEmpty()) {
            return false;
        }
        AiRecommendationRecord r = record.get();
        r.setStatus(status);
        r.setReviewedBy(reviewer);
        r.setReviewedAt(LocalDateTime.now());
        r.setReviewComment(comment);
        repository.save(r);
        return true;
    }

    public long count(AiRecommendation.ValidationStatus status) {
        return repository.findAll().stream().filter(r -> r.getStatus() == status).count();
    }
}
