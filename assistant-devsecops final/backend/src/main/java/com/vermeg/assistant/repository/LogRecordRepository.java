/**
 * Role : fournir l acces aux donnees persistees de LogRecord via Spring Data.
 * Contenu : definition de LogRecordRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.LogRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LogRecordRepository extends JpaRepository<LogRecord, Long>, JpaSpecificationExecutor<LogRecord> {

    @Query("select l.level, count(l) from LogRecord l group by l.level")
    List<Object[]> countByLevel();

    @Query("select l.source, count(l) from LogRecord l group by l.source order by count(l) desc")
    List<Object[]> countBySource();

    @Query("select min(l.timestamp), max(l.timestamp) from LogRecord l")
    List<Object[]> timeRange();

    @Query("select max(l.ingestedAt) from LogRecord l")
    LocalDateTime lastIngestedAt();

    @Query("select distinct l.source from LogRecord l order by l.source")
    List<String> distinctSources();

    @Modifying
    @Query("delete from LogRecord l where l.timestamp < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
