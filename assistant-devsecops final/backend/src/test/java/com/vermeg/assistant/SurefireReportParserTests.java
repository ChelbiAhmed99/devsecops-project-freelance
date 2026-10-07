package com.vermeg.assistant;

import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.model.TestCaseResult;
import com.vermeg.assistant.service.SurefireReportParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires du parsing des rapports Surefire (Sprint 4).
 * On génère un faux rapport XML dans un dossier temporaire plutôt que de
 * dépendre d'une vraie exécution "mvn test" dans les tests eux-mêmes.
 */
class SurefireReportParserTests {

    private final SurefireReportParser parser = new SurefireReportParser();

    @Test
    void shouldReturnEmptyListWhenDirectoryDoesNotExist() {
        List<SuiteResult> results = parser.parseReportsDirectory("/chemin/qui/n/existe/pas");
        assertThat(results).isEmpty();
    }

    @Test
    void shouldParseSuiteWithPassedFailedAndSkippedTests(@TempDir Path tempDir) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.vermeg.assistant.ExampleTests" tests="3" failures="1" errors="0" skipped="1" time="0.456">
                    <testcase name="shouldPass" classname="com.vermeg.assistant.ExampleTests" time="0.1"/>
                    <testcase name="shouldFail" classname="com.vermeg.assistant.ExampleTests" time="0.2">
                        <failure message="expected true but was false">stacktrace...</failure>
                    </testcase>
                    <testcase name="shouldBeSkipped" classname="com.vermeg.assistant.ExampleTests" time="0.0">
                        <skipped/>
                    </testcase>
                </testsuite>
                """;

        Path xmlFile = tempDir.resolve("TEST-com.vermeg.assistant.ExampleTests.xml");
        Files.writeString(xmlFile, xml);

        List<SuiteResult> results = parser.parseReportsDirectory(tempDir.toString());

        assertThat(results).hasSize(1);
        SuiteResult suite = results.get(0);
        assertThat(suite.getSuiteName()).isEqualTo("com.vermeg.assistant.ExampleTests");
        assertThat(suite.getTests()).isEqualTo(3);
        assertThat(suite.getFailures()).isEqualTo(1);
        assertThat(suite.getSkipped()).isEqualTo(1);
        assertThat(suite.getTestCases()).hasSize(3);

        TestCaseResult failedCase = suite.getTestCases().stream()
                .filter(tc -> tc.getName().equals("shouldFail"))
                .findFirst().orElseThrow();
        assertThat(failedCase.getStatus()).isEqualTo(TestCaseResult.Status.FAILED);
        assertThat(failedCase.getFailureMessage()).contains("expected true");

        TestCaseResult passedCase = suite.getTestCases().stream()
                .filter(tc -> tc.getName().equals("shouldPass"))
                .findFirst().orElseThrow();
        assertThat(passedCase.getStatus()).isEqualTo(TestCaseResult.Status.PASSED);
    }
}
