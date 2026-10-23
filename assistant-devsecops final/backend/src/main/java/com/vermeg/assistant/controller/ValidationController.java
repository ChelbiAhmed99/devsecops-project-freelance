/**
 * Role : exposer la validation multi-applications, les rapports par application et la decision humaine.
 * Contenu : definition de ValidationController et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.service.validation.ValidationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Validation multi-applications.
 * <ul>
 *   <li>POST /api/validations/campaigns                    -> lance une campagne sur N applications (202)</li>
 *   <li>GET  /api/validations/campaigns                    -> campagnes récentes et leurs résultats</li>
 *   <li>GET  /api/validations/campaigns/{id}               -> une campagne (un résultat par application)</li>
 *   <li>GET  /api/validations/campaigns/{id}/reports.zip   -> les N rapports distincts + synthèse</li>
 *   <li>GET  /api/validations/portfolio                    -> tableau de bord récapitulatif (dernière validation par application)</li>
 *   <li>GET  /api/validations/{id}                         -> détail d'une validation (rapport structuré)</li>
 *   <li>GET  /api/validations/{id}/report.md | report.html -> rapport indépendant de l'application</li>
 *   <li>GET  /api/validations/history?app=...              -> historique de validation d'une application</li>
 *   <li>POST /api/validations/{id}/decision                -> confirmation ou correction humaine (QM, ADMIN)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/validations")
public class ValidationController {

    private static final String MANAGERS = "hasAnyRole('QUALITY_MANAGER','ADMIN')";

    private final ValidationService validationService;

    public ValidationController(ValidationService validationService) {
        this.validationService = validationService;
    }

    @PostMapping("/campaigns")
    public ResponseEntity<ApiDtos.ValidationCampaignView> start(@RequestBody ApiDtos.ValidationRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(validationService.start(request));
    }

    @GetMapping("/campaigns")
    public List<ApiDtos.ValidationCampaignView> campaigns(@RequestParam(defaultValue = "20") int limit) {
        return validationService.campaigns(limit);
    }

    @GetMapping("/campaigns/{id}")
    public ApiDtos.ValidationCampaignView campaign(@PathVariable Long id) {
        return validationService.campaign(id);
    }

    @GetMapping("/campaigns/{id}/reports.zip")
    public ResponseEntity<byte[]> zip(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"rapports-campagne-" + id + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(validationService.campaignZip(id));
    }

    @GetMapping("/portfolio")
    public ApiDtos.PortfolioSummary portfolio() {
        return validationService.portfolio();
    }

    @GetMapping("/history")
    public List<ApiDtos.ApplicationValidationSummary> history(@RequestParam String app,
                                                              @RequestParam(defaultValue = "20") int limit) {
        return validationService.history(app, limit);
    }

    @GetMapping("/{id}")
    public ApiDtos.ApplicationValidationDetail detail(@PathVariable Long id) {
        return validationService.detail(id);
    }

    @GetMapping("/{id}/report.md")
    public ResponseEntity<byte[]> markdown(@PathVariable Long id) {
        return download(validationService.markdown(id), validationService.fileBaseName(id) + ".md", "text/markdown");
    }

    @GetMapping("/{id}/report.html")
    public ResponseEntity<byte[]> html(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean download) {
        if (!download) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .body(validationService.html(id).getBytes(StandardCharsets.UTF_8));
        }
        return download(validationService.html(id), validationService.fileBaseName(id) + ".html", "text/html");
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize(MANAGERS)
    public ApiDtos.ApplicationValidationSummary decide(@PathVariable Long id,
                                                       @RequestBody ApiDtos.ValidationDecisionRequest request) {
        return validationService.decide(id, request);
    }

    private ResponseEntity<byte[]> download(String body, String filename, String type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(type + ";charset=UTF-8"))
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
