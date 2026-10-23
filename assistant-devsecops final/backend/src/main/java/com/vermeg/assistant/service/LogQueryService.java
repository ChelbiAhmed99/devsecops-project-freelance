/**
 * Role : rechercher et filtrer les journaux enregistres.
 * Contenu : definition de LogQueryService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.domain.LogRecord;
import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.repository.LogRecordRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Recherche multicritère paginée, statistiques et détection de pics sur les
 * logs persistés. Combine la détection statistique locale (z-score robuste) et,
 * si le service IA est disponible, un modèle IsolationForest (scikit-learn).
 */
@Service
public class LogQueryService {

    private final LogRecordRepository repository;
    private final LogStore logStore;
    private final LogAnalysisService analysisService;
    private final TimeSeriesAnomalyDetector detector;
    private final AiClient aiClient;

    public LogQueryService(LogRecordRepository repository, LogStore logStore, LogAnalysisService analysisService,
                           TimeSeriesAnomalyDetector detector, AiClient aiClient) {
        this.repository = repository;
        this.logStore = logStore;
        this.analysisService = analysisService;
        this.detector = detector;
        this.aiClient = aiClient;
    }

    public record AnomalyResult(String engine, List<AnomalyGroup> groups) { }

    @Transactional(readOnly = true)
    public ApiDtos.LogPage search(String level, String source, String query, LocalDateTime from,
                                  LocalDateTime to, int page, int size) {
        return search(level, source, query, from, to, page, size, null, null);
    }

