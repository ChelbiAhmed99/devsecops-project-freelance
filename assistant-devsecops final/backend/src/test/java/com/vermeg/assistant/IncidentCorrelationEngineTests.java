/**
 * Role : verifier la correlation multi-couches et la designation explicable de la cause racine.
 * Contenu : tests unitaires de IncidentCorrelationEngine.
 */

package com.vermeg.assistant;

import com.vermeg.assistant.model.Diagnostics;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.service.IncidentCorrelationEngine;
import com.vermeg.assistant.service.LogParserService;
import com.vermeg.assistant.service.LogTypeClassifier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentCorrelationEngineTests {

    private final IncidentCorrelationEngine engine = new IncidentCorrelationEngine();
    private final LocalDateTime t0 = LocalDateTime.of(2026, 9, 18, 10, 0, 0);

    private LogEntry log(int seconds, LogEntry.LogLevel level, String source, String message, String type) {
        LogEntry e = new LogEntry(t0.plusSeconds(seconds), level, source, message);
        e.setLogType(type);
        return e;
    }

    @Test
    void shouldDesignateServerDatasourceFailureAsRootCauseOfApplicationErrors() {
        List<LogEntry> logs = new ArrayList<>();
        LogEntry cause = log(0, LogEntry.LogLevel.ERROR, "wildfly-jca", "IJ000453: Unable to get managed connection for java:jboss/datasources/TitresDS", LogTypeClassifier.SERVER);
        cause.setStackTrace("Caused by: java.net.ConnectException: Connection refused");
        logs.add(cause);
        for (int i = 1; i <= 4; i++) {
            logs.add(log(i * 3, LogEntry.LogLevel.ERROR, "SqlExceptionHelper", "Unable to acquire JDBC Connection", LogTypeClassifier.APPLICATION));
            logs.add(log(i * 3 + 1, LogEntry.LogLevel.ERROR, "OrdreService", "could not open JPA EntityManager for transaction", LogTypeClassifier.APPLICATION));
        }
        List<Diagnostics.CorrelatedIncident> incidents = engine.correlate(logs);
        assertThat(incidents).hasSize(1);
        Diagnostics.CorrelatedIncident incident = incidents.get(0);
        assertThat(incident.layers()).contains(LogTypeClassifier.SERVER, LogTypeClassifier.APPLICATION);
        assertThat(incident.rootCause().signal().layer()).isEqualTo(LogTypeClassifier.SERVER);
        assertThat(incident.rootCause().rule()).isEqualTo("DB_UNAVAILABLE");
        assertThat(incident.category()).isEqualTo("DATABASE");
        assertThat(incident.rootCause().confidencePercent()).isGreaterThan(60);
        // explicabilité : 5 facteurs dont la somme des contributions = score
        assertThat(incident.rootCause().factors()).hasSize(5);
        double sum = incident.rootCause().factors().stream().mapToDouble(Diagnostics.ExplanationFactor::contribution).sum();
        assertThat(Math.abs(sum - incident.rootCause().score()) < 0.01).isTrue();
        assertThat(incident.chain()).isNotEmpty();
        assertThat(incident.chain()).allMatch(l -> "DB_UNAVAILABLE".equals(l.rule()));
    }

    @Test
    void shouldSplitIncidentsSeparatedByQuietPeriods() {
        List<LogEntry> logs = List.of(
                log(0, LogEntry.LogLevel.ERROR, "a", "Connection timeout to payment gateway", LogTypeClassifier.APPLICATION),
                log(20, LogEntry.LogLevel.ERROR, "a", "Connection timeout to payment gateway", LogTypeClassifier.APPLICATION),
                log(3600, LogEntry.LogLevel.ERROR, "b", "NullPointerException in ReportService", LogTypeClassifier.APPLICATION),
                log(3610, LogEntry.LogLevel.ERROR, "b", "NullPointerException in ReportService", LogTypeClassifier.APPLICATION));
        assertThat(engine.correlate(logs)).hasSize(2);
    }

    @Test
    void shouldIgnoreIsolatedSingleErrorsButKeepFatal() {
        assertThat(engine.correlate(List.of(log(0, LogEntry.LogLevel.ERROR, "a", "oops", LogTypeClassifier.APPLICATION)))).isEmpty();
        assertThat(engine.correlate(List.of(log(0, LogEntry.LogLevel.FATAL, "a", "java.lang.OutOfMemoryError: Java heap space", LogTypeClassifier.APPLICATION))))
                .hasSize(1);
    }

    @Test
    void shouldFallBackToTemporalChainWhenNoRuleApplies() {
        List<LogEntry> logs = List.of(
                log(0, LogEntry.LogLevel.ERROR, "a", "Validation du lot impossible", LogTypeClassifier.APPLICATION),
                log(5, LogEntry.LogLevel.ERROR, "b", "Export abandonné", LogTypeClassifier.APPLICATION));
        Diagnostics.CorrelatedIncident incident = engine.correlate(logs).get(0);
        assertThat(incident.chain()).hasSize(1);
        assertThat(incident.chain().get(0).rule()).isEqualTo("TEMPORAL");
        assertThat(incident.rootCause().rule()).isNull();
    }

    @Test
    void shouldLinkFailingTestsToIncident() {
        List<LogEntry> logs = List.of(
                log(0, LogEntry.LogLevel.ERROR, "db", "HikariPool-1 - Connection is not available, request timed out after 30000ms", LogTypeClassifier.APPLICATION),
                log(2, LogEntry.LogLevel.ERROR, "api", "Request failed with 503 Service Unavailable", LogTypeClassifier.APPLICATION));
        List<Diagnostics.TestFailureRef> failures = List.of(
                new Diagnostics.TestFailureRef("test:RepoIT#shouldLoad", "RepoIT", "shouldLoad", "Connection is not available, request timed out", null),
                new Diagnostics.TestFailureRef("test:MathTest#adds", "MathTest", "adds", "expected 2 but was 3", null));
        Diagnostics.CorrelatedIncident incident = engine.correlate(logs, failures).get(0);
        assertThat(incident.impactedTests()).containsExactly("test:RepoIT#shouldLoad");
        assertThat(incident.rootCause().rule()).isEqualTo("DB_POOL_SATURATION");
    }

    @Test
    void shouldCorrelateBundledWildFlyIncident() throws Exception {
        LogParserService parser = new LogParserService();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("demo/wildfly-server.log")) {
            List<Diagnostics.CorrelatedIncident> incidents = engine.correlate(parser.parseStream(in));
            assertThat(incidents).isNotEmpty();
            assertThat(incidents).anyMatch(i -> "DB_UNAVAILABLE".equals(i.rootCause().rule()));
            assertThat(incidents).anyMatch(i -> "DEPLOYMENT_FAILURE".equals(i.rootCause().rule()));
        }
    }
}
