/**
 * Role : convertir les lignes de journaux en objets structures.
 * Contenu : definition de LogParserService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.model.LogEntry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parseur de logs multi-formats :
 * <ol>
 *   <li>format "classique" : {@code 2026-09-18 10:23:41[.123] ERROR [source] message}</li>
 *   <li>format Spring Boot 3 : {@code 2026-09-18T10:23:41.123+01:00 ERROR 123 --- [app] [thread] logger : message}</li>
 *   <li>JSON par ligne (Logstash / ECS) : {@code {"@timestamp":..., "level":..., "message":...}}</li>
 * </ol>
 * Les lignes de continuation (traces de pile "at ...", "Caused by: ...") sont
 * rattachées à l'entrée précédente dans le champ {@code stackTrace}.
 */
@Service
public class LogParserService {

    private static final String TS = "(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?)";
    private static final String LEVEL = "(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|SEVERE|FATAL|CRITICAL)";

    private static final Pattern CLASSIC = Pattern.compile(
            "^" + TS + "\\s+" + LEVEL + "\\s+\\[([^\\]]+)]\\s+(.*)$");

    private static final Pattern SPRING_BOOT = Pattern.compile(
            "^" + TS + "\\s+" + LEVEL + "\\s+\\d+\\s+---\\s+(?:\\[([^\\]]*)]\\s+)?\\[\\s*([^\\]]*)]\\s+(\\S+)\\s*:\\s?(.*)$");

    private static final Pattern CONTINUATION = Pattern.compile(
            "^(\\s+.*|at\\s.*|Caused by:.*|\\.\\.\\. \\d+ (more|common frames omitted).*|[a-zA-Z_$][\\w$.]*(Exception|Error)(:.*)?)$");

    private static final DateTimeFormatter LOCAL_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record ParseResult(List<LogEntry> entries, int skippedLines) { }

    public List<LogEntry> parseSampleLogFile() {
        try (InputStream is = new ClassPathResource("sample-logs/app.log").getInputStream()) {
            return parseStream(is);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de lire le fichier de logs d'exemple", e);
        }
    }

    public List<LogEntry> parseStream(InputStream inputStream) {
        return parseWithStats(inputStream).entries();
    }

    public ParseResult parseWithStats(InputStream inputStream) {
        List<LogEntry> entries = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            LogEntry previous = null;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Optional<LogEntry> parsed = parseLine(line);
                if (parsed.isPresent()) {
                    previous = parsed.get();
                    entries.add(previous);
                } else if (previous != null && CONTINUATION.matcher(line).matches()) {
                    appendStackTrace(previous, line);
                } else {
                    skipped++;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Erreur lors du parsing du flux de logs", e);
        }
        return new ParseResult(entries, skipped);
    }

    public Optional<LogEntry> parseLine(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return Optional.empty();
        }
        String line = rawLine.strip();
        if (line.startsWith("{")) {
            return parseJson(line);
        }
        Matcher spring = SPRING_BOOT.matcher(line);
        if (spring.matches()) {
            String app = spring.group(3);
            String logger = spring.group(5);
            String source = app != null && !app.isBlank() ? app.trim() : shortLogger(logger);
            return build(spring.group(1), spring.group(2), source, spring.group(6));
        }
        Matcher classic = CLASSIC.matcher(line);
        if (classic.matches()) {
            return build(classic.group(1), classic.group(2), classic.group(3).trim(), classic.group(4));
        }
        return Optional.empty();
    }

    private Optional<LogEntry> parseJson(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            if (!node.isObject()) return Optional.empty();
            String message = firstText(node, "message", "msg", "log");
            if (message == null) return Optional.empty();
            String ts = firstText(node, "@timestamp", "timestamp", "time", "ts");
            String level = firstText(node, "level", "log.level", "severity", "levelname");
            String source = firstText(node, "service", "service.name", "app", "application", "source",
                    "logger_name", "logger");
            LocalDateTime timestamp = ts != null ? parseTimestamp(ts) : LocalDateTime.now();
            LogEntry entry = new LogEntry(timestamp, mapLevel(level),
                    source == null ? "unknown" : shortLogger(source), message);
            String stack = firstText(node, "stack_trace", "error.stack_trace", "exception", "stacktrace");
            entry.setStackTrace(stack);
            return Optional.of(entry);
        } catch (IOException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v == null && f.contains(".")) {
                // Notation ECS imbriquée : {"log": {"level": "error"}}
                JsonNode cursor = node;
                for (String part : f.split("\\.")) {
                    cursor = cursor == null ? null : cursor.get(part);
                }
                v = cursor;
            }
            if (v != null && !v.isNull() && v.isValueNode()) {
                return v.asText();
            }
        }
        return null;
    }

    private Optional<LogEntry> build(String ts, String level, String source, String message) {
        try {
            return Optional.of(new LogEntry(parseTimestamp(ts), mapLevel(level), source, message.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Accepte "yyyy-MM-dd HH:mm:ss[.SSS]", ISO-8601 local ou avec fuseau, et epoch millisecondes. */
    public static LocalDateTime parseTimestamp(String raw) {
        String value = raw.trim().replace(',', '.');
        if (value.matches("\\d{12,13}")) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(value)), ZoneId.systemDefault());
        }
        if (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            String normalized = value.replace(' ', 'T').replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
            return OffsetDateTime.parse(normalized).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        }
        if (value.contains("T")) {
            return LocalDateTime.parse(value);
        }
        return LocalDateTime.parse(value, LOCAL_FORMAT);
    }

    public static LogEntry.LogLevel mapLevel(String raw) {
        if (raw == null) return LogEntry.LogLevel.UNKNOWN;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE", "DEBUG" -> LogEntry.LogLevel.DEBUG;
            case "INFO", "INFORMATION", "NOTICE" -> LogEntry.LogLevel.INFO;
            case "WARN", "WARNING" -> LogEntry.LogLevel.WARN;
            case "ERROR", "ERR", "SEVERE" -> LogEntry.LogLevel.ERROR;
            case "FATAL", "CRITICAL", "CRIT", "EMERG", "ALERT" -> LogEntry.LogLevel.FATAL;
            default -> LogEntry.LogLevel.UNKNOWN;
        };
    }

    private void appendStackTrace(LogEntry entry, String line) {
        String current = entry.getStackTrace();
        if (current != null && current.length() > 7000) {
            return;
        }
        entry.setStackTrace(current == null ? line.strip() : current + "\n" + line.strip());
    }

    private String shortLogger(String logger) {
        if (logger == null) return "unknown";
        int idx = logger.lastIndexOf('.');
        return idx >= 0 && idx < logger.length() - 1 ? logger.substring(idx + 1) : logger;
    }
}
