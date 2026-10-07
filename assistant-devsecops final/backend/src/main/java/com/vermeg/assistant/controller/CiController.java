package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.TestExecutionSummary;
import com.vermeg.assistant.service.AuditService;
import com.vermeg.assistant.service.LogIngestionService;
import com.vermeg.assistant.service.QualityGateService;
import com.vermeg.assistant.service.TestOrchestrationService;
import com.vermeg.assistant.service.TestRunHistoryService;
import com.vermeg.assistant.service.VulnerabilityService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Intégration CI/CD : remontée automatique des résultats du pipeline à chaque
 * exécution (authentification par en-tête X-API-Key, rôle CI).
 * <ul>
 *   <li>POST /api/ci/logs                         -> logs du pipeline / de l'application (texte)</li>
 *   <li>POST /api/ci/tests                        -> rapports JUnit XML (+ jacoco.xml)</li>
 *   <li>POST /api/ci/security/{dependency-check|trivy} -> rapport JSON brut</li>
 *   <li>GET  /api/ci/quality-gate                 -> PASSED / FAILED (HTTP 200 / 412)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ci")
public class CiController {

    private final LogIngestionService logIngestionService;
    private final TestOrchestrationService testService;
    private final VulnerabilityService vulnerabilityService;
    private final QualityGateService qualityGateService;
    private final AuditService auditService;

    public CiController(LogIngestionService logIngestionService, TestOrchestrationService testService,
                        VulnerabilityService vulnerabilityService, QualityGateService qualityGateService,
                        AuditService auditService) {
        this.logIngestionService = logIngestionService;
        this.testService = testService;
        this.vulnerabilityService = vulnerabilityService;
        this.qualityGateService = qualityGateService;
        this.auditService = auditService;
    }

    @PostMapping(value = "/logs", consumes = {MediaType.TEXT_PLAIN_VALUE, "application/x-ndjson", MediaType.APPLICATION_JSON_VALUE})
    public ApiDtos.IngestResult logs(@RequestBody String raw, @RequestParam(required = false) String source) {
        ApiDtos.IngestResult result = logIngestionService.ingestText(raw, source);
        auditService.record("CI_LOGS", source == null ? "pipeline" : source, result.ingested() + " logs");
        return result;
    }

    @PostMapping(value = "/tests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TestExecutionSummary tests(@RequestPart("reports") List<MultipartFile> reports,
                                      @RequestPart(value = "coverage", required = false) MultipartFile coverage,
                                      @RequestParam(required = false) String branch,
                                      @RequestParam(required = false) String commit,
                                      @RequestParam(required = false) String pipeline) throws Exception {
        return testService.importReports(reports, coverage,
                new TestRunHistoryService.RunMetadata("CI", branch, commit, pipeline, null, null, null, null));
    }

    @PostMapping(value = "/security/{type}", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public ApiDtos.ScanDiff security(@PathVariable String type, @RequestBody String json) {
        return vulnerabilityService.importReport(type, json);
    }

    @GetMapping("/quality-gate")
    public ResponseEntity<ApiDtos.QualityGateResult> qualityGate() {
        ApiDtos.QualityGateResult result = qualityGateService.evaluate();
        return ResponseEntity.status("PASSED".equals(result.status()) ? 200 : 412).body(result);
    }
}
