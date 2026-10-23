/**
 * Role : representer et persister une application surveillee et validee par l'assistant.
 * Contenu : definition de ManagedApplication et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.domain;

import com.vermeg.assistant.model.ApplicationCriticality;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application du portefeuille surveillée par l'assistant (multi-applications).
 * <p>
 * Chaque application possède ses propres résultats de tests, logs, vulnérabilités,
 * validations et rapports. Le code (identifiant court et stable, ex. "portail-paiement")
 * sert de clé de rattachement dans toutes les autres tables.
 */
@Entity
@Table(name = "managed_application", uniqueConstraints = @UniqueConstraint(name = "uk_app_code", columnNames = "code"))
public class ManagedApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(length = 80)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationCriticality criticality = ApplicationCriticality.MEDIUM;

    /** Type de serveur d'exécution : WILDFLY, JBOSS_EAP, SPRING_BOOT, TOMCAT, AUTRE. */
    @Column(length = 40)
    private String serverType;

    /** Dossier du projet Maven (exécution de « mvn test » depuis l'assistant). */
    @Column(length = 500)
    private String projectPath;

    @Column(length = 500)
    private String reportsPath;

    @Column(length = 500)
    private String coveragePath;

    /** Sources de logs rattachées à l'application (séparées par des virgules). */
    @Column(length = 1000)
    private String logSources;

    @Column(length = 40)
    private String jiraProjectKey;

    @Column(length = 500)
    private String repositoryUrl;

    /** Surcharges de la politique de validation (JSON : seuils, poids, règles bloquantes). */
    @Column(length = 4000)
    private String policyOverrides;

    @Column(nullable = false)
    private boolean active = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ManagedApplication() {
    }

    public ManagedApplication(String code, String name) {
        this.code = normalizeCode(code);
        this.name = name;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** Code normalisé : minuscules, chiffres et tirets uniquement (utilisable dans une URL ou un nom de fichier). */
    public static String normalizeCode(String raw) {
        if (raw == null) return null;
        String code = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return code.length() > 60 ? code.substring(0, 60) : code;
    }

    /** Sources de logs sous forme d'ensemble (vide si non renseigné). */
    public Set<String> logSourceSet() {
        if (logSources == null || logSources.isBlank()) return Set.of();
        return Arrays.stream(logSources.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = normalizeCode(code); }
    public String getName() { return name; }
    public void setName(String name) { this.name = VulnerabilityRecord.truncate(name, 120); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = VulnerabilityRecord.truncate(description, 1000); }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = VulnerabilityRecord.truncate(owner, 80); }
    public ApplicationCriticality getCriticality() { return criticality; }
    public void setCriticality(ApplicationCriticality criticality) {
        this.criticality = criticality == null ? ApplicationCriticality.MEDIUM : criticality;
    }
    public String getServerType() { return serverType; }
    public void setServerType(String serverType) { this.serverType = VulnerabilityRecord.truncate(serverType, 40); }
    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = VulnerabilityRecord.truncate(projectPath, 500); }
    public String getReportsPath() { return reportsPath; }
    public void setReportsPath(String reportsPath) { this.reportsPath = VulnerabilityRecord.truncate(reportsPath, 500); }
    public String getCoveragePath() { return coveragePath; }
    public void setCoveragePath(String coveragePath) { this.coveragePath = VulnerabilityRecord.truncate(coveragePath, 500); }
    public String getLogSources() { return logSources; }
    public void setLogSources(String logSources) { this.logSources = VulnerabilityRecord.truncate(logSources, 1000); }
    public String getJiraProjectKey() { return jiraProjectKey; }
    public void setJiraProjectKey(String jiraProjectKey) {
        this.jiraProjectKey = jiraProjectKey == null || jiraProjectKey.isBlank() ? null
                : VulnerabilityRecord.truncate(jiraProjectKey.trim().toUpperCase(Locale.ROOT), 40);
    }
    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = VulnerabilityRecord.truncate(repositoryUrl, 500); }
    public String getPolicyOverrides() { return policyOverrides; }
    public void setPolicyOverrides(String policyOverrides) { this.policyOverrides = VulnerabilityRecord.truncate(policyOverrides, 4000); }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = LocalDateTime.now(); }
}
