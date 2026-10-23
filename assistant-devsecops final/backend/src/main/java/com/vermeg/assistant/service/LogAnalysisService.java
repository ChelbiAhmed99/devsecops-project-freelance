/**
 * Role : analyser les journaux pour y reperer des tendances et anomalies.
 * Contenu : definition de LogAnalysisService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.LogEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Détection d'anomalies dans les logs :
 * 1. normalisation des messages (nombres, UUID, adresses, IP, chemins remplacés par des marqueurs) ;
 * 2. regroupement exact des messages normalisés ;
 * 3. fusion des groupes proches : similarité de Jaccard (local) ou étiquettes de
 *    clustering TF-IDF + DBSCAN calculées par le service IA ;
 * 4. classification par type, résumé en langage naturel, tri par gravité/fréquence.
 */
@Service
public class LogAnalysisService {

    @Value("${assistant.logs.anomaly-threshold:3}")
    private int anomalyThreshold = 3;

    @Value("${assistant.logs.similarity-threshold:0.6}")
    private double similarityThreshold = 0.6;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX_PATTERN = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b[0-9a-f]{16,}\\b");
    private static final Pattern IP_PATTERN = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern NUMBERS_PATTERN = Pattern.compile("\\d+");

    private final AnomalySummaryService summaryService;

    public LogAnalysisService(AnomalySummaryService summaryService) {
        this.summaryService = summaryService;
    }

    public void setAnomalyThreshold(int anomalyThreshold) {
        this.anomalyThreshold = anomalyThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public int getAnomalyThreshold() {
        return anomalyThreshold;
    }

    /** Regroupement local (Jaccard). */
    public List<AnomalyGroup> detectAnomalies(List<LogEntry> entries) {
        Map<String, List<LogEntry>> exactGroups = exactGroups(entries);
        return finalizeGroups(mergeBySimilarity(exactGroups));
    }

    /**
     * Regroupement guidé par des étiquettes de cluster externes (TF-IDF + DBSCAN
     * côté service IA). {@code labels} associe chaque message normalisé à un
     * numéro de cluster ; -1 (bruit) ou absence = groupe isolé.
     */
    public List<AnomalyGroup> detectAnomaliesWithClusters(List<LogEntry> entries, Map<String, Integer> labels) {
        Map<String, List<LogEntry>> exactGroups = exactGroups(entries);
        Map<String, List<String>> byCluster = new LinkedHashMap<>();
        for (String key : exactGroups.keySet()) {
            Integer label = labels.get(key);
            String clusterKey = label == null || label < 0 ? "single:" + key : "cluster:" + label;
            byCluster.computeIfAbsent(clusterKey, k -> new ArrayList<>()).add(key);
        }
        Map<String, List<LogEntry>> merged = new LinkedHashMap<>();
        for (List<String> keys : byCluster.values()) {
            // Représentant du cluster : le message le plus fréquent
            String representative = keys.stream()
                    .max(Comparator.comparingInt(k -> exactGroups.get(k).size())).orElseThrow();
            List<LogEntry> all = new ArrayList<>();
            keys.forEach(k -> all.addAll(exactGroups.get(k)));
            merged.put(representative, all);
        }
        return finalizeGroups(merged);
    }

    /** Messages normalisés distincts (entrée du clustering IA). */
    public List<String> distinctNormalizedMessages(List<LogEntry> entries) {
        return new ArrayList<>(exactGroups(entries).keySet());
    }

    private Map<String, List<LogEntry>> exactGroups(List<LogEntry> entries) {
        return entries.stream()
                .filter(e -> e.getLevel() != null && e.getLevel().isAnomalyCandidate())
                .collect(Collectors.groupingBy(e -> normalize(e.getMessage()), LinkedHashMap::new, Collectors.toList()));
    }

    private Map<String, List<LogEntry>> mergeBySimilarity(Map<String, List<LogEntry>> exactGroups) {
        List<String> keys = new ArrayList<>(exactGroups.keySet());
        Map<String, List<LogEntry>> result = new LinkedHashMap<>();
        Set<String> alreadyMerged = new HashSet<>();

        for (String key : keys) {
            if (alreadyMerged.contains(key)) continue;
            List<LogEntry> combined = new ArrayList<>(exactGroups.get(key));
            alreadyMerged.add(key);
            for (String other : keys) {
                if (other.equals(key) || alreadyMerged.contains(other)) continue;
                if (TextSimilarityUtil.jaccardSimilarity(key, other) >= similarityThreshold) {
                    combined.addAll(exactGroups.get(other));
                    alreadyMerged.add(other);
                }
            }
            result.put(key, combined);
        }
        return result;
    }

    private List<AnomalyGroup> finalizeGroups(Map<String, List<LogEntry>> groups) {
        List<AnomalyGroup> result = new ArrayList<>();
        groups.forEach((message, occurrences) -> result.add(buildAnomalyGroup(message, occurrences)));
        result.sort(Comparator.comparing(AnomalyGroup::isCritical).reversed()
                .thenComparing(Comparator.comparingInt(AnomalyGroup::getOccurrences).reversed()));
        return result;
    }

    private AnomalyGroup buildAnomalyGroup(String representativeMessage, List<LogEntry> occurrences) {
        int count = occurrences.size();
        Set<String> sources = occurrences.stream().map(LogEntry::getSource)
                .filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));

