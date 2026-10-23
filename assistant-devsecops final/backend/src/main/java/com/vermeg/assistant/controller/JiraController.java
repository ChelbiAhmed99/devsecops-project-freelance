/**
 * Role : exposer l'integration Jira controlee (rapprochement, propositions, validation).
 * Contenu : definition de JiraController et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.JiraModels;
import com.vermeg.assistant.service.jira.JiraService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Intégration Jira.
 * <ul>
 *   <li>GET  /api/jira/status                  -> mode LIVE ou SIMULATION, quatre yeux</li>
 *   <li>POST /api/jira/matches                 -> tickets correspondant à un incident (score + raison)</li>
 *   <li>POST /api/jira/draft?type=&issueKey=   -> brouillon pré-rempli (diagnostic + correction IA)</li>
 *   <li>POST /api/jira/actions                 -> proposition d'action (tout utilisateur)</li>
 *   <li>GET  /api/jira/actions                 -> historique (status, app)</li>
 *   <li>POST /api/jira/actions/{id}/approve    -> approbation + exécution (QM, ADMIN, quatre yeux)</li>
 *   <li>POST /api/jira/actions/{id}/reject     -> rejet motivé (QM, ADMIN)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/jira")
public class JiraController {

    private static final String MANAGERS = "hasAnyRole('QUALITY_MANAGER','ADMIN')";

    private final JiraService jiraService;

    public JiraController(JiraService jiraService) {
        this.jiraService = jiraService;
    }

    @GetMapping("/status")
    public JiraModels.JiraStatus status() {
        return jiraService.status();
    }

    @PostMapping("/matches")
    public List<JiraModels.JiraMatch> matches(@RequestBody JiraModels.IncidentQuery query) {
        return jiraService.matches(query);
    }

    @PostMapping("/draft")
    public JiraModels.ProposalRequest draft(@RequestBody JiraModels.IncidentQuery query,
                                            @RequestParam(defaultValue = "CREATE_ISSUE") String type,
                                            @RequestParam(required = false) String issueKey) {
        return jiraService.draft(query, type, issueKey);
    }

    @PostMapping("/actions")
    public JiraModels.JiraActionView propose(@RequestBody JiraModels.ProposalRequest request) {
        return jiraService.propose(request);
    }

    @GetMapping("/actions")
    public List<JiraModels.JiraActionView> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String app) {
        return jiraService.list(status, app);
    }

    @PostMapping("/actions/{id}/approve")
    @PreAuthorize(MANAGERS)
    public JiraModels.JiraActionView approve(@PathVariable Long id, @RequestBody(required = false) JiraModels.DecisionRequest decision) {
        return jiraService.approve(id, decision);
    }

    @PostMapping("/actions/{id}/reject")
    @PreAuthorize(MANAGERS)
    public JiraModels.JiraActionView reject(@PathVariable Long id, @RequestBody JiraModels.DecisionRequest decision) {
        return jiraService.reject(id, decision);
    }
}
