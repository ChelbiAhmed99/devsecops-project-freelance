/**
 * Role : fournir le stockage des evenements de journalisation.
 * Contenu : definition de LogStore et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.domain.LogRecord;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.repository.LogRecordRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Store des logs, désormais persisté en base (PostgreSQL / H2). L'interface
 * publique est restée celle du Sprint 2 (addAll / getAll / clear / size) :
 * les services d'analyse n'ont pas eu à changer.
 */
@Component
public class LogStore {

    private final LogRecordRepository repository;
    private final int analysisWindow;

    public LogStore(LogRecordRepository repository,
                    @Value("${assistant.logs.analysis-window:20000}") int analysisWindow) {
        this.repository = repository;
        this.analysisWindow = analysisWindow;
    }

    @Transactional
    public void addAll(List<LogEntry> newEntries) {
        if (newEntries == null || newEntries.isEmpty()) return;
        repository.saveAll(newEntries.stream().map(LogRecord::from).toList());
    }

    /** Les N logs les plus récents (fenêtre d'analyse), triés chronologiquement. */
    @Transactional(readOnly = true)
    public List<LogEntry> getAll() {
        List<LogEntry> latest = new ArrayList<>(repository
                .findAll(PageRequest.of(0, analysisWindow, Sort.by(Sort.Direction.DESC, "timestamp")))
                .map(LogRecord::toEntry)
                .getContent());
        Collections.reverse(latest);
        return latest;
    }

    /**
     * Fenêtre d'analyse d'UNE application : logs rattachés explicitement à son code ou émis
     * par une des sources qu'elle déclare (robuste à une déclaration de source tardive).
     */
    @Transactional(readOnly = true)
    public List<LogEntry> getForApplication(String applicationCode, Collection<String> sources) {
        Collection<String> safeSources = sources == null || sources.isEmpty() ? List.of("__aucune_source__") : sources;
        List<LogEntry> latest = new ArrayList<>(repository
                .findForApplication(applicationCode, safeSources,
                        PageRequest.of(0, analysisWindow, Sort.by(Sort.Direction.DESC, "timestamp")))
                .map(LogRecord::toEntry)
                .getContent());
        Collections.reverse(latest);
        return latest;
    }

    @Transactional
    public void clear() {
        repository.deleteAllInBatch();
    }

    @Transactional
    public int purgeOlderThan(LocalDateTime cutoff) {
        return repository.deleteOlderThan(cutoff);
    }

    public long size() {
        return repository.count();
    }
}
