package com.vermeg.assistant.service;

import com.vermeg.assistant.domain.TestRunRecord;
import com.vermeg.assistant.model.CorrelatedFailure;
import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.model.TestExecutionSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestration de la brique tests : exécution asynchrone (job), import de
 * rapports venant du pipeline CI, résultats consolidés et corrélation aux logs.
 */
@Service
public class TestOrchestrationService {

    private final TestExecutionService executionService;
    private final SurefireReportParser surefireParser;
    private final JacocoReportParser jacocoParser;
    private final TestRunHistoryService history;
    private final TestLogCorrelationService correlationService;
    private final LogStore logStore;
    private final JobService jobService;
    private final AuditService auditService;

    @Value("${assistant.tests.reports-path:target/surefire-reports}")
    private String reportsPath;

    @Value("${assistant.tests.coverage-report-path:target/site/jacoco/jacoco.xml}")
    private String coveragePath;

    public TestOrchestrationService(TestExecutionService executionService, SurefireReportParser surefireParser,
                                    JacocoReportParser jacocoParser, TestRunHistoryService history,
                                    TestLogCorrelationService correlationService, LogStore logStore,
                                    JobService jobService, AuditService auditService) {
        this.executionService = executionService;
        this.surefireParser = surefireParser;
        this.jacocoParser = jacocoParser;
        this.history = history;
        this.correlationService = correlationService;
        this.logStore = logStore;
        this.jobService = jobService;
        this.auditService = auditService;
    }

    public JobService.Job runAsync() {
        auditService.record("TESTS_RUN", "mvn test", null);
        return jobService.submit("tests", "Exécution des tests", () -> {
            // Marge de 2 s : certains systèmes de fichiers arrondissent la date de modification.
            long startedAt = System.currentTimeMillis() - 2000;
            TestExecutionService.ExecutionResult result = executionService.runTests();
            // Seuls les rapports écrits par CETTE exécution comptent (pas ceux d'un ancien run).
            List<SuiteResult> suites = surefireParser.parseReportsDirectory(reportsPath, startedAt);
            if (suites.isEmpty()) {
                String reason = result.exitCode() == -1
                        ? result.output()
                        : "Maven s'est terminé (code " + result.exitCode() + ") sans produire de rapport de tests : "
                        + "erreur de compilation ou de configuration probable.\n"
                        + ProcessRunner.tail(result.output(), 1500);
                return new JobService.JobOutcome(false, reason, Map.of("exitCode", result.exitCode()));
            }
            TestExecutionService.TestRunWindow window = executionService.getLastRunWindow().orElse(null);
            TestExecutionSummary summary = history.record(suites, jacocoParser.parseFile(coveragePath).orElse(null),
                    new TestRunHistoryService.RunMetadata("UI", null, null, null,
                            window == null ? null : window.start(), window == null ? null : window.end(),
                            result.exitCode(), ProcessRunner.tail(result.output(), 3500)));
            int failed = summary.getTotalFailures() + summary.getTotalErrors();
            return new JobService.JobOutcome(true,
                    summary.getTotalTests() + " tests, " + failed + " en échec, réussite " + summary.getSuccessRatePercent() + "%",
                    Map.of("runId", summary.getRunId(), "successRate", summary.getSuccessRatePercent()));
        });
    }

    /** Import de rapports JUnit XML (+ JaCoCo optionnel) envoyés par le pipeline ou l'interface. */
    public TestExecutionSummary importReports(List<MultipartFile> reports, MultipartFile coverage,
                                              TestRunHistoryService.RunMetadata meta) throws Exception {
        List<SuiteResult> suites = new ArrayList<>();
        for (MultipartFile file : reports) {
            try (InputStream in = file.getInputStream()) {
                suites.addAll(surefireParser.parse(in));
            }
        }
        if (suites.isEmpty()) {
            throw new IllegalArgumentException("Aucune suite de tests trouvée dans les fichiers fournis (format JUnit XML attendu)");
        }
        JacocoReportParser.Coverage cov = null;
        if (coverage != null && !coverage.isEmpty()) {
            try (InputStream in = coverage.getInputStream()) {
                cov = jacocoParser.parse(in).orElse(null);
            }
        }
        TestExecutionSummary summary = history.record(suites, cov, meta);
        auditService.record("TESTS_IMPORT", meta.trigger(), suites.size() + " suites importées");
        return summary;
    }

    /** Dernière exécution connue ; à défaut, lecture directe des rapports présents sur disque. */
    public TestExecutionSummary currentResults() {
        Optional<TestExecutionSummary> latest = history.latest();
        if (latest.isPresent()) {
            return latest.get();
        }
        TestExecutionSummary fromDisk = TestExecutionSummary.fromSuites(surefireParser.parseReportsDirectory(reportsPath));
        jacocoParser.parseFile(coveragePath).ifPresent(c -> {
            fromDisk.setLineCoverage(c.linePercent());
            fromDisk.setBranchCoverage(c.branchPercent());
        });
        fromDisk.setTrigger("DISK");
        return fromDisk;
    }

    public List<CorrelatedFailure> correlatedFailures() {
        return correlatedFailures(null);
    }

    /** Corrélation pour une exécution précise de l'historique (ou la dernière si {@code runId} est nul). */
    public List<CorrelatedFailure> correlatedFailures(Long runId) {
        Optional<TestRunRecord> latest = runId == null ? history.latestRecord() : history.recordById(runId);
        List<SuiteResult> suites;
        Optional<TestExecutionService.TestRunWindow> window;
        if (latest.isPresent()) {
            suites = history.readSuites(latest.get().getSuitesJson());
            window = latest.get().getWindowStart() != null && latest.get().getWindowEnd() != null
                    ? Optional.of(new TestExecutionService.TestRunWindow(latest.get().getWindowStart(), latest.get().getWindowEnd()))
                    : Optional.empty();
        } else {
            suites = surefireParser.parseReportsDirectory(reportsPath);
            window = executionService.getLastRunWindow();
        }
        return correlationService.correlate(suites, logStore.getAll(), window);
    }
}
