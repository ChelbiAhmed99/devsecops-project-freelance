package com.vermeg.assistant.service;

import com.vermeg.assistant.domain.AuditEvent;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.repository.AuditEventRepository;
import com.vermeg.assistant.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** Trace les actions sensibles (connexion, changement de statut, validation IA, scans...). */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(String action, String target, String details) {
        recordAs(CurrentUser.username(), action, target, details);
    }

    public void recordAs(String username, String action, String target, String details) {
        try {
            repository.save(new AuditEvent(username, action, target, details));
        } catch (RuntimeException e) {
            // L'audit ne doit jamais faire échouer l'action métier elle-même.
            log.warn("Impossible d'enregistrer l'événement d'audit {} : {}", action, e.getMessage());
        }
    }

    public List<ApiDtos.AuditView> latest(int limit) {
        return repository.findAllByOrderByAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 1000))))
                .stream()
                .map(e -> new ApiDtos.AuditView(e.getId(), e.getAt(), e.getUsername(), e.getAction(),
                        e.getTarget(), e.getDetails()))
                .toList();
    }
}
