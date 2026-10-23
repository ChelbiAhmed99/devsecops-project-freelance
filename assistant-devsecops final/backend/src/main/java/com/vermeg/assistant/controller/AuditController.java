/**
 * Role : exposer les endpoints REST lies aux consultation de l audit et deleguer leur traitement aux services.
 * Contenu : definition de AuditController et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Journal d'audit (ADMIN, QUALITY_MANAGER). */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public List<ApiDtos.AuditView> latest(@RequestParam(defaultValue = "200") int limit) {
        return auditService.latest(limit);
    }
}
