/**
 * Role : fournir l acces aux donnees persistees de LogRecord via Spring Data.
 * Contenu : definition de LogRecordRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.LogRecord;
import com.vermeg.assistant.model.LogEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
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

    // ---------- Multi-applications et couches de logs ----------
    /** Logs d'une application : rattachés explicitement OU émis par une de ses sources déclarées. */
    @Query("select l from LogRecord l where l.applicationCode = :app or l.source in :sources")
    Page<LogRecord> findForApplication(@Param("app") String app,
            @Param("sources") Collection<String> sources, Pageable pageable);

    @Query("select l.logType, count(l) from LogRecord l group by l.logType")
    List<Object[]> countByLogType();

    @Query("select l.applicationCode, count(l) from LogRecord l group by l.applicationCode")
    List<Object[]> countByApplication();

    @Query("select count(l) from LogRecord l where (l.applicationCode = :app or l.source in :sources) and l.timestamp > :after")
    long countForApplicationAfter(@Param("app") String app, @Param("sources") Collection<String> sources,
                                  @Param("after") LocalDateTime after);

    @Query("select l from LogRecord l where l.timestamp > :after and l.level in :levels")
    List<LogRecord> findAfter(@Param("after") LocalDateTime after,
                              @Param("levels") Collection<LogEntry.LogLevel> levels);

    long countByTimestampAfter(LocalDateTime after);

    @Modifying
    @Query("update LogRecord l set l.applicationCode = :code where l.applicationCode is null")
    int assignOrphans(@Param("code") String code);
}