    /**
     * Recherche multicritère ; {@code logType} : APPLICATION | SERVER | CICD (séparés par des virgules),
     * {@code application} : code d'application. Les logs antérieurs à la classification par couche
     * (logType nul) sont traités comme APPLICATION.
     */
    @Transactional(readOnly = true)
    public ApiDtos.LogPage search(String level, String source, String query, LocalDateTime from,
                                  LocalDateTime to, int page, int size, String logType, String application) {
        Specification<LogRecord> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (logType != null && !logType.isBlank()) {
                List<String> types = new ArrayList<>();
                for (String t : logType.split(",")) types.add(t.trim().toUpperCase(Locale.ROOT));
                Predicate typed = root.get("logType").in(types);
                predicates.add(types.contains(LogTypeClassifier.APPLICATION)
                        ? cb.or(typed, cb.isNull(root.get("logType"))) : typed);
            }
            if (application != null && !application.isBlank()) {
                predicates.add(cb.equal(root.get("applicationCode"), application.trim()));
            }
            if (level != null && !level.isBlank()) {
                List<LogEntry.LogLevel> levels = new ArrayList<>();
                for (String l : level.split(",")) {
                    levels.add(LogEntry.LogLevel.valueOf(l.trim().toUpperCase(Locale.ROOT)));
                }
                predicates.add(root.get("level").in(levels));
            }
            if (source != null && !source.isBlank()) {
                predicates.add(cb.equal(root.get("source"), source.trim()));
            }
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("message")), pattern));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        int safeSize = Math.max(1, Math.min(size, 500));
        Page<LogRecord> result = repository.findAll(spec,
                PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "timestamp")));
        return new ApiDtos.LogPage(result.getContent().stream().map(LogRecord::toEntry).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ApiDtos.LogStats stats() {
        Map<String, Long> byLevel = new LinkedHashMap<>();
        for (LogEntry.LogLevel l : LogEntry.LogLevel.values()) byLevel.put(l.name(), 0L);
        long total = 0;
        long errors = 0;
        for (Object[] row : repository.countByLevel()) {
            long count = ((Number) row[1]).longValue();
            byLevel.put(String.valueOf(row[0]), count);
            total += count;
            if (row[0] instanceof LogEntry.LogLevel lvl && lvl.isErrorLike()) errors += count;
        }
        Map<String, Long> bySource = new LinkedHashMap<>();
        for (Object[] row : repository.countBySource()) {
            bySource.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        LocalDateTime first = null;
        LocalDateTime last = null;
        List<Object[]> range = repository.timeRange();
        if (!range.isEmpty() && range.get(0) != null && range.get(0).length == 2) {
            first = (LocalDateTime) range.get(0)[0];
            last = (LocalDateTime) range.get(0)[1];
        }
        List<LogEntry> window = logStore.getAll();
        int bucket = adaptiveBucket(window);
        double errorRate = total == 0 ? 0 : Math.round(10000.0 * errors / total) / 100.0;
        return new ApiDtos.LogStats(total, byLevel, bySource, detector.timeline(window, bucket), first, last, errorRate);
    }

    public List<String> sources() {
        return repository.distinctSources();
    }

    /** Volumes par couche (applicatif / serveur / CI) et par application. */
    @Transactional(readOnly = true)
    public Map<String, Object> breakdown() {
        Map<String, Long> byType = new LinkedHashMap<>();
        LogTypeClassifier.TYPES.forEach(t -> byType.put(t, 0L));
        for (Object[] row : repository.countByLogType()) {
            String type = row[0] == null ? LogTypeClassifier.APPLICATION : String.valueOf(row[0]);
            byType.merge(type, ((Number) row[1]).longValue(), Long::sum);
        }
        Map<String, Long> byApplication = new LinkedHashMap<>();
        for (Object[] row : repository.countByApplication()) {
            byApplication.put(row[0] == null ? "(non rattaché)" : String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byType", byType);
        result.put("byApplication", byApplication);
        return result;
    }

    public AnomalyResult anomalies(String engine) {
        List<LogEntry> entries = logStore.getAll();
        if ("ml".equalsIgnoreCase(engine)) {
            Optional<Map<String, Integer>> labels = aiClient.cluster(analysisService.distinctNormalizedMessages(entries));
            if (labels.isPresent()) {
                return new AnomalyResult("tfidf-dbscan", analysisService.detectAnomaliesWithClusters(entries, labels.get()));
            }
        }
        return new AnomalyResult("jaccard", analysisService.detectAnomalies(entries));
    }

    /** Pics de fréquence : z-score robuste par service + IsolationForest global (si IA disponible). */
    public ApiDtos.SpikeReport spikes() {
        List<LogEntry> entries = logStore.getAll();
        List<ApiDtos.FrequencySpike> spikes = new ArrayList<>(detector.detectSpikes(entries));
        int bucket = detector.getBucketMinutes();
        String method = "robust-zscore";

        // Vecteurs de caractéristiques par fenêtre : [erreurs, warnings, total, messages d'erreur distincts, services en erreur]
        TreeMap<LocalDateTime, List<LogEntry>> byBucket = new TreeMap<>();
        entries.stream().filter(e -> e.getTimestamp() != null)
                .forEach(e -> byBucket.computeIfAbsent(detector.bucketOf(e.getTimestamp(), bucket), k -> new ArrayList<>()).add(e));
        List<String> labels = new ArrayList<>();
        List<double[]> features = new ArrayList<>();
        Map<String, LocalDateTime> labelToBucket = new HashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        byBucket.forEach((start, logs) -> {
            long err = logs.stream().filter(l -> l.getLevel().isErrorLike()).count();
            long warn = logs.stream().filter(l -> l.getLevel() == LogEntry.LogLevel.WARN).count();
            long distinct = logs.stream().filter(l -> l.getLevel().isErrorLike())
                    .map(l -> LogAnalysisService.normalize(l.getMessage())).distinct().count();
            long services = logs.stream().filter(l -> l.getLevel().isErrorLike()).map(LogEntry::getSource).distinct().count();
            String label = start.format(fmt);
            labels.add(label);
            labelToBucket.put(label, start);
            features.add(new double[]{err, warn, logs.size(), distinct, services});
        });
        Optional<List<AiClient.Outlier>> outliers = aiClient.outliers(labels, features);
        if (outliers.isPresent()) {
            method = "robust-zscore+isolation-forest";
            for (AiClient.Outlier o : outliers.get()) {
                LocalDateTime start = labelToBucket.get(o.bucket());
                if (start == null) continue;
                long errCount = byBucket.get(start).stream().filter(l -> l.getLevel().isErrorLike()).count();
                if (errCount == 0) continue; // un creux d'activité n'est pas un incident
                spikes.add(new ApiDtos.FrequencySpike("(tous services)", start, errCount, 0,
                        Math.round(o.score() * 100.0) / 100.0, "isolation-forest"));
            }
        }
        spikes.sort(Comparator.comparing(ApiDtos.FrequencySpike::bucketStart).reversed());
        return new ApiDtos.SpikeReport(method, bucket, spikes, detector.timeline(entries, bucket));
    }

    /** Taille de fenêtre adaptée à l'étendue temporelle pour garder ~60-150 barres. */
    private int adaptiveBucket(List<LogEntry> entries) {
        if (entries.size() < 2) return 5;
        LocalDateTime first = entries.get(0).getTimestamp();
        LocalDateTime last = entries.get(entries.size() - 1).getTimestamp();
        if (first == null || last == null) return 5;
        long minutes = Math.max(1, java.time.Duration.between(first, last).toMinutes());
        int[] candidates = {1, 5, 15, 30, 60, 180, 360, 720, 1440};
        for (int c : candidates) {
            if (minutes / c <= 150) return c;
        }
        return 1440;
    }
}
