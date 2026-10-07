package com.vermeg.assistant.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Regroupement des occurrences d'une même famille d'erreur (message normalisé,
 * puis fusion par similarité Jaccard ou par clustering TF-IDF/DBSCAN côté IA).
 */
public class AnomalyGroup {

    /** Identifiant stable (hash du message normalisé) : sert de référence pour l'IA. */
    private String key;
    private String normalizedMessage;
    private LogEntry.LogLevel level;
    private int occurrences;
    private List<LogEntry> examples;
    private boolean critical;
    private Set<String> affectedSources;
    private String summary;
    /** Classification automatique : DATABASE, NETWORK, SECURITY, CODE_DEFECT, RESOURCE... */
    private String category;
    private LocalDateTime firstSeen;
    private LocalDateTime lastSeen;

    public AnomalyGroup() {
    }

    public AnomalyGroup(String normalizedMessage, LogEntry.LogLevel level, int occurrences,
                        List<LogEntry> examples, boolean critical, Set<String> affectedSources,
                        String summary) {
        this.normalizedMessage = normalizedMessage;
        this.level = level;
        this.occurrences = occurrences;
        this.examples = examples;
        this.critical = critical;
        this.affectedSources = affectedSources;
        this.summary = summary;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getNormalizedMessage() { return normalizedMessage; }
    public void setNormalizedMessage(String normalizedMessage) { this.normalizedMessage = normalizedMessage; }
    public LogEntry.LogLevel getLevel() { return level; }
    public void setLevel(LogEntry.LogLevel level) { this.level = level; }
    public int getOccurrences() { return occurrences; }
    public void setOccurrences(int occurrences) { this.occurrences = occurrences; }
    public List<LogEntry> getExamples() { return examples; }
    public void setExamples(List<LogEntry> examples) { this.examples = examples; }
    public boolean isCritical() { return critical; }
    public void setCritical(boolean critical) { this.critical = critical; }
    public Set<String> getAffectedSources() { return affectedSources; }
    public void setAffectedSources(Set<String> affectedSources) { this.affectedSources = affectedSources; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public LocalDateTime getFirstSeen() { return firstSeen; }
    public void setFirstSeen(LocalDateTime firstSeen) { this.firstSeen = firstSeen; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }
}
