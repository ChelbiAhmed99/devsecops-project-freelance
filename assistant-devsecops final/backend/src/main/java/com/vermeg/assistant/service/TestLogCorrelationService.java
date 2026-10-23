/**
 * Role : relier les echecs de tests aux evenements de journalisation correspondants.
 * Contenu : definition de TestLogCorrelationService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.model.CorrelatedFailure;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.model.TestCaseResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Sprint 5 : relie chaque test en échec/erreur aux logs qui aident le plus
 * probablement à comprendre la cause.
 * <p>
 * Stratégie à deux niveaux :
 * 1. Si on connaît la fenêtre temporelle de l'exécution des tests, on
 *    restreint d'abord aux logs ERROR/WARN émis pendant cette fenêtre
 *    (c'est le signal le plus fort : "ça s'est passé pendant le run").
 * 2. On classe ensuite (ou, à défaut de logs dans la fenêtre, on classe
 *    directement parmi tous les logs ERROR/WARN connus) par similarité
 *    textuelle entre le nom du test / message d'échec et le message de log.
 * <p>
 * Cette approche reste robuste même en démo, quand les logs d'exemple ne
 * datent pas de l'instant présent : on retombe alors sur la similarité
 * textuelle seule, ce qui est signalé explicitement dans la note de
 * corrélation pour rester honnête sur la méthode utilisée.
 */
@Service
public class TestLogCorrelationService {

    private static final int MAX_RELATED_LOGS = 3;
    private static final double MIN_SIMILARITY_TO_KEEP = 0.05;

    public List<CorrelatedFailure> correlate(List<SuiteResult> suites,
                                              List<LogEntry> allLogs,
                                              Optional<TestExecutionService.TestRunWindow> window) {

        List<CorrelatedFailure> results = new ArrayList<>();

        for (SuiteResult suite : suites) {
            for (TestCaseResult testCase : suite.getTestCases()) {
                if (testCase.getStatus() != TestCaseResult.Status.FAILED
                        && testCase.getStatus() != TestCaseResult.Status.ERROR) {
                    continue;
                }
                results.add(correlateSingleFailure(suite, testCase, allLogs, window));
            }
        }

        return results;
    }

    private CorrelatedFailure correlateSingleFailure(SuiteResult suite,
                                                       TestCaseResult testCase,
                                                       List<LogEntry> allLogs,
                                                       Optional<TestExecutionService.TestRunWindow> window) {

        String queryText = humanizeTestName(testCase.getName())
                + " " + (testCase.getFailureMessage() != null ? testCase.getFailureMessage() : "")
                + " " + humanizeTestName(String.valueOf(suite.getSuiteName()).replaceAll(".*\\.", "").replace("Tests", "").replace("Test", ""));

        List<LogEntry> errorAndWarnLogs = allLogs.stream()
                .filter(l -> l.getLevel() != null && l.getLevel().isAnomalyCandidate())
                .collect(Collectors.toList());

        String note;
        List<LogEntry> candidateLogs;

        if (window.isPresent()) {
            List<LogEntry> withinWindow = errorAndWarnLogs.stream()
                    .filter(l -> l.getTimestamp() != null && !l.getTimestamp().isBefore(window.get().start())
                            && !l.getTimestamp().isAfter(window.get().end()))
                    .collect(Collectors.toList());

            if (!withinWindow.isEmpty()) {
                candidateLogs = withinWindow;
                note = "Logs émis pendant la fenêtre d'exécution des tests, classés par pertinence.";
            } else {
                candidateLogs = errorAndWarnLogs;
                note = "Aucun log émis pendant la fenêtre d'exécution : corrélation basée sur la "
                        + "similarité textuelle avec l'ensemble des logs connus.";
            }
        } else {
            candidateLogs = errorAndWarnLogs;
            note = "Fenêtre d'exécution inconnue : corrélation basée sur la similarité textuelle.";
        }

        List<LogEntry> relatedLogs = candidateLogs.stream()
                .map(log -> new ScoredLog(log, TextSimilarityUtil.jaccardSimilarity(queryText, log.getMessage())))
                .filter(scored -> scored.score >= MIN_SIMILARITY_TO_KEEP)
                .sorted(Comparator.comparingDouble((ScoredLog s) -> s.score).reversed())
                .limit(MAX_RELATED_LOGS)
                .map(ScoredLog::log)
                .collect(Collectors.toList());

        if (relatedLogs.isEmpty()) {
            note = "Aucun log suffisamment pertinent trouvé pour cet échec.";
        }

        return new CorrelatedFailure(
                suite.getSuiteName(),
                testCase.getName(),
                testCase.getFailureMessage(),
                relatedLogs,
                note
        );
    }

    /**
     * Transforme un nom de méthode de test en texte lisible pour améliorer
     * la comparaison de similarité avec des messages de log en langage
     * naturel. Ex: "shouldRejectExpiredToken" -> "should Reject Expired Token"
     */
    private String humanizeTestName(String testName) {
        return testName == null ? "" : testName.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private record ScoredLog(LogEntry log, double score) {
    }
}
