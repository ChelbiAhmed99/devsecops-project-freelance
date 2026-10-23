/**
 * Role : fournir l acces aux donnees persistees de TestRun via Spring Data.
 * Contenu : definition de TestRunRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.TestRunRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestRunRepository extends JpaRepository<TestRunRecord, Long> {

    Optional<TestRunRecord> findFirstByOrderByExecutedAtDesc();

    List<TestRunRecord> findAllByOrderByExecutedAtDesc(Pageable pageable);
}
