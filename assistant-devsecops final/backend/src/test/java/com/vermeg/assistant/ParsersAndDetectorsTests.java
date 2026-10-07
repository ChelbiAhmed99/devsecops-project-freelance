package com.vermeg.assistant;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.service.AnomalyCategorizer;
import com.vermeg.assistant.service.JacocoReportParser;
import com.vermeg.assistant.service.LogParserService;
import com.vermeg.assistant.service.RiskScoring;
import com.vermeg.assistant.service.TimeSeriesAnomalyDetector;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ParsersAndDetectorsTests {

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parserShouldHandleClassicSpringBootJsonAndStackTraces() {
        String raw = """
                2026-09-18 08:22:41 ERROR [payment-service] Connection timeout to database after 30000ms
                2026-09-18T08:23:00.456+02:00 ERROR 4242 --- [order-api] [nio-8080-exec-1] c.v.o.OrderController : NullPointerException while building order
                java.lang.NullPointerException: boom
                \tat com.vermeg.order.OrderController.build(OrderController.java:88)
                {"@timestamp":"2026-09-18T08:24:00Z","level":"FATAL","service":"collateral-service","message":"Out of memory"}
                ligne illisible
                """;
        LogParserService.ParseResult result = new LogParserService().parseWithStats(stream(raw));

        assertThat(result.entries()).hasSize(3);
        assertThat(result.skippedLines()).isEqualTo(1);
        assertThat(result.entries().get(1).getSource()).isEqualTo("order-api");
        assertThat(result.entries().get(1).getStackTrace()).contains("OrderController.java:88");
        assertThat(result.entries().get(2).getLevel()).isEqualTo(LogEntry.LogLevel.FATAL);
        assertThat(result.entries().get(2).getSource()).isEqualTo("collateral-service");
    }

    @Test
    void detectorShouldFlagErrorBurst() {
        List<LogEntry> logs = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 9, 18, 6, 0);
        for (int b = 0; b < 30; b++) {
            logs.add(new LogEntry(base.plusMinutes(b * 5L), LogEntry.LogLevel.ERROR, "svc", "err"));
        }
        for (int k = 0; k < 12; k++) {
            logs.add(new LogEntry(base.plusMinutes(100).plusSeconds(k), LogEntry.LogLevel.ERROR, "svc", "err"));
        }
        List<ApiDtos.FrequencySpike> spikes = new TimeSeriesAnomalyDetector().detectSpikes(logs);

        assertThat(spikes).hasSize(1);
        assertThat(spikes.get(0).count()).isEqualTo(13);
    }

    @Test
    void jacocoParserShouldReadGlobalCounters() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">"
                + "<report name=\"x\"><counter type=\"BRANCH\" missed=\"5\" covered=\"15\"/>"
                + "<counter type=\"LINE\" missed=\"25\" covered=\"75\"/></report>";
        Optional<JacocoReportParser.Coverage> coverage = new JacocoReportParser().parse(stream(xml));

        assertThat(coverage).isPresent();
        assertThat(coverage.get().linePercent()).isEqualTo(75.0);
        assertThat(coverage.get().branchPercent()).isEqualTo(75.0);
    }

    @Test
    void riskScoreShouldRankCriticalWithFixAboveLow() {
        int critical = RiskScoring.score(Vulnerability.Severity.CRITICAL, 9.8, "trivy", "1.2.3", null, null);
        int low = RiskScoring.score(Vulnerability.Severity.LOW, null, "sonarqube", null, null, null);
        assertThat(critical).isGreaterThan(low).isLessThanOrEqualTo(100);
    }

    @Test
    void categorizerShouldClassifyCommonIncidents() {
        assertThat(AnomalyCategorizer.categorize("HikariPool-1 - Connection is not available", null)).isEqualTo("DATABASE");
        assertThat(AnomalyCategorizer.categorize("JWT signature does not match", null)).isEqualTo("SECURITY");
        assertThat(AnomalyCategorizer.categorize("Failed", "java.lang.NullPointerException")).isEqualTo("CODE_DEFECT");
    }
}
