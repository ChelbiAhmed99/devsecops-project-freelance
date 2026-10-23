/**
 * Role : collecter les preuves propres a une application (tests, logs, incidents, securite, historique).
 * Contenu : definition de ValidationEvidenceCollector et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.validation;

import com.vermeg.assistant.domain.ApplicationValidationRecord;
import com.vermeg.assistant.domain.ManagedApplication;
import com.vermeg.assistant.domain.TestRunRecord;
import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.CorrelatedFailure;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.model.TestCaseResult;
import com.vermeg.assistant.model.TestExecutionSummary;
import com.vermeg.assistant.model.ValidationModels;
import com.vermeg.assistant.repository.ApplicationValidationRepository;
import com.vermeg.assistant.service.DiagnosticService;
import com.vermeg.assistant.service.LogAnalysisService;
import com.vermeg.assistant.service.LogTypeClassifier;
import com.vermeg.assistant.service.TestOrchestrationService;
import com.vermeg.assistant.service.TestRunHistoryService;
import com.vermeg.assistant.service.TimeSeriesAnomalyDetector;
import com.vermeg.assistant.service.VulnerabilityService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assemble les preuves d'UNE application. Toutes les données sont filtrées sur l'application :
 * ses exécutions de tests, ses logs (rattachés ou issus de ses sources), ses vulnérabilités et
 * l'historique de ses validations (référence pour la détection de régression).
 */
@Component
public class ValidationEvidenceCollector {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");

    private final TestRunHistoryService historyService;
    private final TestOrchestrationService testService;
    private final DiagnosticService diagnosticService;
    private final LogAnalysisService analysisService;
    private final TimeSeriesAnomalyDetector spikeDetector;
    private final VulnerabilityService vulnerabilityService;
    private final ApplicationValidationRepository validationRepository;

    public ValidationEvidenceCollector(TestRunHistoryService historyService, TestOrchestrationService testService,
                                       DiagnosticService diagnosticService, LogAnalysisService analysisService,
                                       TimeSeriesAnomalyDetector spikeDetector, VulnerabilityService vulnerabilityService,
                                       ApplicationValidationRepository validationRepository) {
        this.historyService = historyService;
        this.testService = testService;
        this.diagnosticService = diagnosticService;
        this.analysisService = analysisService;
        this.spikeDetector = spikeDetector;
        this.vulnerabilityService = vulnerabilityService;
        this.validationRepository = validationRepository;
    }

    public ValidationEvidence collect(ManagedApplication app) {
        ValidationEvidence ev = new ValidationEvidence();
        ev.applicationCode = app.getCode();
        ev.applicationName = app.getName();
        ev.criticality = app.getCriticality();
        ev.now = LocalDateTime.now();

        List<LogEntry> logs = diagnosticService.logsOf(app);
        collectTests(app, ev, logs);
        collectLogs(app, ev, logs);
        collectSecurity(app, ev);
        collectHistory(app, ev);
        return ev;
    }

    private void collectTests(ManagedApplication app, ValidationEvidence ev, List<LogEntry> logs) {
        List<TestRunRecord> runs = historyService.recentRecords(app.getCode(), 2);
        if (runs.isEmpty()) return;
        TestRunRecord latest = runs.get(0);
        TestExecutionSummary s = historyService.summaryOf(latest);
        ev.testsAvailable = s.getTotalTests() > 0;
        ev.testRunId = latest.getId();
        ev.testRunAt = latest.getExecutedAt();
        ev.testTrigger = latest.getTrigger();
        ev.totalTests = s.getTotalTests();
        ev.failedTests = s.getTotalFailures() + s.getTotalErrors();
        ev.skippedTests = s.getTotalSkipped();
        ev.successRate = s.getSuccessRatePercent();
        ev.lineCoverage = s.getLineCoverage();
        ev.branchCoverage = s.getBranchCoverage();
        ev.testDurationSeconds = s.getDurationSeconds();

        // Comparaison avec l'exécution précédente : nouveaux échecs et tests corrigés
        Map<String, TestCaseResult.Status> previous = new HashMap<>();
        if (runs.size() > 1) {
            ev.previousRunAvailable = true;
            for (SuiteResult suite : historyService.readSuites(runs.get(1).getSuitesJson())) {
                if (suite.getTestCases() == null) continue;
                suite.getTestCases().forEach(tc -> previous.put(suite.getSuiteName() + "#" + tc.getName(), tc.getStatus()));
            }
        }
        Map<String, List<String>> related = new HashMap<>();
        for (CorrelatedFailure cf : testService.correlatedFailures(app.getCode(), logs)) {
            related.put(cf.getSuiteName() + "#" + cf.getTestCaseName(),
                    cf.getRelatedLogs() == null ? List.of() : cf.getRelatedLogs().stream().map(ValidationEvidenceCollector::line).toList());
        }
        for (SuiteResult suite : s.getSuites() == null ? List.<SuiteResult>of() : s.getSuites()) {
            if (suite.getTestCases() == null) continue;
            for (TestCaseResult tc : suite.getTestCases()) {
                String key = suite.getSuiteName() + "#" + tc.getName();
                TestCaseResult.Status before = previous.get(key);
                if (tc.getStatus().isFailure()) {
                    boolean isNew = before == TestCaseResult.Status.PASSED;
                    if (isNew) ev.newFailures.add("test:" + key);
                    if (ev.failures.size() < 25) {
                        ev.failures.add(new ValidationModels.TestFailureView("test:" + key, suite.getSuiteName(), tc.getName(),
                                tc.getStatus().name(), truncate(tc.getFailureMessage(), 600), truncate(tc.getStackTrace(), 1500),
                                related.getOrDefault(key, List.of()), isNew));
                    }
                } else if (tc.getStatus() == TestCaseResult.Status.PASSED && before != null && before.isFailure()) {
                    ev.fixedTests.add("test:" + key);
                }
            }
        }
        ev.flaky = historyService.flakyTests(app.getCode());
        ev.testTrend = historyService.history(app.getCode(), 12);
    }

