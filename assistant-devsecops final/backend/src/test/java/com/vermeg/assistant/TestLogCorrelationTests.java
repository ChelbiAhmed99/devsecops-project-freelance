/**
 * Role : documenter le comportement attendu en verifiant le rapprochement entre echecs de tests et journaux.
 * Contenu : definition de TestLogCorrelationTests et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant;

import com.vermeg.assistant.model.CorrelatedFailure;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.model.TestCaseResult;
import com.vermeg.assistant.service.TestExecutionService;
import com.vermeg.assistant.service.TestLogCorrelationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de la corrélation entre tests en échec et logs (Sprint 5).
 */
class TestLogCorrelationTests {

    private final TestLogCorrelationService correlationService = new TestLogCorrelationService();

    @Test
    void shouldCorrelateFailureWithSimilarLogWhenNoWindowKnown() {
        TestCaseResult failedCase = new TestCaseResult(
                "shouldConnectToPaymentGateway",
                TestCaseResult.Status.FAILED,
                "Connection timeout while reaching payment gateway",
                0.2
        );
        SuiteResult suite = new SuiteResult("PaymentServiceTests", 1, 1, 0, 0, 0.2, List.of(failedCase));

        LogEntry relevantLog = new LogEntry(
                LocalDateTime.of(2026, 9, 18, 8, 22, 41),
                LogEntry.LogLevel.ERROR,
                "payment-service",
                "Connection timeout to database after 30000ms"
        );
        LogEntry irrelevantLog = new LogEntry(
                LocalDateTime.of(2026, 9, 18, 9, 10, 0),
                LogEntry.LogLevel.INFO,
                "auth-service",
                "User logged in successfully"
        );

        List<CorrelatedFailure> correlated = correlationService.correlate(
                List.of(suite), List.of(relevantLog, irrelevantLog), Optional.empty());

        assertThat(correlated).hasSize(1);
        CorrelatedFailure failure = correlated.get(0);
        assertThat(failure.getTestCaseName()).isEqualTo("shouldConnectToPaymentGateway");
        assertThat(failure.getRelatedLogs()).contains(relevantLog);
        assertThat(failure.getRelatedLogs()).doesNotContain(irrelevantLog); // niveau INFO exclu
        assertThat(failure.getCorrelationNote()).contains("similarité textuelle");
    }

    @Test
    void shouldPreferLogsInsideExecutionWindowWhenAvailable() {
        TestCaseResult failedCase = new TestCaseResult(
                "shouldValidateToken", TestCaseResult.Status.ERROR, "Token validation failed", 0.1);
        SuiteResult suite = new SuiteResult("AuthServiceTests", 1, 0, 1, 0, 0.1, List.of(failedCase));

        LocalDateTime windowStart = LocalDateTime.of(2026, 9, 20, 10, 0, 0);
        LocalDateTime windowEnd = LocalDateTime.of(2026, 9, 20, 10, 5, 0);

        LogEntry insideWindow = new LogEntry(
                LocalDateTime.of(2026, 9, 20, 10, 2, 0),
                LogEntry.LogLevel.ERROR, "auth-service", "Invalid token signature detected");
        LogEntry outsideWindow = new LogEntry(
                LocalDateTime.of(2026, 9, 18, 8, 0, 0),
                LogEntry.LogLevel.ERROR, "auth-service", "Invalid token signature detected");

        Optional<TestExecutionService.TestRunWindow> window =
                Optional.of(new TestExecutionService.TestRunWindow(windowStart, windowEnd));

        List<CorrelatedFailure> correlated = correlationService.correlate(
                List.of(suite), List.of(insideWindow, outsideWindow), window);

        assertThat(correlated).hasSize(1);
        assertThat(correlated.get(0).getRelatedLogs()).containsExactly(insideWindow);
        assertThat(correlated.get(0).getCorrelationNote()).contains("fenêtre d'exécution");
    }

    @Test
    void shouldIgnorePassedTests() {
        TestCaseResult passedCase = new TestCaseResult(
                "shouldPass", TestCaseResult.Status.PASSED, null, 0.05);
        SuiteResult suite = new SuiteResult("SomeTests", 1, 0, 0, 0, 0.05, List.of(passedCase));

        List<CorrelatedFailure> correlated = correlationService.correlate(
                List.of(suite), List.of(), Optional.empty());

        assertThat(correlated).isEmpty();
    }
}
