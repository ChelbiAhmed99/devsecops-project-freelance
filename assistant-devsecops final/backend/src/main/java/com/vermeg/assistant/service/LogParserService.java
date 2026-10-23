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
import java.time.LocalDate;
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
 *   <li>JBoss / WildFly (server.log) : {@code 2026-09-18 10:23:41,123 ERROR [org.jboss.as.controller] (thread) WFLYCTL0013: ...}</li>
 *   <li>JBoss / WildFly (console, heure seule) : {@code 10:23:41,123 ERROR [io.undertow.request] (default task-1) UT005023: ...}</li>
 *   <li>Sortie de pipeline CI/CD (Maven, Jenkins, GitLab) : {@code [ERROR] Failed to execute goal ...}, {@code Finished: FAILURE}</li>
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

    /** server.log JBoss/WildFly : date heure,ms NIVEAU [catégorie] (thread) message. */
    private static final Pattern WILDFLY = Pattern.compile(
            "^" + TS + "\\s+" + LEVEL + "\\s+\\[([^\\]]+)]\\s+\\(([^)]*)\\)\\s+(.*)$");

    /** Console JBoss/WildFly : heure,ms NIVEAU [catégorie] (thread) message (date = jour de référence). */
    private static final Pattern WILDFLY_CONSOLE = Pattern.compile(
            "^(\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,3})?)\\s+" + LEVEL + "\\s+\\[([^\\]]+)]\\s+\\(([^)]*)\\)\\s+(.*)$");

    /** Préfixe d'horodatage ajouté par Jenkins (plugin Timestamper) ou GitLab : [2026-09-18T10:23:41.123Z] ou [10:23:41]. */
    private static final Pattern CI_TIMESTAMP = Pattern.compile(
            "^\\[?(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z?|\\d{2}:\\d{2}:\\d{2})]?\\s+(.*)$");

    /** Ligne Maven : [INFO] / [WARNING] / [ERROR] message. */
    private static final Pattern MAVEN_LINE = Pattern.compile("^\\[(INFO|WARNING|WARN|ERROR|DEBUG)]\\s?(.*)$");

    /** Marqueurs de fin ou d'échec de pipeline reconnus même sans préfixe Maven. */
    private static final Pattern CI_MARKER = Pattern.compile(
            "^(Finished: (FAILURE|SUCCESS|UNSTABLE|ABORTED)|ERROR: Job failed.*|Job succeeded|BUILD (FAILURE|SUCCESS)|"
                    + "npm ERR!.*|ERROR: .*|FAILURE: Build failed.*|\\[Pipeline] .*)$");

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
        return parseWithStats(inputStream, null);
    }

    /**
     * @param forcedLogType type de log imposé par l'appelant (APPLICATION, SERVER, CICD) ou null.
     *                      En mode CICD, toute ligne non reconnue devient une entrée du pipeline
     *                      (au lieu d'être ignorée), car une sortie de build n'a pas de format fixe.
     */
    public ParseResult parseWithStats(InputStream inputStream, String forcedLogType) {
        boolean ciMode = LogTypeClassifier.CICD.equals(forcedLogType);
        LocalDateTime ciClock = LocalDateTime.now().withNano(0);
        int ciSequence = 0;
        // Lignes de pipeline sans horodatage : elles seront recalées sur leurs voisines horodatées.
        List<Integer> inferred = new ArrayList<>();
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
                if (parsed.isEmpty() && !(previous != null && CONTINUATION.matcher(line).matches())) {
                    // Sortie de pipeline : horodatage déduit de l'ordre des lignes si absent.
                    LocalDateTime fallback = ciClock.plusNanos(1_000_000L * ciSequence);
                    parsed = parseCiLine(line, ciMode, fallback);
                    if (parsed.isPresent()) {
                        ciSequence++;
                        if (parsed.get().getTimestamp() == fallback) inferred.add(entries.size());
                    }
                }
                if (parsed.isPresent()) {
                    previous = parsed.get();
                    if (forcedLogType != null && LogTypeClassifier.TYPES.contains(forcedLogType)) {
                        previous.setLogType(forcedLogType);
                    }
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
        anchorInferredTimestamps(entries, inferred);
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
        Optional<LogEntry> wildfly = parseWildFly(line);
        if (wildfly.isPresent()) {
            return wildfly;
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

    /**
     * Formats JBoss / WildFly. La catégorie du logger donne la couche : un sous-système du
     * serveur (org.jboss.*, io.undertow...) est classé SERVER avec un nom court (wildfly-jca,
     * wildfly-deployment...) ; un logger applicatif déployé dans le serveur reste APPLICATION.
     * Le nom du thread est retiré du message pour que le regroupement d'anomalies reste stable.
     */
    private Optional<LogEntry> parseWildFly(String line) {
        Matcher full = WILDFLY.matcher(line);
        String ts;
        String level;
        String category;
        String message;
        if (full.matches()) {
            ts = full.group(1);
            level = full.group(2);
            category = full.group(3).trim();
            message = full.group(5);
        } else {
            Matcher console = WILDFLY_CONSOLE.matcher(line);
            if (!console.matches()) return Optional.empty();
            ts = LocalDate.now() + " " + console.group(1);
            level = console.group(2);
            category = console.group(3).trim();
            message = console.group(5);
        }
        Optional<String> subsystem = LogTypeClassifier.serverSubsystem(category);
        boolean serverCode = LogTypeClassifier.errorCode(message).filter(c -> !c.startsWith("ORA-")
                && !c.startsWith("HHH") && !c.startsWith("SQLSTATE")).isPresent();
        // Une ligne "classique" [service] (texte) ne doit pas être prise pour du WildFly :
        // on exige une catégorie de logger Java (avec des points) ou un code de message serveur.
        if (!category.contains(".") && !serverCode && subsystem.isEmpty()) return Optional.empty();
        String source = subsystem.orElseGet(() -> serverCode && !category.contains(".") ? "wildfly" : shortLogger(category));
        Optional<LogEntry> entry = build(ts, level, source, message);
        entry.ifPresent(e -> e.setLogType(subsystem.isPresent() || serverCode
                ? LogTypeClassifier.SERVER : LogTypeClassifier.APPLICATION));
        return entry;
    }

    /**
     * Une sortie Jenkins mélange des lignes horodatées et d'autres non (« [Pipeline] », « Finished: FAILURE »).
     * Chaque ligne non horodatée reprend l'horodatage de la ligne horodatée précédente (ou, à défaut,
     * suivante) + 1 ms par position : l'ordre est conservé et un même build reste un seul incident.
     */
    private static void anchorInferredTimestamps(List<LogEntry> entries, List<Integer> inferred) {
        if (inferred.isEmpty() || inferred.size() == entries.size()) return;
        java.util.Set<Integer> inferredSet = new java.util.HashSet<>(inferred);
        for (int idx : inferred) {
            LocalDateTime anchor = null;
            int distance = 0;
            for (int j = idx - 1; j >= 0 && anchor == null; j--) {
                if (!inferredSet.contains(j)) {
                    anchor = entries.get(j).getTimestamp();
                    distance = idx - j;
                }
            }
            if (anchor == null) {
                for (int j = idx + 1; j < entries.size() && anchor == null; j++) {
                    if (!inferredSet.contains(j)) {
                        anchor = entries.get(j).getTimestamp();
                        distance = idx - j; // négatif : avant la première ligne horodatée
                    }
                }
            }
            if (anchor != null) entries.get(idx).setTimestamp(anchor.plusNanos(1_000_000L * distance));
        }
    }

    /** Ligne de sortie de pipeline (Maven, Jenkins, GitLab), ou vide si non reconnue. */
    private Optional<LogEntry> parseCiLine(String rawLine, boolean ciMode, LocalDateTime fallbackTimestamp) {
        String line = rawLine.strip();
        LocalDateTime timestamp = fallbackTimestamp;
        Matcher ts = CI_TIMESTAMP.matcher(line);
        if (ts.matches()) {
            try {
                String raw = ts.group(1);
                timestamp = raw.contains("T") ? parseTimestamp(raw)
                        : LocalDateTime.of(fallbackTimestamp.toLocalDate(), java.time.LocalTime.parse(raw));
                line = ts.group(2).strip();
            } catch (DateTimeParseException e) {
                // horodatage illisible : on garde l'ordre des lignes
            }
        }
        LogEntry.LogLevel level;
        String message;
        Matcher maven = MAVEN_LINE.matcher(line);
        if (maven.matches()) {
            level = mapLevel(maven.group(1));
            message = maven.group(2).strip();
            if (message.isEmpty() || message.chars().allMatch(c -> c == '-')) return Optional.empty();
        } else if (CI_MARKER.matcher(line).matches()) {
            level = line.contains("FAILURE") || line.startsWith("ERROR") || line.startsWith("npm ERR")
                    || line.contains("failed") ? LogEntry.LogLevel.ERROR
                    : line.contains("UNSTABLE") ? LogEntry.LogLevel.WARN : LogEntry.LogLevel.INFO;
            message = line;
        } else if (ciMode) {
            String lower = line.toLowerCase(Locale.ROOT);
            level = lower.contains("error") || lower.contains("exception") || lower.contains("failed")
                    ? LogEntry.LogLevel.ERROR : lower.contains("warn") ? LogEntry.LogLevel.WARN : LogEntry.LogLevel.INFO;
            message = line;
        } else {
            return Optional.empty();
        }
        LogEntry entry = new LogEntry(timestamp, level, "ci-pipeline", message);
        entry.setLogType(LogTypeClassifier.CICD);
        return Optional.of(entry);
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
