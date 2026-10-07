package com.vermeg.assistant.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO "légers" exposés par l'API, regroupés sous forme de records Java
 * (immutables, sérialisés nativement par Jackson).
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    // ---------- Authentification ----------
    public record LoginRequest(String username, String password) { }

    public record UserView(Long id, String username, String fullName, String email, String role,
                           boolean enabled, LocalDateTime createdAt, LocalDateTime lastLoginAt) { }

    public record LoginResponse(String token, LocalDateTime expiresAt, UserView user) { }

    public record ChangePasswordRequest(String currentPassword, String newPassword) { }

    public record UserUpsertRequest(String username, String fullName, String email, String role,
                                    String password, Boolean enabled) { }

    // ---------- Logs ----------
    public record IngestResult(int ingested, int skippedLines, long totalInStore,
                               int criticalAnomalies) { }

    public record LogPage(List<LogEntry> content, long totalElements, int page, int size,
                          int totalPages) { }

    public record TimeBucket(LocalDateTime start, long total, long errors, long warnings) { }

    public record LogStats(long total, Map<String, Long> byLevel, Map<String, Long> bySource,
                           List<TimeBucket> timeline, LocalDateTime firstAt, LocalDateTime lastAt,
                           double errorRatePercent) { }

    public record FrequencySpike(String source, LocalDateTime bucketStart, long count,
                                 double baseline, double score, String method) { }

    public record SpikeReport(String method, int bucketMinutes, List<FrequencySpike> spikes,
                              List<TimeBucket> timeline) { }

    // ---------- Tests ----------
    public record TestRunPoint(Long runId, LocalDateTime executedAt, int total, int failed,
                               double successRate, Double lineCoverage, String trigger,
                               String branch) { }

    public record FlakyTest(String suiteName, String testName, int runsObserved, int failures,
                            int statusChanges, double flakinessPercent, String lastStatus) { }

    // ---------- Sécurité ----------
    public record ScanDiff(String source, int detected, int newCount, int updatedCount,
                           int resolvedCount, int reopenedCount) { }

    public record VulnerabilityStats(long open, long fixed, long total, Map<String, Long> openBySeverity,
                                     Map<String, Long> openBySource, Map<String, Long> byStatus,
                                     Double meanTimeToFixDays) { }

    public record VulnerabilityUpdate(String status, String assignee, String note) { }

    // ---------- Quality gate ----------
    public record GateCondition(String name, String actual, String threshold, boolean passed) { }

    public record QualityGateResult(String status, List<GateCondition> conditions,
                                    LocalDateTime evaluatedAt) { }

    // ---------- IA ----------
    public record ExplainRequest(String incidentType, String source, String message,
                                 Integer occurrences, String extraContext, String incidentRef,
                                 Boolean force) { }

    public record ReviewRequest(String status, String comment) { }

    public record ChatMessage(String role, String content) { }

    public record ChatRequest(String question, List<ChatMessage> history) { }

    public record ChatResponse(String answer, String provider, String model,
                               LocalDateTime answeredAt) { }

    // ---------- Audit ----------
    public record AuditView(Long id, LocalDateTime at, String username, String action,
                            String target, String details) { }
}
