/**
 * Role : conserver et consulter l historique des executions de tests.
 * Contenu : definition de TestRunHistoryService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.domain.TestRunRecord;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.model.TestCaseResult;
import com.vermeg.assistant.model.TestExecutionSummary;
import com.vermeg.assistant.repository.TestRunRepository;
import com.vermeg.assistant.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Historique des exécutions de tests : persistance, tendance (taux de réussite,
 * couverture) et détection des tests instables ("flaky") qui alternent entre
 * succès et échec sans changement évident.
 */
@Service
public class TestRunHistoryService {

    private final TestRunRepository repository;
    private final ObjectMapper objectMapper;
    private final EventService eventService;

    @Value("${assistant.tests.flaky-window:10}")
    private int flakyWindow = 10;

    public TestRunHistoryService(TestRunRepository repository, ObjectMapper objectMapper, EventService eventService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.eventService = eventService;
    }

    @Value("${assistant.applications.default-code:assistant-devsecops}")
    private String defaultApplication = "assistant-devsecops";

    /**
     * Métadonnées d'une exécution. {@code application} : code de l'application testée
     * (null = application par défaut, comportement mono-projet historique).
     */
    public record RunMetadata(String trigger, String branch, String commitSha, String pipelineId,
                              LocalDateTime windowStart, LocalDateTime windowEnd, Integer exitCode,
                              String outputTail, String application) {
        /** Constructeur historique (sans application) conservé pour compatibilité. */
        public RunMetadata(String trigger, String branch, String commitSha, String pipelineId,
                           LocalDateTime windowStart, LocalDateTime windowEnd, Integer exitCode, String outputTail) {
            this(trigger, branch, commitSha, pipelineId, windowStart, windowEnd, exitCode, outputTail, null);
        }

        public static RunMetadata of(String trigger) {
            return new RunMetadata(trigger, null, null, null, null, null, null, null, null);
        }

        public RunMetadata withApplication(String app) {
            return new RunMetadata(trigger, branch, commitSha, pipelineId, windowStart, windowEnd, exitCode, outputTail, app);
        }
    }

    @Transactional
    public TestExecutionSummary record(List<SuiteResult> suites, JacocoReportParser.Coverage coverage, RunMetadata meta) {
        TestExecutionSummary summary = TestExecutionSummary.fromSuites(suites);
        TestRunRecord r = new TestRunRecord();
        r.setExecutedAt(LocalDateTime.now());
        r.setApplicationCode(meta.application() == null || meta.application().isBlank()
                ? defaultApplication : meta.application());
        r.setTrigger(meta.trigger());
        r.setTriggeredBy(CurrentUser.username());
        r.setBranch(meta.branch());
        r.setCommitSha(meta.commitSha());
        r.setPipelineId(meta.pipelineId());
        r.setWindowStart(meta.windowStart());
        r.setWindowEnd(meta.windowEnd());
        r.setExitCode(meta.exitCode());
        r.setOutputTail(meta.outputTail());
        r.setTotalTests(summary.getTotalTests());
        r.setFailures(summary.getTotalFailures());
        r.setErrors(summary.getTotalErrors());
        r.setSkipped(summary.getTotalSkipped());
        r.setSuccessRate(summary.getSuccessRatePercent());
        r.setDurationSeconds(summary.getDurationSeconds());
        if (coverage != null) {
            r.setLineCoverage(coverage.linePercent());
            r.setBranchCoverage(coverage.branchPercent());
        }
        try {
            r.setSuitesJson(objectMapper.writeValueAsString(suites));
        } catch (Exception e) {
            r.setSuitesJson("[]");
        }
        TestRunRecord saved = repository.save(r);

        int failed = summary.getTotalFailures() + summary.getTotalErrors();
        eventService.publish("tests.completed", failed > 0 ? "warning" : "success",
                failed > 0 ? failed + " test(s) en échec" : "Tous les tests sont passés",
                Map.of("runId", saved.getId(), "total", summary.getTotalTests(), "failed", failed,
                        "successRate", summary.getSuccessRatePercent(), "application", saved.getApplicationCode()));
        return toSummary(saved);
    }

