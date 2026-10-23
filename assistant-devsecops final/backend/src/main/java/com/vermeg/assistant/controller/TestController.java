/**
 * Role : exposer les endpoints REST lies aux tests et resultats de tests et deleguer leur traitement aux services.
 * Contenu : definition de TestController et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.CorrelatedFailure;
import com.vermeg.assistant.model.TestExecutionSummary;
import com.vermeg.assistant.service.JobService;
import com.vermeg.assistant.service.TestOrchestrationService;
import com.vermeg.assistant.service.TestRunHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Brique "tests automatisés".
 * <ul>
 *   <li>POST /api/tests/run                  -> lance "mvn test" en tâche de fond (202 + job)</li>
 *   <li>POST /api/tests/import               -> import de rapports JUnit XML (+ jacoco.xml)</li>
 *   <li>GET  /api/tests/results              -> dernière exécution consolidée (+ couverture)</li>
 *   <li>GET  /api/tests/runs/{id}            -> une exécution de l'historique</li>
 *   <li>GET  /api/tests/history              -> tendance des dernières exécutions</li>
 *   <li>GET  /api/tests/flaky                -> tests instables</li>
 *   <li>GET  /api/tests/failures/correlated  -> échecs corrélés aux logs (runId optionnel)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tests")
public class TestController {

    private final TestOrchestrationService testService;
    private final TestRunHistoryService historyService;

    public TestController(TestOrchestrationService testService, TestRunHistoryService historyService) {
        this.testService = testService;
        this.historyService = historyService;
    }

    @PostMapping("/run")
    public ResponseEntity<JobService.Job> runTests() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(testService.runAsync());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TestExecutionSummary importReports(@RequestPart("reports") List<MultipartFile> reports,
                                              @RequestPart(value = "coverage", required = false) MultipartFile coverage,
                                              @RequestParam(required = false) String branch,
                                              @RequestParam(required = false) String commit) throws Exception {
        return testService.importReports(reports, coverage,
                new TestRunHistoryService.RunMetadata("IMPORT", branch, commit, null, null, null, null, null));
    }

    @GetMapping("/results")
    public TestExecutionSummary getResults() {
        return testService.currentResults();
    }

    @GetMapping("/runs/{id}")
    public TestExecutionSummary getRun(@PathVariable Long id) {
        return historyService.byId(id).orElseThrow(() -> new NoSuchElementException("Exécution introuvable : " + id));
    }

    @GetMapping("/history")
    public List<ApiDtos.TestRunPoint> history(@RequestParam(defaultValue = "20") int limit) {
        return historyService.history(limit);
    }

    @GetMapping("/flaky")
    public List<ApiDtos.FlakyTest> flaky() {
        return historyService.flakyTests();
    }

    @GetMapping("/failures/correlated")
    public List<CorrelatedFailure> getCorrelatedFailures(@RequestParam(required = false) Long runId) {
        return testService.correlatedFailures(runId);
    }
}
