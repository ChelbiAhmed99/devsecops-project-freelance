/**
 * Role : detecter des variations anormales dans les series temporelles.
 * Contenu : definition de TimeSeriesAnomalyDetector et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.LogEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Détection d'anomalies sur la FRÉQUENCE des événements (cahier des charges §5.1) :
 * les erreurs de chaque service sont comptées par fenêtre de N minutes, puis une
 * fenêtre est signalée comme "pic" si son volume s'écarte fortement de la normale
 * (score robuste basé sur la médiane et l'écart absolu médian, MAD).
 * La médiane résiste mieux que la moyenne aux pics eux-mêmes.
 */
@Service
public class TimeSeriesAnomalyDetector {

    private static final int MAX_BUCKETS = 5000;

    @Value("${assistant.logs.spike-bucket-minutes:5}")
    private int bucketMinutes = 5;

    @Value("${assistant.logs.spike-z-threshold:2.5}")
    private double zThreshold = 2.5;

    @Value("${assistant.logs.spike-min-count:3}")
    private int minCount = 3;

    public int getBucketMinutes() {
        return bucketMinutes;
    }

    public void configure(int bucketMinutes, double zThreshold, int minCount) {
        this.bucketMinutes = bucketMinutes;
        this.zThreshold = zThreshold;
        this.minCount = minCount;
    }

    public LocalDateTime bucketOf(LocalDateTime ts, int minutes) {
        LocalDateTime truncated = ts.truncatedTo(ChronoUnit.DAYS);
        long offset = Duration.between(truncated, ts).toMinutes();
        return truncated.plusMinutes((offset / minutes) * minutes);
    }

    /** Histogramme global (tous services) : total / erreurs / avertissements par fenêtre. */
    public List<ApiDtos.TimeBucket> timeline(List<LogEntry> entries, int minutes) {
        List<LogEntry> dated = entries.stream().filter(e -> e.getTimestamp() != null).toList();
        if (dated.isEmpty()) return List.of();
        TreeMap<LocalDateTime, long[]> buckets = new TreeMap<>();
        for (LogEntry e : dated) {
            long[] c = buckets.computeIfAbsent(bucketOf(e.getTimestamp(), minutes), k -> new long[3]);
            c[0]++;
            if (e.getLevel() != null && e.getLevel().isErrorLike()) c[1]++;
            if (e.getLevel() == LogEntry.LogLevel.WARN) c[2]++;
        }
        fillGaps(buckets, minutes);
        return buckets.entrySet().stream()
                .map(en -> new ApiDtos.TimeBucket(en.getKey(), en.getValue()[0], en.getValue()[1], en.getValue()[2]))
                .toList();
    }

    public List<ApiDtos.FrequencySpike> detectSpikes(List<LogEntry> entries) {
        Map<String, List<LogEntry>> bySource = entries.stream()
                .filter(e -> e.getTimestamp() != null && e.getLevel() != null && e.getLevel().isErrorLike())
                .collect(Collectors.groupingBy(e -> Objects.requireNonNullElse(e.getSource(), "unknown")));

        List<ApiDtos.FrequencySpike> spikes = new ArrayList<>();
        bySource.forEach((source, logs) -> {
            TreeMap<LocalDateTime, long[]> buckets = new TreeMap<>();
            logs.forEach(l -> buckets.computeIfAbsent(bucketOf(l.getTimestamp(), bucketMinutes), k -> new long[1])[0]++);
            fillGaps(buckets, bucketMinutes);

            double[] counts = buckets.values().stream().mapToDouble(c -> c[0]).toArray();
            double median = median(counts);
            double[] deviations = new double[counts.length];
            for (int i = 0; i < counts.length; i++) deviations[i] = Math.abs(counts[i] - median);
            // 1.4826 * MAD ≈ écart-type pour une loi normale ; plancher à 1 pour éviter les divisions par 0
            double scale = Math.max(1.0, 1.4826 * median(deviations));

            buckets.forEach((start, c) -> {
                double score = (c[0] - median) / scale;
                if (c[0] >= minCount && score >= zThreshold) {
                    spikes.add(new ApiDtos.FrequencySpike(source, start, c[0],
                            Math.round(median * 100.0) / 100.0, Math.round(score * 100.0) / 100.0, "robust-zscore"));
                }
            });
        });
        spikes.sort(Comparator.comparingDouble(ApiDtos.FrequencySpike::score).reversed());
        return spikes;
    }

    private void fillGaps(TreeMap<LocalDateTime, long[]> buckets, int minutes) {
        if (buckets.isEmpty()) return;
        int width = buckets.firstEntry().getValue().length;
        LocalDateTime cursor = buckets.firstKey();
        LocalDateTime end = buckets.lastKey();
        int guard = 0;
        while (cursor.isBefore(end) && guard++ < MAX_BUCKETS) {
            buckets.putIfAbsent(cursor, new long[width]);
            cursor = cursor.plusMinutes(minutes);
        }
    }

    static double median(double[] values) {
        if (values.length == 0) return 0;
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int mid = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[mid - 1] + copy[mid]) / 2.0 : copy[mid];
    }
}
