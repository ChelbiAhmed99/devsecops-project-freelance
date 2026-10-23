/**
 * Role : preparer les donnees initiales et les comptes de demonstration au demarrage.
 * Contenu : definition de DataInitializer et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.config;

import com.vermeg.assistant.domain.AppUser;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.repository.AppUserRepository;
import com.vermeg.assistant.repository.TestRunRepository;
import com.vermeg.assistant.repository.VulnerabilityRecordRepository;
import com.vermeg.assistant.security.Role;
import com.vermeg.assistant.service.DependencyCheckReportParser;
import com.vermeg.assistant.service.LogParserService;
import com.vermeg.assistant.service.LogStore;
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
 * - en mode démo (assistant.demo.enabled) et base vide : logs, vulnérabilités
 *   et une exécution de tests d'exemple, pour un tableau de bord immédiatement parlant.
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
                           SurefireReportParser surefireParser) {
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
    }

    @Override
    public void run(ApplicationArguments args) {
        seedUsers();
        if (demoEnabled) {
            seedDemoData();
        }
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
