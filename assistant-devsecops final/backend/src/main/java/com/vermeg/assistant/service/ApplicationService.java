/**
 * Role : gerer le portefeuille d'applications surveillees (multi-applications).
 * Contenu : definition de ApplicationService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.domain.ManagedApplication;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.ApplicationCriticality;
import com.vermeg.assistant.repository.LogRecordRepository;
import com.vermeg.assistant.repository.ManagedApplicationRepository;
import com.vermeg.assistant.repository.TestRunRepository;
import com.vermeg.assistant.repository.VulnerabilityRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Portefeuille d'applications. Point unique pour :
 * - créer / modifier / désactiver une application (jamais de suppression physique : traçabilité) ;
 * - résoudre le code d'application d'une donnée entrante (paramètre explicite, source de log
 *   déclarée, ou application par défaut) ;
 * - migrer les données historiques (mono-projet) vers l'application par défaut.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);
    private static final Set<String> SERVER_TYPES = Set.of("WILDFLY", "JBOSS_EAP", "SPRING_BOOT", "TOMCAT", "AUTRE");

    private final ManagedApplicationRepository repository;
    private final TestRunRepository testRunRepository;
    private final LogRecordRepository logRecordRepository;
    private final VulnerabilityRecordRepository vulnerabilityRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Value("${assistant.applications.default-code:assistant-devsecops}")
    private String defaultCode = "assistant-devsecops";

    @Value("${assistant.applications.default-name:Assistant DevSecOps (auto-analyse)}")
    private String defaultName = "Assistant DevSecOps (auto-analyse)";

    @Value("${assistant.applications.auto-register:true}")
    private boolean autoRegister = true;

    @Value("${assistant.tests.project-path:.}")
    private String defaultProjectPath = ".";

    /** Cache "source de log -> application", invalidé à chaque modification. */
    private volatile Map<String, String> sourceIndex;

    public ApplicationService(ManagedApplicationRepository repository, TestRunRepository testRunRepository,
                              LogRecordRepository logRecordRepository,
                              VulnerabilityRecordRepository vulnerabilityRepository, AuditService auditService,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.testRunRepository = testRunRepository;
        this.logRecordRepository = logRecordRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public String defaultCode() {
        return defaultCode;
    }

    // ------------------------------------------------------------------ lecture

    @Transactional(readOnly = true)
    public List<ApiDtos.ApplicationView> list(boolean includeInactive) {
        List<ManagedApplication> apps = includeInactive ? repository.findAllByOrderByNameAsc()
                : repository.findByActiveTrueOrderByNameAsc();
        return apps.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<ManagedApplication> activeEntities() {
        return repository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<ManagedApplication> find(String code) {
        String normalized = ManagedApplication.normalizeCode(code);
        return normalized == null || normalized.isEmpty() ? Optional.empty() : repository.findByCode(normalized);
    }

    public ManagedApplication require(String code) {
        return find(code).orElseThrow(() -> new NoSuchElementException("Application inconnue : " + code));
    }

    @Transactional(readOnly = true)
    public ApiDtos.ApplicationView view(String code) {
        return toView(require(code));
    }

    /**
     * Code d'application à utiliser pour une donnée entrante. Ordre de résolution :
     * code explicite (créé à la volée si l'auto-enregistrement est activé), puis
     * application déclarant la source de log, puis application par défaut.
     */
    @Transactional
    public String resolve(String explicitCode, String source) {
        if (explicitCode != null && !explicitCode.isBlank()) {
            String code = ManagedApplication.normalizeCode(explicitCode);
            if (code.isEmpty()) {
                throw new IllegalArgumentException("Code d'application invalide : " + explicitCode);
            }
            if (!repository.existsByCode(code)) {
                if (!autoRegister) {
                    throw new IllegalArgumentException("Application inconnue : " + code
                            + ". Déclarez-la dans l'écran Applications avant de publier des résultats.");
                }
                ManagedApplication app = new ManagedApplication(code, explicitCode.trim());
                repository.save(app);
                sourceIndex = null;
                auditService.record("APPLICATION_AUTO_REGISTERED", code, "créée automatiquement à la réception de données");
            }
            return code;
        }
        String bySource = resolveBySource(source);
        return bySource != null ? bySource : defaultCode;
    }

    /** Application déclarant cette source de log, ou null. */
    public String resolveBySource(String source) {
        if (source == null || source.isBlank()) return null;
        Map<String, String> index = sourceIndex;
        if (index == null) {
            index = new HashMap<>();
            for (ManagedApplication app : repository.findAll()) {
                if (!app.isActive()) continue;
                for (String s : app.logSourceSet()) index.putIfAbsent(s, app.getCode());
            }
            sourceIndex = index;
        }
        return index.get(source.trim());
    }

    // ---------------------------------------------------------------- écriture

    @Transactional
    public ApiDtos.ApplicationView create(ApiDtos.ApplicationUpsertRequest request) {
        if (request.code() == null || request.code().isBlank() || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Le code et le nom de l'application sont obligatoires");
        }
        String code = ManagedApplication.normalizeCode(request.code());
        if (code.isEmpty()) throw new IllegalArgumentException("Code d'application invalide");
        if (repository.existsByCode(code)) {
            throw new IllegalStateException("Une application avec le code « " + code + " » existe déjà");
        }
        ManagedApplication app = new ManagedApplication(code, request.name().trim());
        apply(app, request);
        repository.save(app);
        sourceIndex = null;
        auditService.record("APPLICATION_CREATED", code, app.getName() + " (" + app.getCriticality() + ")");
        return toView(app);
    }

    @Transactional
    public ApiDtos.ApplicationView update(String code, ApiDtos.ApplicationUpsertRequest request) {
        ManagedApplication app = require(code);
        if (request.name() != null && !request.name().isBlank()) app.setName(request.name().trim());
        apply(app, request);
        if (request.active() != null) {
            if (!request.active() && defaultCode.equals(app.getCode())) {
                throw new IllegalArgumentException("L'application par défaut ne peut pas être désactivée");
            }
            app.setActive(request.active());
        }
        app.touch();
        repository.save(app);
        sourceIndex = null;
        auditService.record("APPLICATION_UPDATED", app.getCode(), "criticité=" + app.getCriticality()
                + ", active=" + app.isActive());
        return toView(app);
    }

    private void apply(ManagedApplication app, ApiDtos.ApplicationUpsertRequest r) {
        if (r.description() != null) app.setDescription(blankToNull(r.description()));
        if (r.owner() != null) app.setOwner(blankToNull(r.owner()));
        if (r.criticality() != null) app.setCriticality(ApplicationCriticality.parse(r.criticality()));
        if (r.serverType() != null) {
            String type = r.serverType().trim().toUpperCase();
            if (!type.isEmpty() && !SERVER_TYPES.contains(type)) {
                throw new IllegalArgumentException("Type de serveur invalide. Valeurs : " + SERVER_TYPES);
            }
            app.setServerType(type.isEmpty() ? null : type);
        }
        if (r.projectPath() != null) app.setProjectPath(blankToNull(r.projectPath()));
        if (r.reportsPath() != null) app.setReportsPath(blankToNull(r.reportsPath()));
        if (r.coveragePath() != null) app.setCoveragePath(blankToNull(r.coveragePath()));
        if (r.logSources() != null) app.setLogSources(blankToNull(r.logSources()));
        if (r.jiraProjectKey() != null) app.setJiraProjectKey(r.jiraProjectKey());
        if (r.repositoryUrl() != null) app.setRepositoryUrl(blankToNull(r.repositoryUrl()));
        if (r.policyOverrides() != null) {
            String json = blankToNull(r.policyOverrides());
            if (json != null) {
                try {
                    JsonNode node = objectMapper.readTree(json);
                    if (!node.isObject()) throw new IllegalArgumentException("objet JSON attendu");
                } catch (Exception e) {
                    throw new IllegalArgumentException("Surcharges de politique invalides (JSON attendu) : " + e.getMessage());
                }
            }
            app.setPolicyOverrides(json);
        }
    }

    // ------------------------------------------------- initialisation/migration

    /** Crée l'application par défaut (l'assistant lui-même) si elle n'existe pas encore. */
    @Transactional
    public ManagedApplication ensureDefault() {
        return repository.findByCode(defaultCode).orElseGet(() -> {
            ManagedApplication app = new ManagedApplication(defaultCode, defaultName);
            app.setDescription("Application par défaut : regroupe les données publiées sans code d'application "
                    + "(compatibilité avec le fonctionnement mono-projet).");
            app.setCriticality(ApplicationCriticality.MEDIUM);
            app.setServerType("SPRING_BOOT");
            app.setProjectPath(defaultProjectPath);
            app.setLogSources("assistant-devsecops-backend");
            log.info("Application par défaut créée : {}", defaultCode);
            return repository.save(app);
        });
    }

    /** Rattache les données historiques (antérieures au multi-applications) à l'application par défaut. */
    @Transactional
    public int migrateLegacyData() {
        int runs = testRunRepository.assignOrphans(defaultCode);
        int logs = logRecordRepository.assignOrphans(defaultCode);
        int vulns = vulnerabilityRepository.assignOrphans(defaultCode);
        int total = runs + logs + vulns;
        if (total > 0) {
            log.info("Migration multi-applications : {} exécution(s), {} log(s), {} vulnérabilité(s) rattaché(e)s à {}",
                    runs, logs, vulns, defaultCode);
        }
        return total;
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    @Transactional
    public ManagedApplication saveEntity(ManagedApplication app) {
        sourceIndex = null;
        return repository.save(app);
    }

    public ApiDtos.ApplicationView toView(ManagedApplication a) {
        return new ApiDtos.ApplicationView(a.getId(), a.getCode(), a.getName(), a.getDescription(), a.getOwner(),
                a.getCriticality().name(), a.getServerType(), a.getProjectPath(), a.getReportsPath(),
                a.getCoveragePath(), a.getLogSources(), a.getJiraProjectKey(), a.getRepositoryUrl(),
                a.getPolicyOverrides(), a.isActive(), defaultCode.equals(a.getCode()), a.getCreatedAt(),
                a.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
