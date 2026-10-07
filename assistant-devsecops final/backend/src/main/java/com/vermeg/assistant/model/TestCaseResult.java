package com.vermeg.assistant.model;

/** Résultat d'un test individuel extrait d'un rapport Surefire. */
public class TestCaseResult {

    private String name;
    private Status status;
    private String failureMessage;
    private double timeSeconds;
    /** Extrait de la trace d'échec (tronqué), utile au diagnostic et à l'IA. */
    private String stackTrace;

    public enum Status {
        PASSED, FAILED, ERROR, SKIPPED;

        public boolean isFailure() {
            return this == FAILED || this == ERROR;
        }
    }

    public TestCaseResult() {
    }

    public TestCaseResult(String name, Status status, String failureMessage, double timeSeconds) {
        this.name = name;
        this.status = status;
        this.failureMessage = failureMessage;
        this.timeSeconds = timeSeconds;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public double getTimeSeconds() { return timeSeconds; }
    public void setTimeSeconds(double timeSeconds) { this.timeSeconds = timeSeconds; }
    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
}
