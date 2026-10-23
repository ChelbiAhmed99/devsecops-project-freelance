/**
 * Role : exposer les endpoints REST lies aux etat et informations systeme et deleguer leur traitement aux services.
 * Contenu : definition de SystemController et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.controller;

import com.vermeg.assistant.service.ToolStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostic des prérequis : GET /api/system/tools indique si Maven, Trivy, le
 * démon Docker, SonarQube et les clés d'API sont disponibles côté serveur.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ToolStatusService toolStatusService;

    public SystemController(ToolStatusService toolStatusService) {
        this.toolStatusService = toolStatusService;
    }

    @GetMapping("/tools")
    public Map<String, Object> tools(@RequestParam(defaultValue = "false") boolean refresh) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tools", toolStatusService.status(refresh));
        body.put("defaultImage", toolStatusService.defaultImage());
        return body;
    }
}
