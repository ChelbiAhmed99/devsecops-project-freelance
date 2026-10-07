package com.vermeg.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Tâches planifiées : rétention des logs et synchronisation périodique de SonarQube. */
@Component
public class MaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);

    private final LogStore logStore;
    private final SecurityScanService securityScanService;
    private final SonarIssueFetcher sonarIssueFetcher;

    @Value("${assistant.logs.retention-days:30}")
    private int retentionDays;

    public MaintenanceScheduler(LogStore logStore, SecurityScanService securityScanService,
                                SonarIssueFetcher sonarIssueFetcher) {
        this.logStore = logStore;
        this.securityScanService = securityScanService;
        this.sonarIssueFetcher = sonarIssueFetcher;
    }

    @Scheduled(cron = "0 30 2 * * *")
    public void purgeOldLogs() {
        if (retentionDays <= 0) return;
        int deleted = logStore.purgeOlderThan(LocalDateTime.now().minusDays(retentionDays));
        if (deleted > 0) {
            log.info("Rétention : {} logs de plus de {} jours supprimés", deleted, retentionDays);
        }
    }

    @Scheduled(fixedDelayString = "${assistant.security.sonar.sync-interval-ms:600000}", initialDelay = 60000)
    public void syncSonar() {
        if (!sonarIssueFetcher.isEnabled()) return;
        securityScanService.syncSonarNow().ifPresent(diff ->
                log.info("Synchronisation Sonar : {}", SecurityScanService.describe(diff)));
    }
}
