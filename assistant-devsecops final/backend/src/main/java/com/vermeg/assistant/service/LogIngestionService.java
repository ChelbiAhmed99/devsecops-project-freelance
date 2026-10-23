/**
 * Role : recevoir et enregistrer les journaux entrants.
 * Contenu : definition de LogIngestionService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.LogEntry;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Point d'entrée unique de l'ingestion (API, pipeline CI/CD, upload de fichier,
 * collecte Loki) : parsing, persistance, puis détection des NOUVELLES anomalies
 * critiques pour notifier le tableau de bord en temps réel.
 */
@Service
public class LogIngestionService {

    private final LogParserService parser;
    private final LogStore logStore;
    private final LogAnalysisService analysisService;
    private final EventService eventService;

    public LogIngestionService(LogParserService parser, LogStore logStore,
                               LogAnalysisService analysisService, EventService eventService) {
        this.parser = parser;
        this.logStore = logStore;
        this.analysisService = analysisService;
        this.eventService = eventService;
    }

    public ApiDtos.IngestResult ingestText(String raw, String defaultSource) {
        return ingestStream(new ByteArrayInputStream(raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8)),
                defaultSource);
    }

    public ApiDtos.IngestResult ingestStream(InputStream in, String defaultSource) {
        LogParserService.ParseResult result = parser.parseWithStats(in);
        if (defaultSource != null && !defaultSource.isBlank()) {
            result.entries().stream().filter(e -> e.getSource() == null || "unknown".equals(e.getSource()))
                    .forEach(e -> e.setSource(defaultSource));
        }
        return ingestEntries(result.entries(), result.skippedLines());
    }

    public synchronized ApiDtos.IngestResult ingestEntries(List<LogEntry> entries, int skipped) {
        if (entries.isEmpty()) {
            return new ApiDtos.IngestResult(0, skipped, logStore.size(), 0);
        }
        Set<String> criticalBefore = criticalKeys();
        logStore.addAll(entries);
        List<AnomalyGroup> after = analysisService.detectAnomalies(logStore.getAll());
        List<AnomalyGroup> newCritical = after.stream()
                .filter(AnomalyGroup::isCritical)
                .filter(g -> !criticalBefore.contains(g.getKey()))
                .toList();
        for (AnomalyGroup g : newCritical) {
            eventService.publish("anomaly.critical", "critical", "Nouvelle anomalie critique",
                    Map.of("key", g.getKey(), "message", g.getNormalizedMessage(),
                            "occurrences", g.getOccurrences(), "sources", String.join(", ", g.getAffectedSources())));
        }
        eventService.publish("logs.ingested", "info", entries.size() + " logs ingérés",
                Map.of("count", entries.size()));
        long critical = after.stream().filter(AnomalyGroup::isCritical).count();
        return new ApiDtos.IngestResult(entries.size(), skipped, logStore.size(), (int) critical);
    }

    private Set<String> criticalKeys() {
        return analysisService.detectAnomalies(logStore.getAll()).stream()
                .filter(AnomalyGroup::isCritical)
                .map(AnomalyGroup::getKey)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