        LogEntry.LogLevel level = occurrences.stream().map(LogEntry::getLevel)
                .max(Comparator.comparingInt(LogEntry.LogLevel::ordinal)).orElse(LogEntry.LogLevel.UNKNOWN);

        List<LogEntry> sorted = occurrences.stream()
                .sorted(Comparator.comparing(LogEntry::getTimestamp, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        LocalDateTime first = sorted.get(0).getTimestamp();
        LocalDateTime last = sorted.get(sorted.size() - 1).getTimestamp();

        String stack = occurrences.stream().map(LogEntry::getStackTrace)
                .filter(Objects::nonNull).findFirst().orElse(null);

        // FATAL est critique dès la première occurrence ; sinon seuil de fréquence.
        boolean critical = count >= anomalyThreshold || level == LogEntry.LogLevel.FATAL;

        AnomalyGroup group = new AnomalyGroup(representativeMessage, level, count,
                sorted.subList(Math.max(0, sorted.size() - 3), sorted.size()), critical, sources, null);
        group.setKey(keyOf(representativeMessage));
        group.setCategory(AnomalyCategorizer.categorize(representativeMessage, stack));
        group.setFirstSeen(first);
        group.setLastSeen(last);
        // Couches touchées (applicatif / serveur / CI) et couche dominante.
        Map<String, Long> layers = occurrences.stream()
                .collect(Collectors.groupingBy(LogTypeClassifier::classify, TreeMap::new, Collectors.counting()));
        group.setLayers(new TreeSet<>(layers.keySet()));
        group.setLogType(layers.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(LogTypeClassifier.APPLICATION));
        LogTypeClassifier.errorCode(representativeMessage).ifPresent(group::setErrorCode);
        group.setSummary(summaryService.summarize(group));
        return group;
    }

    /**
     * Remplace les parties variables d'un message par des marqueurs génériques.
     * Les codes d'erreur des serveurs et bases (WFLYCTL0013, IJ000453, ARJUNA012117, ORA-00942...)
     * sont préservés : ils identifient précisément le problème et ne doivent pas être fusionnés.
     */
    public static String normalize(String message) {
        if (message == null) return "";
        List<String> codes = new ArrayList<>();
        Matcher codeMatcher = LogTypeClassifier.errorCodePattern().matcher(message);
        StringBuilder protectedMsg = new StringBuilder();
        while (codeMatcher.find()) {
            codeMatcher.appendReplacement(protectedMsg, Matcher.quoteReplacement("\u00a7" + placeholder(codes.size()) + "\u00a7"));
            codes.add(codeMatcher.group(1));
        }
        codeMatcher.appendTail(protectedMsg);
        String msg = UUID_PATTERN.matcher(protectedMsg.toString()).replaceAll("#uuid#");
        msg = EMAIL_PATTERN.matcher(msg).replaceAll("#email#");
        msg = IP_PATTERN.matcher(msg).replaceAll("#ip#");
        msg = HEX_PATTERN.matcher(msg).replaceAll("#hex#");
        msg = NUMBERS_PATTERN.matcher(msg).replaceAll("#");
        for (int i = 0; i < codes.size(); i++) {
            msg = msg.replace("\u00a7" + placeholder(i) + "\u00a7", codes.get(i));
        }
        return msg.trim();
    }

    /** Marqueur sans chiffre (sinon il serait lui-même remplacé par #) : a, b, ..., z, ba, bb... */
    private static String placeholder(int index) {
        StringBuilder sb = new StringBuilder();
        int i = index;
        do {
            sb.insert(0, (char) ('a' + i % 26));
            i /= 26;
        } while (i > 0);
        return sb.toString();
    }

    public static String keyOf(String normalizedMessage) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedMessage.getBytes(StandardCharsets.UTF_8));
            return "anomaly-" + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "anomaly-" + Integer.toHexString(normalizedMessage.hashCode());
        }
    }

    /** Nombre d'anomalies critiques (utilisé par le tableau de bord et le quality gate). */
    public long countCritical(List<LogEntry> entries) {
        return detectAnomalies(entries).stream().filter(AnomalyGroup::isCritical).count();
    }

    /** Index "message normalisé → groupe" pour retrouver rapidement un groupe par clé. */
    public Map<String, AnomalyGroup> indexByKey(List<AnomalyGroup> groups) {
        Map<String, AnomalyGroup> index = new HashMap<>();
        groups.forEach(g -> index.put(g.getKey(), g));
        return index;
    }
}