    @Transactional(readOnly = true)
    public Optional<TestExecutionSummary> latest() {
        return repository.findFirstByOrderByExecutedAtDesc().map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public Optional<TestExecutionSummary> byId(Long id) {
        return repository.findById(id).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public Optional<TestRunRecord> recordById(Long id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<TestRunRecord> latestRecord() {
        return repository.findFirstByOrderByExecutedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.TestRunPoint> history(int limit) {
        List<ApiDtos.TestRunPoint> points = new ArrayList<>(repository
                .findAllByOrderByExecutedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
                .stream()
                .map(r -> new ApiDtos.TestRunPoint(r.getId(), r.getExecutedAt(), r.getTotalTests(),
                        r.getFailures() + r.getErrors(), r.getSuccessRate(), r.getLineCoverage(),
                        r.getTrigger(), r.getBranch()))
                .toList());
        Collections.reverse(points);
        return points;
    }

    // ------------------------------------------------------ vues par application

    @Transactional(readOnly = true)
    public Optional<TestExecutionSummary> latest(String application) {
        if (application == null || application.isBlank()) return latest();
        return repository.findFirstByApplicationCodeOrderByExecutedAtDesc(application).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public Optional<TestRunRecord> latestRecord(String application) {
        if (application == null || application.isBlank()) return latestRecord();
        return repository.findFirstByApplicationCodeOrderByExecutedAtDesc(application);
    }

    /** Les {@code limit} dernières exécutions d'une application, de la plus récente à la plus ancienne. */
    @Transactional(readOnly = true)
    public List<TestRunRecord> recentRecords(String application, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        return application == null || application.isBlank()
                ? repository.findAllByOrderByExecutedAtDesc(page)
                : repository.findByApplicationCodeOrderByExecutedAtDesc(application, page);
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.TestRunPoint> history(String application, int limit) {
        if (application == null || application.isBlank()) return history(limit);
        List<ApiDtos.TestRunPoint> points = new ArrayList<>(recentRecords(application, limit).stream()
                .map(r -> new ApiDtos.TestRunPoint(r.getId(), r.getExecutedAt(), r.getTotalTests(),
                        r.getFailures() + r.getErrors(), r.getSuccessRate(), r.getLineCoverage(),
                        r.getTrigger(), r.getBranch()))
                .toList());
        Collections.reverse(points);
        return points;
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.FlakyTest> flakyTests(String application) {
        if (application == null || application.isBlank()) return flakyTests();
        List<TestRunRecord> runs = new ArrayList<>(recentRecords(application, flakyWindow));
        Collections.reverse(runs);
        return computeFlaky(runs);
    }

    /** Exécutions postérieures à une date (vérification après correction). */
    @Transactional(readOnly = true)
    public List<TestRunRecord> runsAfter(String application, LocalDateTime after) {
        return application == null || application.isBlank()
                ? repository.findByExecutedAtAfterOrderByExecutedAtAsc(after)
                : repository.findByApplicationCodeAndExecutedAtAfterOrderByExecutedAtAsc(application, after);
    }

    public TestExecutionSummary summaryOf(TestRunRecord record) {
        return toSummary(record);
    }

    /**
     * Un test est "flaky" s'il a à la fois réussi et échoué sur les N dernières
     * exécutions. Score = nombre de changements d'état / (exécutions observées - 1).
     */
    @Transactional(readOnly = true)
    public List<ApiDtos.FlakyTest> flakyTests() {
        List<TestRunRecord> runs = new ArrayList<>(repository.findAllByOrderByExecutedAtDesc(PageRequest.of(0, flakyWindow)));
        Collections.reverse(runs); // ordre chronologique
        return computeFlaky(runs);
    }

    private List<ApiDtos.FlakyTest> computeFlaky(List<TestRunRecord> runs) {
        Map<String, List<TestCaseResult.Status>> history = new LinkedHashMap<>();
        Map<String, String[]> names = new LinkedHashMap<>();
        for (TestRunRecord run : runs) {
            for (SuiteResult suite : readSuites(run.getSuitesJson())) {
                if (suite.getTestCases() == null) continue;
                for (TestCaseResult tc : suite.getTestCases()) {
                    if (tc.getStatus() == TestCaseResult.Status.SKIPPED) continue;
                    String key = suite.getSuiteName() + "#" + tc.getName();
                    history.computeIfAbsent(key, k -> new ArrayList<>()).add(tc.getStatus());
                    names.putIfAbsent(key, new String[]{suite.getSuiteName(), tc.getName()});
                }
            }
        }
        List<ApiDtos.FlakyTest> flaky = new ArrayList<>();
        history.forEach((key, statuses) -> {
            long failures = statuses.stream().filter(TestCaseResult.Status::isFailure).count();
            if (statuses.size() < 2 || failures == 0 || failures == statuses.size()) return;
            int changes = 0;
            for (int i = 1; i < statuses.size(); i++) {
                if (statuses.get(i).isFailure() != statuses.get(i - 1).isFailure()) changes++;
            }
            double pct = Math.round(10000.0 * changes / (statuses.size() - 1)) / 100.0;
            String[] n = names.get(key);
            flaky.add(new ApiDtos.FlakyTest(n[0], n[1], statuses.size(), (int) failures, changes, pct,
                    statuses.get(statuses.size() - 1).name()));
        });
        flaky.sort(Comparator.comparingDouble(ApiDtos.FlakyTest::flakinessPercent).reversed());
        return flaky;
    }

    public List<SuiteResult> readSuites(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<SuiteResult>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private TestExecutionSummary toSummary(TestRunRecord r) {
        List<SuiteResult> suites = readSuites(r.getSuitesJson());
        TestExecutionSummary s = new TestExecutionSummary(r.getTotalTests(), r.getFailures(), r.getErrors(),
                r.getSkipped(), r.getSuccessRate(), suites);
        s.setRunId(r.getId());
        s.setExecutedAt(r.getExecutedAt());
        s.setTrigger(r.getTrigger());
        s.setBranch(r.getBranch());
        s.setCommitSha(r.getCommitSha());
        s.setLineCoverage(r.getLineCoverage());
        s.setBranchCoverage(r.getBranchCoverage());
        s.setDurationSeconds(r.getDurationSeconds());
        s.setApplication(r.getApplicationCode());
        return s;
    }
}
