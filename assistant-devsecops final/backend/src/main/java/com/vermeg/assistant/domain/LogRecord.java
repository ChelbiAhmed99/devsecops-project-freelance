/**
 * Role : representer et persister les donnees metier de Log dans la base de donnees.
 * Contenu : definition de LogRecord et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.domain;

import com.vermeg.assistant.model.LogEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Ligne de log persistée (table indexée pour la recherche/filtrage). */
@Entity
@Table(name = "log_record", indexes = {
        @Index(name = "idx_log_ts", columnList = "log_timestamp"),
        @Index(name = "idx_log_level", columnList = "log_level"),
        @Index(name = "idx_log_source", columnList = "source")
})
public class LogRecord {

    public static final int MAX_MESSAGE = 4000;
    public static final int MAX_STACK = 8000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "log_timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_level", nullable = false, length = 10)
    private LogEntry.LogLevel level;

    @Column(nullable = false, length = 120)
    private String source;

    @Column(nullable = false, length = MAX_MESSAGE)
    private String message;

    @Column(length = MAX_STACK)
    private String stackTrace;

    private LocalDateTime ingestedAt;

    public LogRecord() {
    }

    public static LogRecord from(LogEntry entry) {
        LogRecord r = new LogRecord();
        r.timestamp = entry.getTimestamp() != null ? entry.getTimestamp() : LocalDateTime.now();
        r.level = entry.getLevel() != null ? entry.getLevel() : LogEntry.LogLevel.UNKNOWN;
        r.source = truncate(entry.getSource() == null || entry.getSource().isBlank()
                ? "unknown" : entry.getSource(), 120);
        r.message = truncate(entry.getMessage() == null ? "" : entry.getMessage(), MAX_MESSAGE);
        r.stackTrace = truncate(entry.getStackTrace(), MAX_STACK);
        r.ingestedAt = LocalDateTime.now();
        return r;
    }

    public LogEntry toEntry() {
        return new LogEntry(id, timestamp, level, source, message, stackTrace);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max - 3) + "...";
    }

    public Long getId() { return id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public LogEntry.LogLevel getLevel() { return level; }
    public String getSource() { return source; }
    public String getMessage() { return message; }
    public String getStackTrace() { return stackTrace; }
    public LocalDateTime getIngestedAt() { return ingestedAt; }
}
