/**
 * Role : definir les donnees echangees ou calculees pour le modele LogEntry.
 * Contenu : definition de LogEntry et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Ligne de log normalisée après parsing, quel que soit le format d'origine
 * (texte "classique", format Spring Boot, JSON Logstash, flux Loki...).
 * <p>
 * Exemple de ligne brute : 2026-09-18 10:23:41 ERROR [payment-service] Connection timeout
 */
public class LogEntry {

    private Long id;
    private LocalDateTime timestamp;
    private LogLevel level;
    private String source;
    private String message;
    /** Trace de pile éventuelle (lignes de continuation "at ...", "Caused by: ..."). */
    private String stackTrace;

    public enum LogLevel {
        DEBUG, INFO, WARN, ERROR, FATAL, UNKNOWN;

        /** ERROR et FATAL sont traités comme des erreurs dans toute l'analyse. */
        public boolean isErrorLike() {
            return this == ERROR || this == FATAL;
        }

        /** Niveaux pris en compte par la détection d'anomalies. */
        public boolean isAnomalyCandidate() {
            return this == WARN || isErrorLike();
        }
    }

    public LogEntry() {
    }

    public LogEntry(LocalDateTime timestamp, LogLevel level, String source, String message) {
        this.timestamp = timestamp;
        this.level = level;
        this.source = source;
        this.message = message;
    }

    public LogEntry(Long id, LocalDateTime timestamp, LogLevel level, String source,
                    String message, String stackTrace) {
        this(timestamp, level, source, message);
        this.id = id;
        this.stackTrace = stackTrace;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public LogLevel getLevel() { return level; }
    public void setLevel(LogLevel level) { this.level = level; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogEntry other)) return false;
        return Objects.equals(timestamp, other.timestamp) && level == other.level
                && Objects.equals(source, other.source) && Objects.equals(message, other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, level, source, message);
    }

    @Override
    public String toString() {
        return timestamp + " " + level + " [" + source + "] " + message;
    }
}
