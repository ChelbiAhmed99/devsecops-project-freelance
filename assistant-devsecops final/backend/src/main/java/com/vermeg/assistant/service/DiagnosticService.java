/**
 * Role : fournir les incidents correles et la cause racine d'une application (ou de tout le portefeuille).
 * Contenu : definition de DiagnosticService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.domain.ManagedApplication;
import com.vermeg.assistant.domain.TestRunRecord;
import com.vermeg.assistant.model.Diagnostics;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.model.TestCaseResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Façade Spring du moteur de corrélation : choisit les bons logs (ceux de l'application ou
 * de tout le portefeuille) et les échecs de la dernière exécution de tests, puis délègue à
 * {@link IncidentCorrelationEngine}.
 */
@Service
public class DiagnosticService {

    private final IncidentCorrelationEngine engine;
    private final LogStore logStore;
    private final ApplicationService applicationService;
    private final TestRunHistoryService historyService;

    public DiagnosticService(IncidentCorrelationEngine engine, LogStore logStore,
                             ApplicationService applicationService, TestRunHistoryService historyService) {
        this.engine = engine;
        this.logStore = logStore;
        this.applicationService = applicationService;
        this.historyService = historyService;
    }

    /** Incidents corrélés ; {@code application} vide = tout le portefeuille. */
    public List<Diagnostics.CorrelatedIncident> incidents(String application) {
        if (application == null || application.isBlank()) {
            return engine.correlate(logStore.getAll(), failures(historyService.latestRecord()));
        }
        ManagedApplication app = applicationService.require(application);
        return incidents(app, logsOf(app));
    }

    public List<Diagnostics.CorrelatedIncident> incidents(ManagedApplication app, List<LogEntry> logs) {
        return engine.correlate(logs, failures(historyService.latestRecord(app.getCode())));
    }

    public List<LogEntry> logsOf(ManagedApplication app) {
        return logStore.getForApplication(app.getCode(), app.logSourceSet());
    }

    /** Échecs de tests d'une exécution, au format attendu par le moteur de corrélation. */
    public List<Diagnostics.TestFailureRef> failures(Optional<TestRunRecord> run) {
        List<Diagnostics.TestFailureRef> refs = new ArrayList<>();
        if (run.isEmpty()) return refs;
        for (SuiteResult suite : historyService.readSuites(run.get().getSuitesJson())) {
            if (suite.getTestCases() == null) continue;
            for (TestCaseResult tc : suite.getTestCases()) {
                if (!tc.getStatus().isFailure()) continue;
                refs.add(new Diagnostics.TestFailureRef("test:" + suite.getSuiteName() + "#" + tc.getName(),
                        suite.getSuiteName(), tc.getName(), tc.getFailureMessage(), tc.getStackTrace()));
            }
        }
        return refs;
    }
}