    private void collectLogs(ManagedApplication app, ValidationEvidence ev, List<LogEntry> logs) {
        ev.logsAvailable = !logs.isEmpty();
        ev.logCount = logs.size();
        if (logs.isEmpty()) return;
        Map<String, Long> byLayer = new LinkedHashMap<>();
        Map<String, Long> errorsByLayer = new LinkedHashMap<>();
        LogTypeClassifier.TYPES.forEach(t -> {
            byLayer.put(t, 0L);
            errorsByLayer.put(t, 0L);
        });
        List<LogEntry> errors = new ArrayList<>();
        for (LogEntry e : logs) {
            String layer = LogTypeClassifier.classify(e);
            byLayer.merge(layer, 1L, Long::sum);
            if (e.getLevel() != null && e.getLevel().isErrorLike()) {
                errorsByLayer.merge(layer, 1L, Long::sum);
                errors.add(e);
                if (e.getLevel() == LogEntry.LogLevel.FATAL && LogTypeClassifier.SERVER.equals(layer)) ev.fatalServerCount++;
            } else if (e.getLevel() == LogEntry.LogLevel.WARN) {
                ev.warnCount++;
            }
        }
        ev.errorCount = errors.size();
        ev.errorRatePercent = Math.round(10000.0 * errors.size() / logs.size()) / 100.0;
        ev.logsByLayer = byLayer;
        ev.errorsByLayer = errorsByLayer;

        List<AnomalyGroup> anomalies = analysisService.detectAnomalies(logs);
        ev.criticalAnomalies = (int) anomalies.stream().filter(AnomalyGroup::isCritical).count();
        for (AnomalyGroup a : anomalies.stream().limit(10).toList()) {
            ev.anomalies.add(new ValidationModels.AnomalyView(a.getKey(), a.getNormalizedMessage(), a.getLevel().name(),
                    a.getLogType(), a.getCategory(), a.getErrorCode(), a.getOccurrences(), a.isCritical(),
                    new ArrayList<>(a.getAffectedSources()), a.getLastSeen(),
                    a.getExamples() == null || a.getExamples().isEmpty() ? null : truncate(a.getExamples().get(0).getMessage(), 300)));
        }
        ev.spikes = spikeDetector.detectSpikes(logs).size();
        ev.logExcerpts = errors.stream().skip(Math.max(0, errors.size() - 15)).map(ValidationEvidenceCollector::line).toList();
        ev.incidents = diagnosticService.incidents(app, logs).stream().limit(5).toList();
    }

    private void collectSecurity(ManagedApplication app, ValidationEvidence ev) {
        ApiDtos.VulnerabilityStats stats = vulnerabilityService.stats(app.getCode());
        ev.securityScanned = stats.total() > 0;
        ev.openVulns = stats.open();
        ev.openBySeverity = stats.openBySeverity();
        ev.topVulnerabilities = vulnerabilityService.list("OPEN", null, null, null, app.getCode()).stream().limit(10).toList();
    }

    private void collectHistory(ManagedApplication app, ValidationEvidence ev) {
        List<ApplicationValidationRecord> previous = validationRepository
                .findByApplicationCodeAndStateOrderByFinishedAtDesc(app.getCode(), "DONE", PageRequest.of(0, 10));
        ev.previousScores = previous.stream().map(ApplicationValidationRecord::getScore).filter(Objects::nonNull).toList();
        if (!previous.isEmpty()) {
            ApplicationValidationRecord last = previous.get(0);
            ev.previousScore = last.getScore();
            ValidationModels.Verdict v = last.getFinalVerdict() != null ? last.getFinalVerdict() : last.getVerdict();
            ev.previousVerdict = v == null ? null : v.label();
        }
        // Confiance accordée à l'IA : taux d'accord entre son avis et la décision humaine finale.
        List<ApplicationValidationRecord> reviewed = validationRepository
                .findByHumanDecisionIsNotNullAndLlmOpinionIsNotNullOrderByDecidedAtDesc(PageRequest.of(0, 30));
        ev.llmTrustSamples = reviewed.size();
        if (!reviewed.isEmpty()) {
            long agreed = reviewed.stream().filter(r -> AdaptiveValidationEngine.opinionVerdict(r.getLlmOpinion()) == r.getFinalVerdict()).count();
            ev.llmTrust = reviewed.size() < 5 ? 1.0 : Math.max(0.2, (double) agreed / reviewed.size());
        }
    }

    private static String line(LogEntry e) {
        return (e.getTimestamp() == null ? "" : e.getTimestamp().format(TS)) + " " + e.getLevel() + " [" + e.getSource() + "] "
                + truncate(e.getMessage(), 240);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
