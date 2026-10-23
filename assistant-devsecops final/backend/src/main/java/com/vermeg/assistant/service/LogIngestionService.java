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
    private final ApplicationService applicationService;

    public LogIngestionService(LogParserService parser, LogStore logStore,
                               LogAnalysisService analysisService, EventService eventService,
                               ApplicationService applicationService) {
        this.parser = parser;
        this.logStore = logStore;
        this.analysisService = analysisService;
        this.eventService = eventService;
        this.applicationService = applicationService;
    }

    public ApiDtos.IngestResult ingestText(String raw, String defaultSource) {
        return ingestText(raw, defaultSource, null, null);
    }

    /**
     * @param application code de l'application émettrice (null = déduit de la source, sinon application par défaut)
     * @param logType     APPLICATION, SERVER ou CICD (null = déduit ligne par ligne)
     */
    public ApiDtos.IngestResult ingestText(String raw, String defaultSource, String application, String logType) {
        return ingestStream(new ByteArrayInputStream(raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8)),
                defaultSource, application, logType);
    }

    public ApiDtos.IngestResult ingestStream(InputStream in, String defaultSource) {
        return ingestStream(in, defaultSource, null, null);
    }

    public ApiDtos.IngestResult ingestStream(InputStream in, String defaultSource, String application, String logType) {
        String type = normalizeType(logType);
        LogParserService.ParseResult result = parser.parseWithStats(in, type);
        if (defaultSource != null && !defaultSource.isBlank()) {
            result.entries().stream().filter(e -> e.getSource() == null || "unknown".equals(e.getSource())
                            || ("ci-pipeline".equals(e.getSource()) && LogTypeClassifier.CICD.equals(type)))
                    .forEach(e -> e.setSource(defaultSource));
        }
        if (application != null && !application.isBlank()) {
            String app = applicationService.resolve(application, null);
            result.entries().forEach(e -> e.setApplication(app));
        }
        return ingestEntries(result.entries(), result.skippedLines());
    }

    private static String normalizeType(String logType) {
        if (logType == null || logType.isBlank()) return null;
        String t = logType.trim().toUpperCase();
        if ("CI".equals(t) || "CI_CD".equals(t) || "CI/CD".equals(t) || "PIPELINE".equals(t)) t = LogTypeClassifier.CICD;
        if ("SERVEUR".equals(t) || "WILDFLY".equals(t) || "JBOSS".equals(t)) t = LogTypeClassifier.SERVER;
        if ("APP".equals(t) || "APPLICATIF".equals(t)) t = LogTypeClassifier.APPLICATION;
        if (!LogTypeClassifier.TYPES.contains(t)) {
            throw new IllegalArgumentException("Type de log invalide : " + logType + " (attendu : APPLICATION, SERVER ou CICD)");
        }
        return t;
    }

    public synchronized ApiDtos.IngestResult ingestEntries(List<LogEntry> entries, int skipped) {
        if (entries.isEmpty()) {
            return new ApiDtos.IngestResult(0, skipped, logStore.size(), 0);
        }
        // Rattachement : application explicite, sinon application déclarant la source, sinon défaut ;
        // couche : imposée par le parseur ou l'appelant, sinon déduite par règles.
        for (LogEntry e : entries) {
            if (e.getApplication() == null) e.setApplication(applicationService.resolve(null, e.getSource()));
            if (e.getLogType() == null) e.setLogType(LogTypeClassifier.classify(e.getSource(), e.getMessage()));
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
