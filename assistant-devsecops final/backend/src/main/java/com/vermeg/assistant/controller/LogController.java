package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.service.AuditService;
import com.vermeg.assistant.service.LogIngestionService;
import com.vermeg.assistant.service.LogQueryService;
import com.vermeg.assistant.service.LogStore;
import com.vermeg.assistant.service.LokiLogPuller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Brique "analyse des logs".
 * <ul>
 *   <li>GET    /api/logs                 -> logs de la fenêtre d'analyse (compatibilité)</li>
 *   <li>GET    /api/logs/search          -> recherche paginée (niveau, source, texte, période)</li>
 *   <li>GET    /api/logs/stats           -> volumes par niveau/source + histogramme temporel</li>
 *   <li>GET    /api/logs/sources         -> liste des services connus</li>
 *   <li>GET    /api/logs/anomalies       -> groupes d'anomalies (engine=local|ml)</li>
 *   <li>GET    /api/logs/spikes          -> pics de fréquence (z-score robuste + IsolationForest)</li>
 *   <li>POST   /api/logs/ingest          -> ingestion texte brut</li>
 *   <li>POST   /api/logs/upload          -> ingestion d'un fichier</li>
 *   <li>DELETE /api/logs                 -> purge (ADMIN)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogStore logStore;
    private final LogQueryService queryService;
    private final LogIngestionService ingestionService;
    private final AuditService auditService;
    private final LokiLogPuller lokiLogPuller;

    public LogController(LogStore logStore, LogQueryService queryService, LogIngestionService ingestionService,
                         AuditService auditService, LokiLogPuller lokiLogPuller) {
        this.logStore = logStore;
        this.queryService = queryService;
        this.ingestionService = ingestionService;
        this.auditService = auditService;
        this.lokiLogPuller = lokiLogPuller;
    }

    @GetMapping
    public List<LogEntry> getLogs() {
        return logStore.getAll();
    }

    @GetMapping("/search")
    public ApiDtos.LogPage search(@RequestParam(required = false) String level,
                                  @RequestParam(required = false) String source,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return queryService.search(level, source, q, from, to, page, size);
    }

    @GetMapping("/stats")
    public ApiDtos.LogStats stats() {
        return queryService.stats();
    }

    @GetMapping("/sources")
    public List<String> sources() {
        return queryService.sources();
    }

    @GetMapping("/anomalies")
    public ResponseEntity<List<AnomalyGroup>> getAnomalies(@RequestParam(defaultValue = "local") String engine) {
        LogQueryService.AnomalyResult result = queryService.anomalies(engine);
        return ResponseEntity.ok().header("X-Clustering-Engine", result.engine()).body(result.groups());
    }

    @GetMapping("/spikes")
    public ApiDtos.SpikeReport spikes() {
        return queryService.spikes();
    }

    @GetMapping("/collectors")
    public Map<String, Object> collectors() {
        return Map.of("loki", lokiLogPuller.status());
    }

    @PostMapping(value = "/ingest", consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE,
            "application/x-ndjson"})
    public ApiDtos.IngestResult ingest(@RequestBody String rawLogs,
                                       @RequestParam(required = false) String source) {
        ApiDtos.IngestResult result = ingestionService.ingestText(rawLogs, source);
        auditService.record("LOGS_INGEST", source == null ? "api" : source, result.ingested() + " logs");
        return result;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiDtos.IngestResult upload(@RequestPart("file") MultipartFile file,
                                       @RequestParam(required = false) String source) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ApiDtos.IngestResult result = ingestionService.ingestStream(in, source);
            auditService.record("LOGS_UPLOAD", file.getOriginalFilename(), result.ingested() + " logs");
            return result;
        }
    }

    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> clear() {
        logStore.clear();
        auditService.record("LOGS_CLEARED", "log_record", null);
        return Map.of("totalInStore", logStore.size());
    }
}
