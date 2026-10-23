/**
 * Role : definir les donnees echangees ou calculees pour le modele CorrelatedFailure.
 * Contenu : definition de CorrelatedFailure et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.model;

import java.util.List;

/**
 * Associe un test en échec aux logs les plus pertinents pour comprendre la
 * cause probable, avec une note expliquant la méthode de corrélation.
 */
public class CorrelatedFailure {

    private String suiteName;
    private String testCaseName;
    private String failureMessage;
    private List<LogEntry> relatedLogs;
    private String correlationNote;

    public CorrelatedFailure() {
    }

    public CorrelatedFailure(String suiteName, String testCaseName, String failureMessage,
                             List<LogEntry> relatedLogs, String correlationNote) {
        this.suiteName = suiteName;
        this.testCaseName = testCaseName;
        this.failureMessage = failureMessage;
        this.relatedLogs = relatedLogs;
        this.correlationNote = correlationNote;
    }

    public String getSuiteName() { return suiteName; }
    public void setSuiteName(String suiteName) { this.suiteName = suiteName; }
    public String getTestCaseName() { return testCaseName; }
    public void setTestCaseName(String testCaseName) { this.testCaseName = testCaseName; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public List<LogEntry> getRelatedLogs() { return relatedLogs; }
    public void setRelatedLogs(List<LogEntry> relatedLogs) { this.relatedLogs = relatedLogs; }
    public String getCorrelationNote() { return correlationNote; }
    public void setCorrelationNote(String correlationNote) { this.correlationNote = correlationNote; }
}
