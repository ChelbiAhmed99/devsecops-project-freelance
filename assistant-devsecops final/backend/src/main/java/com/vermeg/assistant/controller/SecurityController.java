package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.service.JobService;
import com.vermeg.assistant.service.SecurityScanService;
import com.vermeg.assistant.service.VulnerabilityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Brique "détection des vulnérabilités" (dépendances, code, images).
 * <ul>
 *   <li>GET   /api/security/vulnerabilities          -> liste filtrable, triée par priorité</li>
 *   <li>GET   /api/security/vulnerabilities/{id}     -> détail</li>
 *   <li>PATCH /api/security/vulnerabilities/{id}     -> statut / assigné / note (QM, ADMIN)</li>
 *   <li>GET   /api/security/stats                    -> indicateurs consolidés</li>
 *   <li>POST  /api/security/scan/{dependencies|images|sonar} -> scans asynchrones (QM, ADMIN)</li>
 *   <li>POST  /api/security/import/{dependency-check|trivy}  -> import d'un rapport JSON (QM, ADMIN)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

    private static final String MANAGERS = "hasAnyRole('QUALITY_MANAGER','ADMIN')";

    private final VulnerabilityService vulnerabilityService;
    private final SecurityScanService scanService;

    public SecurityController(VulnerabilityService vulnerabilityService, SecurityScanService scanService) {
        this.vulnerabilityService = vulnerabilityService;
        this.scanService = scanService;
    }

    @GetMapping("/vulnerabilities")
    public List<Vulnerability> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String severity,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(required = false) String q) {
        return vulnerabilityService.list(status, severity, source, q);
    }

    @GetMapping("/vulnerabilities/{recordId}")
    public Vulnerability get(@PathVariable Long recordId) {
        return vulnerabilityService.get(recordId);
    }

    @PatchMapping("/vulnerabilities/{recordId}")
    @PreAuthorize(MANAGERS)
    public Vulnerability update(@PathVariable Long recordId, @RequestBody ApiDtos.VulnerabilityUpdate update) {
        return vulnerabilityService.update(recordId, update);
    }

    /** Compatibilité avec l'API du Sprint 6 (identifiant CVE + composant). */
    @PatchMapping("/vulnerabilities/{id}/status")
    @PreAuthorize(MANAGERS)
    public Vulnerability updateStatusLegacy(@PathVariable String id, @RequestParam String component,
                                            @RequestBody Map<String, String> body) {
        return vulnerabilityService.updateByNaturalKey(id, component, body.get("status"));
    }

    @GetMapping("/stats")
    public ApiDtos.VulnerabilityStats stats() {
        return vulnerabilityService.stats();
    }

    @PostMapping("/scan/dependencies")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<JobService.Job> scanDependencies() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(scanService.scanDependencies());
    }

    @PostMapping("/scan/images")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<JobService.Job> scanImages(@RequestParam(required = false) String image) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(scanService.scanImage(image));
    }

    @PostMapping("/scan/sonar")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<JobService.Job> syncSonar() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(scanService.syncSonar());
    }

    @PostMapping(value = "/import/{type}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(MANAGERS)
    public ApiDtos.ScanDiff importReport(@PathVariable String type, @RequestPart("file") MultipartFile file)
            throws Exception {
        return vulnerabilityService.importReport(type, new String(file.getBytes(), StandardCharsets.UTF_8));
    }
}
