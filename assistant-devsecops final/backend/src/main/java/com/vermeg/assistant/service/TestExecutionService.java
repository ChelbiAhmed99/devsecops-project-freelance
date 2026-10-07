package com.vermeg.assistant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Déclenche "mvn test" sur le projet surveillé (par défaut l'assistant lui-même,
 * "dogfooding") et mémorise la fenêtre temporelle de l'exécution pour corréler
 * les échecs avec les logs émis pendant le run.
 */
@Service
public class TestExecutionService {

    @Value("${assistant.tests.project-path:.}")
    private String projectPath;

    @Value("${assistant.tests.timeout-seconds:600}")
    private int timeoutSeconds;

    private volatile TestRunWindow lastRunWindow;

    public record ExecutionResult(boolean success, int exitCode, String output) { }

    public record TestRunWindow(LocalDateTime start, LocalDateTime end) { }

    public ExecutionResult runTests() {
        LocalDateTime start = LocalDateTime.now();
        try {
            // -Dmaven.test.failure.ignore : on veut les rapports même si des tests échouent
            ProcessRunner.Result r = ProcessRunner.run(
                    List.of(ProcessRunner.mavenCommand(), "-B", "test", "-Dmaven.test.failure.ignore=true"),
                    Path.of(projectPath).toAbsolutePath().toFile(), timeoutSeconds, true);
            lastRunWindow = new TestRunWindow(start, LocalDateTime.now());
            if (r.timedOut()) {
                return new ExecutionResult(false, -1, "Timeout : l'exécution des tests a dépassé " + timeoutSeconds + "s");
            }
            return new ExecutionResult(r.exitCode() == 0, r.exitCode(), r.stdout());
        } catch (IOException e) {
            lastRunWindow = new TestRunWindow(start, LocalDateTime.now());
            return new ExecutionResult(false, -1, "Impossible de lancer Maven (est-il installé et dans le PATH ?) : "
                    + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecutionResult(false, -1, "Exécution interrompue");
        }
    }

    public Optional<TestRunWindow> getLastRunWindow() {
        return Optional.ofNullable(lastRunWindow);
    }
}
