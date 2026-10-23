/**
 * Role : verifier le parsing des logs JBoss/WildFly et CI/CD, et la classification par couche.
 * Contenu : tests unitaires de LogParserService, LogTypeClassifier et LogAnalysisService.normalize.
 */

package com.vermeg.assistant;

import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.service.LogAnalysisService;
import com.vermeg.assistant.service.LogParserService;
import com.vermeg.assistant.service.LogTypeClassifier;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WildFlyAndCiLogParsingTests {

    private final LogParserService parser = new LogParserService();

    private static InputStream text(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldParseWildFlyServerLogAsServerLayer() {
        Optional<LogEntry> e = parser.parseLine("2026-09-18 10:41:55,211 ERROR [org.jboss.jca.core.connectionmanager.listener.TxConnectionListener] "
                + "(default task-12) IJ000453: Unable to get managed connection for java:jboss/datasources/TitresDS");
        assertThat(e).isPresent();
        assertThat(e.get().getLevel()).isEqualTo(LogEntry.LogLevel.ERROR);
        assertThat(e.get().getSource()).isEqualTo("wildfly-jca");
        assertThat(e.get().getLogType()).isEqualTo(LogTypeClassifier.SERVER);
        // le thread "(default task-12)" est retiré du message
        assertThat(e.get().getMessage()).startsWith("IJ000453: Unable to get managed connection");
    }

    @Test
    void shouldKeepApplicationLoggersDeployedInWildFlyAsApplicationLayer() {
        Optional<LogEntry> e = parser.parseLine("2026-09-18 10:41:55,702 ERROR [com.vermeg.titres.service.OrdreService] (default task-12) "
                + "Impossible d'enregistrer l'ordre ORD-88412");
        assertThat(e).isPresent();
        assertThat(e.get().getSource()).isEqualTo("OrdreService");
        assertThat(e.get().getLogType()).isEqualTo(LogTypeClassifier.APPLICATION);
    }

    @Test
    void shouldParseWildFlyConsoleFormatWithTimeOnly() {
        Optional<LogEntry> e = parser.parseLine("10:23:41,123 ERROR [io.undertow.request] (default task-1) UT005023: Exception handling request to /api");
        assertThat(e).isPresent();
        assertThat(e.get().getTimestamp().toLocalDate()).isEqualTo(LocalDate.now());
        assertThat(e.get().getSource()).isEqualTo("wildfly-undertow");
        assertThat(e.get().getLogType()).isEqualTo(LogTypeClassifier.SERVER);
    }

    @Test
    void shouldNotMistakeClassicLinesWithParenthesesForWildFly() {
        Optional<LogEntry> e = parser.parseLine("2026-09-18 10:23:41 ERROR [payment-service] (retry 2) Connection timeout");
        assertThat(e).isPresent();
        assertThat(e.get().getSource()).isEqualTo("payment-service");
        assertThat(e.get().getMessage()).isEqualTo("(retry 2) Connection timeout");
    }

    @Test
    void shouldAttachCausedByLinesOfWildFlyErrors() {
        String log = "2026-09-18 10:41:55,211 ERROR [org.jboss.jca.core.connectionmanager.listener.TxConnectionListener] (t) IJ000453: Unable to get managed connection\n"
                + "Caused by: java.net.ConnectException: Connection refused\n";
        List<LogEntry> entries = parser.parseStream(text(log));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getStackTrace()).contains("ConnectException");
    }

    @Test
    void shouldParseMavenAndJenkinsOutputAsCiLayer() {
        String log = "[Pipeline] stage\n"
                + "[2026-09-18T09:59:12.008Z] [ERROR] Tests run: 48, Failures: 2, Errors: 1, Skipped: 0\n"
                + "[2026-09-18T09:59:12.402Z] [INFO] BUILD FAILURE\n"
                + "Finished: FAILURE\n"
                + "some unrelated free text\n";
        LogParserService.ParseResult result = parser.parseWithStats(text(log));
        assertThat(result.entries()).hasSize(4);
        assertThat(result.entries()).allMatch(e -> LogTypeClassifier.CICD.equals(e.getLogType()));
        assertThat(result.entries().get(1).getLevel()).isEqualTo(LogEntry.LogLevel.ERROR);
        assertThat(result.entries().get(3).getMessage()).isEqualTo("Finished: FAILURE");
        assertThat(result.entries().get(3).getLevel()).isEqualTo(LogEntry.LogLevel.ERROR);
        assertThat(result.skippedLines()).isEqualTo(1);
    }

    @Test
    void shouldKeepEveryLineInForcedCiMode() {
        String log = "+ mvn -B verify\nnpm WARN deprecated left-pad\nerror: cannot compile module\n";
        LogParserService.ParseResult result = parser.parseWithStats(text(log), LogTypeClassifier.CICD);
        assertThat(result.entries()).hasSize(3);
        assertThat(result.skippedLines()).isEqualTo(0);
        assertThat(result.entries().get(1).getLevel()).isEqualTo(LogEntry.LogLevel.WARN);
        assertThat(result.entries().get(2).getLevel()).isEqualTo(LogEntry.LogLevel.ERROR);
        // l'ordre des lignes est conservé par les horodatages
        assertThat(result.entries().get(0).getTimestamp().isBefore(result.entries().get(2).getTimestamp())).isTrue();
    }

    @Test
    void shouldAnchorUntimestampedPipelineLinesOnTheirNeighbours() {
        String log = "[Pipeline] Start of Pipeline\n"
                + "[2026-09-18T09:59:12.008Z] [ERROR] Tests run: 48, Failures: 2, Errors: 1, Skipped: 0\n"
                + "[2026-09-18T09:59:12.402Z] [INFO] BUILD FAILURE\n"
                + "Finished: FAILURE\n";
        List<LogEntry> e = parser.parseWithStats(text(log), LogTypeClassifier.CICD).entries();
        assertThat(e).hasSize(4);
        // la ligne finale suit la dernière ligne horodatée, la première la précède : même jour, ordre conservé
        assertThat(e.get(3).getTimestamp().isAfter(e.get(2).getTimestamp())).isTrue();
        assertThat(e.get(0).getTimestamp().isBefore(e.get(1).getTimestamp())).isTrue();
        assertThat(java.time.Duration.between(e.get(0).getTimestamp(), e.get(3).getTimestamp()).toMinutes()).isLessThan(1L);
    }

    @Test
    void shouldClassifyBySourceAndMessage() {
        assertThat(LogTypeClassifier.classify("jenkins", "anything")).isEqualTo(LogTypeClassifier.CICD);
        assertThat(LogTypeClassifier.classify("payment-service", "WFLYCTL0013: Operation failed")).isEqualTo(LogTypeClassifier.SERVER);
        assertThat(LogTypeClassifier.classify("org.jboss.as.server", "x")).isEqualTo(LogTypeClassifier.SERVER);
        assertThat(LogTypeClassifier.classify("payment-service", "Connection timeout")).isEqualTo(LogTypeClassifier.APPLICATION);
        assertThat(LogTypeClassifier.errorCode("Deploy failed: WFLYCTL0180: Services with missing")).contains("WFLYCTL0180");
    }

    @Test
    void normalizationShouldPreserveServerErrorCodes() {
        String a = LogAnalysisService.normalize("WFLYCTL0013: Operation (\"deploy\") failed for deployment 42");
        String b = LogAnalysisService.normalize("WFLYCTL0180: Services with missing dependencies 42");
        assertThat(a).isEqualTo("WFLYCTL0013: Operation (\"deploy\") failed for deployment #");
        assertThat(b).startsWith("WFLYCTL0180");
        assertThat(LogAnalysisService.normalize("ORA-00942: table or view does not exist on node 3"))
                .isEqualTo("ORA-00942: table or view does not exist on node #");
        // comportement historique inchangé pour les messages sans code
        assertThat(LogAnalysisService.normalize("Timeout after 3000ms for order 1234")).isEqualTo("Timeout after #ms for order #");
    }

    @Test
    void shouldParseBundledWildFlyDemoLog() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("demo/wildfly-server.log")) {
            LogParserService.ParseResult r = parser.parseWithStats(in);
            assertThat(r.skippedLines()).isEqualTo(0);
            assertThat(r.entries()).anyMatch(e -> LogTypeClassifier.SERVER.equals(e.getLogType()) && e.getLevel().isErrorLike());
            assertThat(r.entries()).anyMatch(e -> LogTypeClassifier.APPLICATION.equals(e.getLogType()) && e.getLevel().isErrorLike());
        }
    }
}
