/**
 * Role : exposer la gestion du portefeuille d'applications (multi-applications).
 * Contenu : definition de ApplicationController et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.service.ApplicationService;
import com.vermeg.assistant.service.QualityGateService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Portefeuille d'applications.
 * <ul>
 *   <li>GET  /api/applications                     -> liste (inactive=true pour inclure les désactivées)</li>
 *   <li>GET  /api/applications/{code}              -> détail</li>
 *   <li>GET  /api/applications/{code}/quality-gate -> porte qualité de l'application</li>
 *   <li>POST /api/applications                     -> création (QM, ADMIN)</li>
 *   <li>PUT  /api/applications/{code}              -> modification / désactivation (QM, ADMIN)</li>
 * </ul>
 * Pas de suppression physique : une application désactivée conserve son historique (traçabilité).
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private static final String MANAGERS = "hasAnyRole('QUALITY_MANAGER','ADMIN')";

    private final ApplicationService applicationService;
    private final QualityGateService qualityGateService;

    public ApplicationController(ApplicationService applicationService, QualityGateService qualityGateService) {
        this.applicationService = applicationService;
        this.qualityGateService = qualityGateService;
    }

    @GetMapping
    public List<ApiDtos.ApplicationView> list(@RequestParam(defaultValue = "false") boolean inactive) {
        return applicationService.list(inactive);
    }

    @GetMapping("/{code}")
    public ApiDtos.ApplicationView get(@PathVariable String code) {
        return applicationService.view(code);
    }

    @GetMapping("/{code}/quality-gate")
    public ApiDtos.QualityGateResult qualityGate(@PathVariable String code) {
        return qualityGateService.evaluate(code);
    }

    @PostMapping
    @PreAuthorize(MANAGERS)
    public ApiDtos.ApplicationView create(@RequestBody ApiDtos.ApplicationUpsertRequest request) {
        return applicationService.create(request);
    }

    @PutMapping("/{code}")
    @PreAuthorize(MANAGERS)
    public ApiDtos.ApplicationView update(@PathVariable String code, @RequestBody ApiDtos.ApplicationUpsertRequest request) {
        return applicationService.update(code, request);
    }
}
