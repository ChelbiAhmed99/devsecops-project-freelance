package com.vermeg.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.model.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Centralisation via Grafana Loki (optionnelle, assistant.logs.loki.enabled=true) :
 * récupère périodiquement les nouvelles lignes (API query_range) et les ingère
 * dans l'assistant. Les lignes non reconnues par le parseur deviennent des
 * entrées dont le niveau est déduit du contenu et la source des labels Loki.
 */
@Service
public class LokiLogPuller {

    private static final Logger log = LoggerFactory.getLogger(LokiLogPuller.class);

    @Value("${assistant.logs.loki.enabled:false}")
    private boolean enabled;
    @Value("${assistant.logs.loki.url:http://localhost:3100}")
    private String lokiUrl;
    @Value("${assistant.logs.loki.query:}")
    private String query;

    private final LogParserService parser;
    private final LogIngestionService ingestionService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Dernier timestamp lu (nanosecondes epoch) : reprise incrémentale. */
    private volatile long lastNanos = Instant.now().minus(Duration.ofMinutes(15)).toEpochMilli() * 1_000_000L;

    public LokiLogPuller(LogParserService parser, LogIngestionService ingestionService) {
        this.parser = parser;
        this.ingestionService = ingestionService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Scheduled(fixedDelayString = "${assistant.logs.loki.poll-interval-ms:30000}", initialDelay = 15000)
    public void poll() {
        if (!enabled) return;
        try {
            String effectiveQuery = query == null || query.isBlank() ? "{job=~\".+\"}" : query;
            String url = lokiUrl + "/loki/api/v1/query_range?limit=2000&direction=forward"
                    + "&query=" + URLEncoder.encode(effectiveQuery, StandardCharsets.UTF_8)
                    + "&start=" + (lastNanos + 1);
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Loki a répondu {}", response.statusCode());
                return;
            }
            List<LogEntry> entries = new ArrayList<>();
            long maxNanos = lastNanos;
            for (JsonNode stream : objectMapper.readTree(response.body()).path("data").path("result")) {
                JsonNode labels = stream.path("stream");
                String source = firstLabel(labels, "service", "service_name", "container", "app", "job");
                for (JsonNode value : stream.path("values")) {
                    long nanos = Long.parseLong(value.get(0).asText());
                    maxNanos = Math.max(maxNanos, nanos);
                    String line = value.get(1).asText();
                    entries.add(parser.parseLine(line).orElseGet(() -> fallback(line, nanos, source)));
                }
            }
            lastNanos = maxNanos;
            if (!entries.isEmpty()) {
                ingestionService.ingestEntries(entries, 0);
                log.debug("Loki : {} lignes ingérées", entries.size());
            }
        } catch (Exception e) {
            log.warn("Collecte Loki impossible : {}", e.getMessage());
        }
    }

    private LogEntry fallback(String line, long nanos, String source) {
        LocalDateTime ts = LocalDateTime.ofInstant(Instant.ofEpochSecond(0, nanos), ZoneId.systemDefault());
        String lower = line.toLowerCase(Locale.ROOT);
        LogEntry.LogLevel level = lower.contains("fatal") ? LogEntry.LogLevel.FATAL
                : lower.contains("error") || lower.contains("exception") ? LogEntry.LogLevel.ERROR
                : lower.contains("warn") ? LogEntry.LogLevel.WARN : LogEntry.LogLevel.INFO;
        return new LogEntry(ts, level, source, line.length() > 3900 ? line.substring(0, 3900) : line);
    }

    private String firstLabel(JsonNode labels, String... names) {
        for (String n : names) {
            if (labels.hasNonNull(n)) return labels.get(n).asText().replaceFirst("^/", "");
        }
        return "loki";
    }

    public Map<String, Object> status() {
        return Map.of("enabled", enabled, "url", lokiUrl, "query", query == null ? "" : query);
    }
}
