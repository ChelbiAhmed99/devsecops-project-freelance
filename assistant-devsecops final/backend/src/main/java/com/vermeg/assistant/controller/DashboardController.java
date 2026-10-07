package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.service.DashboardService;
import com.vermeg.assistant.service.QualityGateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final QualityGateService qualityGateService;

    public DashboardController(DashboardService dashboardService, QualityGateService qualityGateService) {
        this.dashboardService = dashboardService;
        this.qualityGateService = qualityGateService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return dashboardService.overview();
    }

    @GetMapping("/quality-gate")
    public ApiDtos.QualityGateResult qualityGate() {
        return qualityGateService.evaluate();
    }
}
