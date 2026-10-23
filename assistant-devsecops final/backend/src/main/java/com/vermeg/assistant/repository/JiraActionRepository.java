/**
 * Role : acceder aux actions Jira tracees.
 * Contenu : definition de JiraActionRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.JiraActionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JiraActionRepository extends JpaRepository<JiraActionRecord, Long> {

    List<JiraActionRecord> findAllByOrderByProposedAtDesc();

    List<JiraActionRecord> findByStatusIn(List<String> statuses);

    List<JiraActionRecord> findByFingerprintAndStatusIn(String fingerprint, List<String> statuses);
}
