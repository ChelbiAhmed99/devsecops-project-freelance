package com.vermeg.assistant.service;

import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.TestExecutionSummary;
import com.vermeg.assistant.model.Vulnerability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Quality gate DevSecOps : décision PASSED/FAILED consommée par le pipeline CI/CD
 * (le job échoue si la porte qualité n'est pas franchie).
 */
@Service
public class QualityGateService {

    private final VulnerabilityService vulnerabilityService;
    private final TestOrchestrationService testService;
    private final LogAnalysisService analysisService;
    private final LogStore logStore;

    @Value("${assistant.quality-gate.max-critical-vulnerabilities:0}")
    private long maxCritical;
    @Value("${assistant.quality-gate.max-high-vulnerabilities:5}")
    private long maxHigh;
    @Value("${assistant.quality-gate.min-test-success-rate:95}")
    private double minSuccessRate;
    @Value("${assistant.quality-gate.min-line-coverage:40}")
    private double minCoverage;
    @Value("${assistant.quality-gate.max-critical-anomalies:10}")
    private long maxCriticalAnomalies;

    public QualityGateService(VulnerabilityService vulnerabilityService, TestOrchestrationService testService,
                              LogAnalysisService analysisService, LogStore logStore) {
        this.vulnerabilityService = vulnerabilityService;
        this.testService = testService;
        this.analysisService = analysisService;
        this.logStore = logStore;
    }

    public ApiDtos.QualityGateResult evaluate() {
        List<ApiDtos.GateCondition> conditions = new ArrayList<>();

        long critical = vulnerabilityService.countOpen(Vulnerability.Severity.CRITICAL);
        conditions.add(new ApiDtos.GateCondition("Vulnérabilités critiques ouvertes", String.valueOf(critical),
                "≤ " + maxCritical, critical <= maxCritical));

        long high = vulnerabilityService.countOpen(Vulnerability.Severity.HIGH);
        conditions.add(new ApiDtos.GateCondition("Vulnérabilités hautes ouvertes", String.valueOf(high),
                "≤ " + maxHigh, high <= maxHigh));

        TestExecutionSummary tests = testService.currentResults();
        if (tests.getTotalTests() > 0) {
            conditions.add(new ApiDtos.GateCondition("Taux de réussite des tests", tests.getSuccessRatePercent() + " %",
                    "≥ " + minSuccessRate + " %", tests.getSuccessRatePercent() >= minSuccessRate));
        } else {
            conditions.add(new ApiDtos.GateCondition("Taux de réussite des tests", "aucun résultat",
                    "≥ " + minSuccessRate + " %", false));
        }
        if (minCoverage > 0) {
            Double cov = tests.getLineCoverage();
            conditions.add(new ApiDtos.GateCondition("Couverture de lignes", cov == null ? "inconnue" : cov + " %",
                    "≥ " + minCoverage + " %", cov != null && cov >= minCoverage));
        }

        long criticalAnomalies = analysisService.detectAnomalies(logStore.getAll()).stream()
                .filter(AnomalyGroup::isCritical).count();
        conditions.add(new ApiDtos.GateCondition("Anomalies de logs critiques", String.valueOf(criticalAnomalies),
                "≤ " + maxCriticalAnomalies, criticalAnomalies <= maxCriticalAnomalies));

        boolean passed = conditions.stream().allMatch(ApiDtos.GateCondition::passed);
        return new ApiDtos.QualityGateResult(passed ? "PASSED" : "FAILED", conditions, LocalDateTime.now());
    }
}
