/**
 * Role : exposer les endpoints REST lies aux rapports et deleguer leur traitement aux services.
 * Contenu : definition de ReportController et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.controller;

import com.vermeg.assistant.service.ReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** Exports téléchargeables. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/vulnerabilities.csv")
    public ResponseEntity<byte[]> vulnerabilitiesCsv() {
        // BOM UTF-8 pour une ouverture correcte des accents dans Excel
        byte[] body = ("\uFEFF" + reportService.vulnerabilitiesCsv()).getBytes(StandardCharsets.UTF_8);
        return download(body, "vulnerabilites-" + LocalDate.now() + ".csv", "text/csv");
    }

    @GetMapping("/summary.md")
    public ResponseEntity<byte[]> summaryMarkdown() {
        return download(reportService.markdownSummary().getBytes(StandardCharsets.UTF_8),
                "rapport-devsecops-" + LocalDate.now() + ".md", "text/markdown");
    }

    private ResponseEntity<byte[]> download(byte[] body, String filename, String type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(type + ";charset=UTF-8"))
                .body(body);
    }
}
