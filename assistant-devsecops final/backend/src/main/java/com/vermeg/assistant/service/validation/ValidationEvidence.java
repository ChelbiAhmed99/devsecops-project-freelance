/**
 * Role : rassembler les preuves mesurees d'une application avant sa validation.
 * Contenu : definition de ValidationEvidence et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.validation;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.ApplicationCriticality;
import com.vermeg.assistant.model.Diagnostics;
import com.vermeg.assistant.model.ValidationModels;
import com.vermeg.assistant.model.Vulnerability;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Preuves d'une application à l'instant de la validation : résultats de tests, logs,
 * incidents corrélés, vulnérabilités et historique de ses validations précédentes.
 * Objet de données simple (sans dépendance Spring) : le moteur est testable isolément.
 */
public class ValidationEvidence {

    public String applicationCode;
    public String applicationName;
    public ApplicationCriticality criticality = ApplicationCriticality.MEDIUM;
    public LocalDateTime now = LocalDateTime.now();

    // ---- Tests
    public boolean testsAvailable;
    public Long testRunId;
    public LocalDateTime testRunAt;
    public String testTrigger;
    public int totalTests;
    public int failedTests;
    public int skippedTests;
    public double successRate;
    public Double lineCoverage;
    public Double branchCoverage;
    public double testDurationSeconds;
    public List<ValidationModels.TestFailureView> failures = new ArrayList<>();
    /** Tests réussis à l'exécution précédente et en échec maintenant (régression). */
    public List<String> newFailures = new ArrayList<>();
    /** Tests en échec à l'exécution précédente et réussis maintenant. */
    public List<String> fixedTests = new ArrayList<>();
    public List<ApiDtos.FlakyTest> flaky = new ArrayList<>();
    public List<ApiDtos.TestRunPoint> testTrend = new ArrayList<>();
    /** Une exécution précédente existe (permet de détecter les nouveaux échecs). */
    public boolean previousRunAvailable;

    // ---- Logs
    public boolean logsAvailable;
    public long logCount;
    public long errorCount;
    public long warnCount;
    public long fatalServerCount;
    public double errorRatePercent;
    public Map<String, Long> logsByLayer = new LinkedHashMap<>();
    public Map<String, Long> errorsByLayer = new LinkedHashMap<>();
    public int criticalAnomalies;
    public int spikes;
    public List<ValidationModels.AnomalyView> anomalies = new ArrayList<>();
    public List<String> logExcerpts = new ArrayList<>();
    public List<Diagnostics.CorrelatedIncident> incidents = new ArrayList<>();

    // ---- Sécurité
    /** Au moins un résultat de scan (quel que soit le statut) existe pour l'application. */
    public boolean securityScanned;
    public long openVulns;
    public Map<String, Long> openBySeverity = new LinkedHashMap<>();
    public List<Vulnerability> topVulnerabilities = new ArrayList<>();

    // ---- Historique de validation (référence propre à l'application)
    public List<Double> previousScores = new ArrayList<>();
    public Double previousScore;
    public String previousVerdict;

    /** Confiance accordée à l'IA, apprise des décisions humaines passées (1 = pas encore d'historique). */
    public double llmTrust = 1.0;
    public int llmTrustSamples;

    public long openCount(String severity) {
        return openBySeverity.getOrDefault(severity, 0L);
    }

    public double testAgeHours() {
        if (testRunAt == null) return Double.MAX_VALUE;
        return Math.max(0, java.time.Duration.between(testRunAt, now).toMinutes() / 60.0);
    }
}
