/**
 * Role : coordonner les analyses de securite du projet.
 * Contenu : definition de SecurityScanService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.Vulnerability;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lance les scans de sécurité en tâche de fond et consolide leurs résultats. */
@Service
public class SecurityScanService {

    private final DependencyScanService dependencyScanService;
    private final TrivyScanService trivyScanService;
    private final SonarIssueFetcher sonarIssueFetcher;
    private final VulnerabilityService vulnerabilityService;
    private final JobService jobService;
    private final AuditService auditService;

    public SecurityScanService(DependencyScanService dependencyScanService, TrivyScanService trivyScanService,
                               SonarIssueFetcher sonarIssueFetcher, VulnerabilityService vulnerabilityService,
                               JobService jobService, AuditService auditService) {
        this.dependencyScanService = dependencyScanService;
        this.trivyScanService = trivyScanService;
        this.sonarIssueFetcher = sonarIssueFetcher;
        this.vulnerabilityService = vulnerabilityService;
        this.jobService = jobService;
        this.auditService = auditService;
    }

    public JobService.Job scanDependencies() {
        auditService.record("SCAN_DEPENDENCIES", "OWASP Dependency-Check", null);
        return jobService.submit("scan-dependencies", "Scan des dépendances (OWASP)", () -> {
            DependencyScanService.ScanResult result = dependencyScanService.runDependencyCheck();
            if (!result.success()) {
                return new JobService.JobOutcome(false, ProcessRunner.tail(result.output(), 1200),
                        Map.of("exitCode", result.exitCode()));
            }
            ApiDtos.ScanDiff diff = vulnerabilityService.ingestDependencyReportFromDisk();
            return new JobService.JobOutcome(true, describe(diff), Map.of("diff", diff));
        });
    }

    public JobService.Job scanImage(String image) {
        String target = image == null || image.isBlank() ? trivyScanService.getDefaultImage() : image;
        auditService.record("SCAN_IMAGE", target, null);
        return jobService.submit("scan-image", "Scan d'image Trivy (" + target + ")", () -> {
            TrivyScanService.TrivyScanResult result = trivyScanService.scanImage(target);
            if (!result.success()) {
                return new JobService.JobOutcome(false, result.errorMessage(), Map.of());
            }
            ApiDtos.ScanDiff diff = vulnerabilityService.ingest("trivy", TrivyReportMapper.parse(result.jsonOutput()));
            return new JobService.JobOutcome(true, describe(diff), Map.of("diff", diff));
        });
    }

    public JobService.Job syncSonar() {
        return jobService.submit("sync-sonar", "Synchronisation SonarQube", () -> {
            Optional<ApiDtos.ScanDiff> diff = syncSonarNow();
            return diff.map(d -> new JobService.JobOutcome(true, describe(d), Map.<String, Object>of("diff", d)))
                    .orElseGet(() -> new JobService.JobOutcome(false,
                            "SonarQube désactivé ou injoignable (vérifiez SONAR_HOST_URL / SONAR_TOKEN).", Map.of()));
        });
    }

    public Optional<ApiDtos.ScanDiff> syncSonarNow() {
        Optional<List<Vulnerability>> issues = sonarIssueFetcher.fetchIssuesIfAvailable();
        return issues.map(list -> vulnerabilityService.ingest("sonarqube", list));
    }

    public static String describe(ApiDtos.ScanDiff diff) {
        return String.format("%d détectée(s) : %d nouvelle(s), %d corrigée(s) automatiquement, %d rouverte(s)",
                diff.detected(), diff.newCount(), diff.resolvedCount(), diff.reopenedCount());
    }
}
