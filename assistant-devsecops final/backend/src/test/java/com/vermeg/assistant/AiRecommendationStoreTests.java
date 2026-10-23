/**
 * Role : documenter le comportement attendu en verifiant l enregistrement et la consultation des recommandations IA.
 * Contenu : definition de AiRecommendationStoreTests et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant;

import com.vermeg.assistant.model.AiRecommendation;
import com.vermeg.assistant.service.AiRecommendationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Store de recommandations IA (persisté en base). Règle métier centrale : toute
 * recommandation naît en PENDING_REVIEW et ne devient APPROVED/REJECTED que sur
 * action humaine explicite, tracée (auteur, date, commentaire).
 */
@DataJpaTest
@Import(AiRecommendationStore.class)
class AiRecommendationStoreTests {

    @Autowired
    private AiRecommendationStore store;

    private AiRecommendation recommendation(String id, String incidentRef) {
        AiRecommendation r = new AiRecommendation(id, "log_anomaly", "Connection timeout",
                "Explication de test", "Correction de test", "Gravité moyenne",
                AiRecommendation.ValidationStatus.PENDING_REVIEW, LocalDateTime.now());
        r.setIncidentRef(incidentRef);
        r.setProvider("heuristic");
        return r;
    }

    @Test
    void newRecommendationShouldStartPendingReview() {
        store.save(recommendation("rec-1", "anomaly-1"));

        assertThat(store.findById("rec-1")).isPresent();
        assertThat(store.findById("rec-1").get().getStatus())
                .isEqualTo(AiRecommendation.ValidationStatus.PENDING_REVIEW);
    }

    @Test
    void reviewShouldTraceReviewerAndComment() {
        store.save(recommendation("rec-2", "anomaly-2"));

        boolean updated = store.review("rec-2", AiRecommendation.ValidationStatus.APPROVED, "qualite", "Correctif appliqué");

        assertThat(updated).isTrue();
        AiRecommendation reviewed = store.findById("rec-2").orElseThrow();
        assertThat(reviewed.getStatus()).isEqualTo(AiRecommendation.ValidationStatus.APPROVED);
        assertThat(reviewed.getReviewedBy()).isEqualTo("qualite");
        assertThat(reviewed.getReviewComment()).isEqualTo("Correctif appliqué");
        assertThat(reviewed.getReviewedAt()).isNotNull();
    }

    @Test
    void updatingUnknownRecommendationShouldReturnFalse() {
        assertThat(store.updateStatus("inconnu", AiRecommendation.ValidationStatus.REJECTED)).isFalse();
    }

    @Test
    void rejectedRecommendationShouldNotBeReusedAsCache() {
        store.save(recommendation("rec-3", "anomaly-3"));
        assertThat(store.findActiveByIncidentRef("anomaly-3")).isPresent();

        store.updateStatus("rec-3", AiRecommendation.ValidationStatus.REJECTED);

        assertThat(store.findActiveByIncidentRef("anomaly-3")).isEmpty();
    }
}
