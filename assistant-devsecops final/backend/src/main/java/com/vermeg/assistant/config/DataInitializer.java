/**
 * Role : preparer les donnees initiales et les comptes de demonstration au demarrage.
 * Contenu : definition de DataInitializer et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.config;

import com.vermeg.assistant.domain.AppUser;
import com.vermeg.assistant.domain.ManagedApplication;
import com.vermeg.assistant.model.ApplicationCriticality;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.repository.AppUserRepository;
import com.vermeg.assistant.repository.TestRunRepository;
import com.vermeg.assistant.repository.VulnerabilityRecordRepository;
import com.vermeg.assistant.security.Role;
import com.vermeg.assistant.service.ApplicationService;
import com.vermeg.assistant.service.DependencyCheckReportParser;
import com.vermeg.assistant.service.LegacySchemaMigration;
import com.vermeg.assistant.service.LogParserService;
import com.vermeg.assistant.service.LogStore;
import com.vermeg.assistant.service.LogTypeClassifier;
import com.vermeg.assistant.service.SurefireReportParser;
import com.vermeg.assistant.service.TestRunHistoryService;
import com.vermeg.assistant.service.TrivyReportMapper;
import com.vermeg.assistant.service.VulnerabilityService;
import com.vermeg.assistant.service.JacocoReportParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Initialisation au démarrage :
 * - comptes par défaut si la table des utilisateurs est vide ;
 * - application par défaut et migration des données mono-projet vers le multi-applications ;
 * - en mode démo (assistant.demo.enabled) et base vide : logs, vulnérabilités
 *   et une exécution de tests d'exemple, pour un tableau de bord immédiatement parlant ;
 * - en mode démo, trois applications d'exemple aux profils contrastés (WildFly, CI, critique)
 *   pour démontrer la validation multi-applications et les rapports par application.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LogStore logStore;
    private final LogParserService logParser;
    private final VulnerabilityRecordRepository vulnerabilityRepository;
    private final VulnerabilityService vulnerabilityService;
    private final DependencyCheckReportParser dependencyParser;
    private final TestRunRepository testRunRepository;
    private final TestRunHistoryService testRunHistoryService;
    private final SurefireReportParser surefireParser;
    private final ApplicationService applicationService;
    private final LegacySchemaMigration schemaMigration;

    @Value("${assistant.demo.enabled:true}")
    private boolean demoEnabled;
    @Value("${assistant.bootstrap.admin-password:Admin@2026}")
    private String adminPassword;
    @Value("${assistant.bootstrap.quality-password:Qualite@2026}")
    private String qualityPassword;
    @Value("${assistant.bootstrap.developer-password:Dev@2026}")
    private String developerPassword;

    public DataInitializer(AppUserRepository userRepository, PasswordEncoder passwordEncoder, LogStore logStore,
                           LogParserService logParser, VulnerabilityRecordRepository vulnerabilityRepository,
                           VulnerabilityService vulnerabilityService, DependencyCheckReportParser dependencyParser,
                           TestRunRepository testRunRepository, TestRunHistoryService testRunHistoryService,
                           SurefireReportParser surefireParser, ApplicationService applicationService,
                           LegacySchemaMigration schemaMigration) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.logStore = logStore;
        this.logParser = logParser;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.vulnerabilityService = vulnerabilityService;
        this.dependencyParser = dependencyParser;
        this.testRunRepository = testRunRepository;
        this.testRunHistoryService = testRunHistoryService;
        this.surefireParser = surefireParser;
        this.applicationService = applicationService;
        this.schemaMigration = schemaMigration;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedUsers();
        prepareApplications();
        if (demoEnabled) {
            seedDemoData();
            seedDemoApplications();
        }
        // En dernier : rattache aussi les données de démonstration historiques chargées ci-dessus.
        try {
            applicationService.migrateLegacyData();
        } catch (RuntimeException e) {
            log.warn("Migration des données vers l'application par défaut incomplète : {}", e.getMessage());
        }
    }

    /** Multi-applications : application par défaut + remplacement de l'ancienne contrainte d'unicité. */
    private void prepareApplications() {
        applicationService.ensureDefault();
        try {
            schemaMigration.dropLegacyVulnerabilityConstraint();
        } catch (RuntimeException e) {
            log.debug("Ancienne contrainte uk_vuln absente ou déjà supprimée : {}", e.getMessage());
        }
    }

    /**
     * Quatre applications de démonstration (une seule fois, si seule l'application par défaut existe),
     * aux profils contrastés pour couvrir toutes les décisions possibles :
     * referentiel-clients (saine), back-office-titres (WildFly, incident de datasource, nouveaux échecs),
     * api-reporting (pipeline Jenkins en échec, pas de scan de sécurité) et portail-paiement
     * (critique, vulnérabilités critiques bloquantes).
     */
    private void seedDemoApplications() {
        if (applicationService.count() > 1) return;
        try {
            ManagedApplication titres = demoApp("back-office-titres", "Back-office Titres", ApplicationCriticality.HIGH, "WILDFLY",
                    "Gestion des ordres et des positions titres, déployée sur WildFly 31.", "Équipe Titres", "TITRES");
            ManagedApplication reporting = demoApp("api-reporting", "API Reporting", ApplicationCriticality.MEDIUM, "SPRING_BOOT",
                    "Exports réglementaires et reporting quotidien, intégrée par Jenkins.", "Équipe Data", "REPORT");
            ManagedApplication paiement = demoApp("portail-paiement", "Portail de paiement", ApplicationCriticality.CRITICAL, "SPRING_BOOT",
                    "Autorisation et routage des paiements par carte.", "Équipe Paiements", "PAY");
            paiement.setLogSources("payment-service,payment-gateway,auth-service");
            applicationService.saveEntity(paiement);
            ManagedApplication referentiel = demoApp("referentiel-clients", "Référentiel clients", ApplicationCriticality.LOW, "SPRING_BOOT",
                    "Données de référence clients (KYC, adresses).", "Équipe Référentiels", "REF");

            demoRuns(titres, List.of("demo/apps/back-office-titres-run1.xml", "demo/apps/back-office-titres-run2.xml"), 64.2, 51.0);
            demoRuns(reporting, List.of("demo/apps/api-reporting-run1.xml", "demo/apps/api-reporting-run2.xml"), 46.9, 38.5);
            demoRuns(paiement, List.of("demo/apps/portail-paiement-run1.xml", "demo/apps/portail-paiement-run2.xml"), 72.4, 61.0);
            demoRuns(referentiel, List.of("demo/apps/referentiel-clients-run1.xml", "demo/apps/referentiel-clients-run2.xml"), 78.3, 66.1);

            demoLogs(titres, "demo/wildfly-server.log", null, null);
            demoLogs(reporting, "demo/jenkins-build.log", LogTypeClassifier.CICD, "jenkins");
            demoLogs(paiement, "demo/apps/paiement.log", null, null);
            demoLogs(referentiel, "demo/apps/referentiel.log", null, null);

            vulnerabilityService.ingest(titres.getCode(), "trivy", TrivyReportMapper.parse(read("demo/apps/titres-trivy.json")));
            vulnerabilityService.ingest(referentiel.getCode(), "trivy", TrivyReportMapper.parse(read("demo/apps/referentiel-trivy.json")));
            vulnerabilityService.ingest(paiement.getCode(), "dependency-check",
                    dependencyParser.parseJson(read("demo/dependency-check-report.json")));
            log.info("Mode démo : 4 applications d'exemple créées (referentiel-clients, back-office-titres, api-reporting, portail-paiement)");
        } catch (Exception e) {
            log.warn("Chargement des applications de démonstration incomplet : {}", e.getMessage());
        }
    }

    private ManagedApplication demoApp(String code, String name, ApplicationCriticality criticality, String serverType,
                                       String description, String owner, String jiraKey) {
        ManagedApplication app = new ManagedApplication(code, name);
        app.setCriticality(criticality);
        app.setServerType(serverType);
        app.setDescription(description);
        app.setOwner(owner);
        app.setJiraProjectKey(jiraKey);
        return applicationService.saveEntity(app);
    }

    private void demoRuns(ManagedApplication app, List<String> files, double line, double branch) throws Exception {
        for (String file : files) {
            try (InputStream in = new ClassPathResource(file).getInputStream()) {
                testRunHistoryService.record(surefireParser.parse(in), new JacocoReportParser.Coverage(line, branch),
                        new TestRunHistoryService.RunMetadata("DEMO", "main", null, null, null, null, 0, null, app.getCode()));
            }
        }
    }

    private void demoLogs(ManagedApplication app, String file, String logType, String source) throws Exception {
        List<LogEntry> entries;
        try (InputStream in = new ClassPathResource(file).getInputStream()) {
            entries = new ArrayList<>(logParser.parseWithStats(in, logType).entries());
        }
        LocalDateTime latest = entries.stream().map(LogEntry::getTimestamp).max(LocalDateTime::compareTo).orElse(LocalDateTime.now());
        Duration shift = Duration.between(latest, LocalDateTime.now().minusMinutes(10));
        for (LogEntry e : entries) {
            e.setTimestamp(e.getTimestamp().plus(shift));
            e.setApplication(app.getCode());
            if (source != null) e.setSource(source);
            if (e.getLogType() == null) e.setLogType(LogTypeClassifier.classify(e.getSource(), e.getMessage()));
        }
        logStore.addAll(entries);
    }

    private void seedUsers() {
        if (userRepository.count() > 0) return;
        userRepository.save(new AppUser("admin", passwordEncoder.encode(adminPassword), "Administrateur",
                "admin@vermeg.local", Role.ADMIN));
        userRepository.save(new AppUser("qualite", passwordEncoder.encode(qualityPassword), "Responsable Qualité",
                "qualite@vermeg.local", Role.QUALITY_MANAGER));
        userRepository.save(new AppUser("dev", passwordEncoder.encode(developerPassword), "Développeur",
                "dev@vermeg.local", Role.DEVELOPER));
        log.warn("Comptes initiaux créés (admin / qualite / dev). Changez leurs mots de passe depuis l'écran Administration.");
    }

    private void seedDemoData() {
        try {
            if (logStore.size() == 0) {
                List<LogEntry> demo = new ArrayList<>(logParser.parseSampleLogFile());
                try (InputStream in = new ClassPathResource("demo/incident.log").getInputStream()) {
                    demo.addAll(logParser.parseStream(in));
                }
                // Décale les horodatages pour que l'incident de démonstration soit "récent"
                LocalDateTime latest = demo.stream().map(LogEntry::getTimestamp).max(LocalDateTime::compareTo)
                        .orElse(LocalDateTime.now());
                Duration shift = Duration.between(latest, LocalDateTime.now().minusMinutes(5));
                demo.forEach(e -> e.setTimestamp(e.getTimestamp().plus(shift)));
                logStore.addAll(demo);
                log.info("Mode démo : logs d'exemple chargés ({} entrées)", logStore.size());
            }
            if (vulnerabilityRepository.count() == 0) {
                String depJson = read("demo/dependency-check-report.json");
                vulnerabilityService.ingest("dependency-check", dependencyParser.parseJson(depJson));
                vulnerabilityService.ingest("trivy", TrivyReportMapper.parse(read("demo/trivy-report.json")));
                log.info("Mode démo : vulnérabilités d'exemple chargées");
            }
            if (testRunRepository.count() == 0) {
                for (String file : List.of("demo/surefire-run1.xml", "demo/surefire-run2.xml", "demo/surefire-run3.xml")) {
                    try (InputStream in = new ClassPathResource(file).getInputStream()) {
                        List<SuiteResult> suites = surefireParser.parse(in);
                        testRunHistoryService.record(suites, new JacocoReportParser.Coverage(61.4, 48.2),
                                new TestRunHistoryService.RunMetadata("DEMO", "main", null, null, null, null, 0, null));
                    }
                }
                log.info("Mode démo : historique de tests d'exemple chargé");
            }
        } catch (Exception e) {
            log.warn("Chargement des données de démonstration incomplet : {}", e.getMessage());
        }
    }

    private String read(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
