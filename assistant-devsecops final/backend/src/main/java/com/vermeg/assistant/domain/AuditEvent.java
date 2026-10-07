package com.vermeg.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Journal d'audit : qui a fait quoi et quand (exigence forte en contexte financier). */
@Entity
@Table(name = "audit_event", indexes = @Index(name = "idx_audit_at", columnList = "event_at"))
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime at;

    @Column(length = 80)
    private String username;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(length = 300)
    private String target;

    @Column(length = 2000)
    private String details;

    public AuditEvent() {
    }

    public AuditEvent(String username, String action, String target, String details) {
        this.at = LocalDateTime.now();
        this.username = username;
        this.action = action;
        this.target = VulnerabilityRecord.truncate(target, 300);
        this.details = VulnerabilityRecord.truncate(details, 2000);
    }

    public Long getId() { return id; }
    public LocalDateTime getAt() { return at; }
    public String getUsername() { return username; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getDetails() { return details; }
}
