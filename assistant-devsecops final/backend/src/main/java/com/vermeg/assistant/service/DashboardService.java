package com.vermeg.assistant.service;

import com.vermeg.assistant.model.AiRecommendation;
import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.model.TestExecutionSummary;
import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.repository.LogRecordRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vue consolidée du tableau de bord : score de santé global, indicateurs clés
 * des quatre briques, état des services orchestrés et derniers traitements.
 */
@Service
public class DashboardService {

    private final LogStore logStore;
    private final LogAnalysisService analysisService;
    private final LogQueryService logQueryService;
    private final LogRecordRepository logRepository;
    private final TestOrchestrationService testService;
    private final TestRunHistoryService historyService;
    private final VulnerabilityService vulnerabilityService;
    private final AiRecommendationStore recommendationStore;
    private final QualityGateService qualityGateService;
    private final JobService jobService;
    private final AiClient aiClient;
    private final SonarIssueFetcher sonarIssueFetcher;

    public DashboardService(LogStore logStore, LogAnalysisService analysisService, LogQueryService logQueryService,
                            LogRecordRepository logRepository, TestOrchestrationService testService,
                            TestRunHistoryService historyService, VulnerabilityService vulnerabilityService,
                            AiRecommendationStore recommendationStore, QualityGateService qualityGateService,
                            JobService jobService, AiClient aiClient, SonarIssueFetcher sonarIssueFetcher) {
        this.logStore = logStore;
        this.analysisService = analysisService;
        this.logQueryService = logQueryService;
        this.logRepository = logRepository;
        this.testService = testService;
        this.historyService = historyService;
        this.vulnerabilityService = vulnerabilityService;
        this.recommendationStore = recommendationStore;
        this.qualityGateService = qualityGateService;
        this.jobService = jobService;
        this.aiClient = aiClient;
        this.sonarIssueFetcher = sonarIssueFetcher;
    }

    public Map<String, Object> overview() {
        List<LogEntry> logs = logStore.getAll();
        List<AnomalyGroup> anomalies = analysisService.detectAnomalies(logs);
        long criticalAnomalies = anomalies.stream().filter(AnomalyGroup::isCritical).count();
        long errors = logs.stream().filter(l -> l.getLevel().isErrorLike()).count();
        long warnings = logs.stream().filter(l -> l.getLevel() == LogEntry.LogLevel.WARN).count();
        int spikes = logQueryService.spikes().spikes().size();

        TestExecutionSummary tests = testService.currentResults();
        ApiDtos.VulnerabilityStats vulnStats = vulnerabilityService.stats();
        long criticalVulns = vulnStats.openBySeverity().getOrDefault("CRITICAL", 0L);
        long highVulns = vulnStats.openBySeverity().getOrDefault("HIGH", 0L);

        // ---- Score de santé (0-100) : pénalités plafonnées par brique ----
        double penalty = 0;
        penalty += Math.min(40, criticalVulns * 15 + highVulns * 4);
        if (tests.getTotalTests() > 0) {
            penalty += Math.min(25, (100 - tests.getSuccessRatePercent()) * 0.8);
        }
        penalty += Math.min(20, criticalAnomalies * 5);
        penalty += Math.min(10, spikes * 2);
        int health = (int) Math.max(0, Math.round(100 - penalty));
        String label = health >= 80 ? "Bon" : health >= 60 ? "À surveiller" : health >= 40 ? "Dégradé" : "Critique";

        Map<String, Object> logsBlock = new LinkedHashMap<>();
        logsBlock.put("total", logStore.size());
        logsBlock.put("errors", errors);
        logsBlock.put("warnings", warnings);
        logsBlock.put("anomalyGroups", anomalies.size());
        logsBlock.put("criticalAnomalies", criticalAnomalies);
        logsBlock.put("spikes", spikes);
        logsBlock.put("lastIngestAt", logRepository.lastIngestedAt());

        Map<String, Object> testsBlock = new LinkedHashMap<>();
        testsBlock.put("total", tests.getTotalTests());
        testsBlock.put("failed", tests.getTotalFailures() + tests.getTotalErrors());
        testsBlock.put("successRate", tests.getSuccessRatePercent());
        testsBlock.put("lineCoverage", tests.getLineCoverage());
        testsBlock.put("executedAt", tests.getExecutedAt());
        testsBlock.put("trend", historyService.history(12));
        testsBlock.put("flaky", historyService.flakyTests().size());

        Map<String, Object> aiBlock = new LinkedHashMap<>();
        aiBlock.put("pending", recommendationStore.count(AiRecommendation.ValidationStatus.PENDING_REVIEW));
        aiBlock.put("approved", recommendationStore.count(AiRecommendation.ValidationStatus.APPROVED));
        aiBlock.put("rejected", recommendationStore.count(AiRecommendation.ValidationStatus.REJECTED));

        Map<String, Object> services = new LinkedHashMap<>();
        services.put("backend", Map.of("status", "UP"));
        services.put("database", Map.of("status", "UP"));
        services.put("iaService", aiClient.health());
        services.put("sonarqube", sonarIssueFetcher.health());

        List<Vulnerability> topVulns = vulnerabilityService.list("OPEN", null, null, null).stream().limit(5).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("healthScore", health);
        result.put("healthLabel", label);
        result.put("logs", logsBlock);
        result.put("tests", testsBlock);
        result.put("security", vulnStats);
        result.put("ai", aiBlock);
        result.put("services", services);
        result.put("topAnomalies", anomalies.stream().limit(5).toList());
        result.put("topVulnerabilities", topVulns);
        result.put("qualityGate", qualityGateService.evaluate());
        result.put("recentJobs", jobService.recent(6));
        return result;
    }
}
