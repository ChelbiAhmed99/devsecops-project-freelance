/**
 * Role : documenter le comportement attendu en verifiant les analyses et detections effectuees sur les journaux.
 * Contenu : definition de LogAnalysisTests et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant;

import com.vermeg.assistant.model.AnomalyGroup;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.service.AnomalySummaryService;
import com.vermeg.assistant.service.LogAnalysisService;
import com.vermeg.assistant.service.LogParserService;

import com.vermeg.assistant.service.TextSimilarityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires des briques "analyse des logs" (Sprint 1) et
 * "clustering par similarité + résumé" (Sprint 3).
 * À lancer avec : mvn test
 */
class LogAnalysisTests {

    private LogParserService parserService;
    private LogAnalysisService analysisService;

    @BeforeEach
    void setUp() {
        parserService = new LogParserService();
        analysisService = new LogAnalysisService(new AnomalySummaryService());
        analysisService.setAnomalyThreshold(3);
        analysisService.setSimilarityThreshold(0.6);
    }

    @Test
    void shouldParseValidLogLine() {
        String raw = "2026-09-18 08:22:41 ERROR [payment-service] Connection timeout to database after 30000ms\n";
        InputStream stream = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));

        List<LogEntry> entries = parserService.parseStream(stream);

        assertThat(entries).hasSize(1);
        LogEntry entry = entries.get(0);
        assertThat(entry.getLevel()).isEqualTo(LogEntry.LogLevel.ERROR);
        assertThat(entry.getSource()).isEqualTo("payment-service");
        assertThat(entry.getMessage()).contains("Connection timeout");
    }

    @Test
    void shouldIgnoreMalformedLines() {
        String raw = "ceci n'est pas une ligne de log valide\n"
                + "2026-09-18 08:22:41 ERROR [payment-service] Connection timeout to database after 30000ms\n";
        InputStream stream = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));

        List<LogEntry> entries = parserService.parseStream(stream);

        assertThat(entries).hasSize(1);
    }

    @Test
    void shouldGroupSimilarErrorsAndFlagAnomaly() {
        String raw =
                "2026-09-18 08:22:41 ERROR [payment-service] Connection timeout to database after 30000ms\n"
                + "2026-09-18 08:23:41 ERROR [payment-service] Connection timeout to database after 45000ms\n"
                + "2026-09-18 08:24:41 ERROR [payment-service] Connection timeout to database after 31000ms\n"
                + "2026-09-18 08:25:41 INFO [payment-service] Health check OK\n";

        InputStream stream = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
        List<LogEntry> entries = parserService.parseStream(stream);

        List<AnomalyGroup> anomalies = analysisService.detectAnomalies(entries);

        assertThat(anomalies).hasSize(1);
        AnomalyGroup group = anomalies.get(0);
        assertThat(group.getOccurrences()).isEqualTo(3);
        assertThat(group.isCritical()).isTrue();
        assertThat(group.getNormalizedMessage()).contains("Connection timeout");
    }

    @Test
    void shouldGenerateNonEmptySummaryForEachGroup() {
        String raw =
                "2026-09-18 08:22:41 ERROR [payment-service] Connection timeout to database after 30000ms\n"
                + "2026-09-18 08:23:41 ERROR [payment-service] Connection timeout to database after 45000ms\n";

        InputStream stream = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
        List<LogEntry> entries = parserService.parseStream(stream);

        List<AnomalyGroup> anomalies = analysisService.detectAnomalies(entries);

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).getSummary()).isNotBlank();
        assertThat(anomalies.get(0).getSummary()).contains("payment-service");
        assertThat(anomalies.get(0).getAffectedSources()).containsExactly("payment-service");
    }

    @Test
    void shouldMergeSimilarButNotIdenticalMessages() {
        // Deux messages différents après normalisation (mots différents),
        // mais très proches textuellement : doivent être fusionnés par la
        // passe de similarité si le seuil est suffisamment permissif.
        analysisService.setSimilarityThreshold(0.5);

        String raw =
                "2026-09-18 08:22:41 ERROR [order-service] Unable to reach payment gateway\n"
                + "2026-09-18 08:23:41 ERROR [order-service] Unable to reach payment provider\n"
                + "2026-09-18 08:24:41 ERROR [order-service] Unable to reach payment gateway\n";

        InputStream stream = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));
        List<LogEntry> entries = parserService.parseStream(stream);

        List<AnomalyGroup> anomalies = analysisService.detectAnomalies(entries);

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).getOccurrences()).isEqualTo(3);
    }

    @Test
    void jaccardSimilarityShouldBeHighForNearlyIdenticalSentences() {
        double similarity = TextSimilarityUtil.jaccardSimilarity(
                "Unable to reach payment gateway",
                "Unable to reach payment provider"
        );
        assertThat(similarity).isGreaterThan(0.5);
    }

    @Test
    void fatalErrorShouldBeCriticalFromFirstOccurrence() {
        List<LogEntry> entries = List.of(new LogEntry(LocalDateTime.now(), LogEntry.LogLevel.FATAL,
                "collateral-service", "Application shutting down after OutOfMemoryError"));
        List<AnomalyGroup> anomalies = analysisService.detectAnomalies(entries);
        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).isCritical()).isTrue();
        assertThat(anomalies.get(0).getCategory()).isEqualTo("RESOURCE");
    }
}
