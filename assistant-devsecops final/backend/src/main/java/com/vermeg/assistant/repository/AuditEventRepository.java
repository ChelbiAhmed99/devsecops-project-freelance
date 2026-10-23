/**
 * Role : fournir l acces aux donnees persistees de AuditEvent via Spring Data.
 * Contenu : definition de AuditEventRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findAllByOrderByAtDesc(Pageable pageable);
}
