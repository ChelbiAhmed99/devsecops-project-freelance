/**
 * Role : fournir l acces aux donnees persistees de TestRun via Spring Data.
 * Contenu : definition de TestRunRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.TestRunRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

public interface TestRunRepository extends JpaRepository<TestRunRecord, Long> {

    Optional<TestRunRecord> findFirstByOrderByExecutedAtDesc();

    List<TestRunRecord> findAllByOrderByExecutedAtDesc(Pageable pageable);

    // ---------- Multi-applications ----------
    Optional<TestRunRecord> findFirstByApplicationCodeOrderByExecutedAtDesc(String applicationCode);

    List<TestRunRecord> findByApplicationCodeOrderByExecutedAtDesc(String applicationCode, Pageable pageable);

    List<TestRunRecord> findByApplicationCodeAndExecutedAtAfterOrderByExecutedAtAsc(String applicationCode, LocalDateTime after);

    List<TestRunRecord> findByExecutedAtAfterOrderByExecutedAtAsc(LocalDateTime after);

    long countByApplicationCode(String applicationCode);

    /** Migration : rattache les exécutions historiques (sans application) à l'application par défaut. */
    @Modifying
    @Query("update TestRunRecord r set r.applicationCode = :code where r.applicationCode is null")
    int assignOrphans(@Param("code") String code);
}
