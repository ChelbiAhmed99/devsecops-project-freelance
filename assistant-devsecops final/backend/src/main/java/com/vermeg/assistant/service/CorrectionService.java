/**
 * Role : suivre le cycle de vie des corrections proposees par l'IA et verifier leur effet sur les nouvelles donnees.
 * Contenu : definition de CorrectionService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.domain.LogRecord;
import com.vermeg.assistant.domain.ManagedApplication;
import com.vermeg.assistant.domain.TestRunRecord;
import com.vermeg.assistant.domain.VulnerabilityRecord;
import com.vermeg.assistant.model.AiRecommendation;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.LogEntry;
import com.vermeg.assistant.model.SuiteResult;
import com.vermeg.assistant.model.TestCaseResult;
import com.vermeg.assistant.repository.LogRecordRepository;
import com.vermeg.assistant.repository.VulnerabilityRecordRepository;
import com.vermeg.assistant.security.CurrentUser;
import com.vermeg.assistant.service.validation.ValidationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Corrections assistées par IA, sous contrôle humain de bout en bout :
 * <ol>
 *   <li>détection (anomalie, échec de test, vulnérabilité) et analyse d'origine (corrélation) ;</li>
 *   <li>proposition + explication par l'IA (fiche « À valider ») ;</li>
 *   <li>validation humaine (APPROVED / REJECTED) ;</li>
 *   <li>application par un humain, déclarée ici avec sa référence (commit, merge request, ticket) —
 *       <b>l'assistant ne modifie jamais le code ni la production</b> ;</li>
 *   <li>vérification automatique sur les données reçues APRÈS l'application : l'anomalie a-t-elle
 *       disparu des logs, le test passe-t-il, la vulnérabilité est-elle fermée ?</li>
 * </ol>
 */
@Service
public class CorrectionService {

    private static final Logger log = LoggerFactory.getLogger(CorrectionService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    /** Volume minimal de nouveaux logs pour conclure à la disparition d'une anomalie. */
    static final int MIN_NEW_LOGS = 20;

    private final AiRecommendationStore store;
    private final LogRecordRepository logRepository;
    private final VulnerabilityRecordRepository vulnerabilityRepository;
    private final TestRunHistoryService historyService;
    private final ApplicationService applicationService;
    private final AuditService auditService;
    private final EventService eventService;
    private final ValidationService validationService;

    public CorrectionService(AiRecommendationStore store, LogRecordRepository logRepository,
                             VulnerabilityRecordRepository vulnerabilityRepository, TestRunHistoryService historyService,
                             ApplicationService applicationService, AuditService auditService, EventService eventService,
                             ValidationService validationService) {
        this.store = store;
        this.logRepository = logRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.historyService = historyService;
        this.applicationService = applicationService;
        this.auditService = auditService;
        this.eventService = eventService;
        this.validationService = validationService;
    }

    /** Après chaque campagne de validation, les corrections en attente sont revérifiées. */
    @PostConstruct
    void registerCampaignListener() {
        validationService.onCampaignCompleted(id -> verifyPending());
    }

    /** Étape 5 : un humain déclare avoir appliqué une correction VALIDÉE. */
    public AiRecommendation markApplied(String id, ApiDtos.AppliedRequest request) {
        AiRecommendation r = store.findById(id).orElseThrow(() -> new NoSuchElementException("Recommandation introuvable : " + id));
        if (r.getStatus() != AiRecommendation.ValidationStatus.APPROVED) {
            throw new IllegalStateException("Seule une recommandation validée par un humain peut être déclarée appliquée");
        }
        if (request == null || request.reference() == null || request.reference().isBlank()) {
            throw new IllegalArgumentException("Indiquez la référence de la correction (commit, merge request, ticket...)");
        }
        r.setFixStatus(AiRecommendation.FixStatus.APPLIED);
        r.setAppliedBy(CurrentUser.username());
        r.setAppliedAt(LocalDateTime.now());
        r.setAppliedReference(request.reference().trim());
        r.setVerifiedAt(null);
        r.setVerificationDetails("Correction déclarée appliquée : en attente de nouvelles données (logs, tests, scans) pour vérification."
                + (request.comment() == null || request.comment().isBlank() ? "" : " Commentaire : " + request.comment().trim()));
        store.save(r);
        auditService.record("CORRECTION_APPLIED", id, r.getIncidentType() + " · réf. " + r.getAppliedReference());
        return r;
    }

    /** Étape 6 : vérification à la demande. */
    public AiRecommendation verifyNow(String id) {
        AiRecommendation r = store.findById(id).orElseThrow(() -> new NoSuchElementException("Recommandation introuvable : " + id));
        if (r.getAppliedAt() == null) {
            throw new IllegalStateException("La correction doit d'abord être déclarée appliquée");
        }
        return verify(r);
    }

    /** Vérification périodique de toutes les corrections appliquées non encore confirmées. */
    @Scheduled(fixedDelayString = "${assistant.corrections.verify-interval-ms:600000}", initialDelay = 120000)
    public void verifyPending() {
        for (AiRecommendation.FixStatus status : List.of(AiRecommendation.FixStatus.APPLIED,
                AiRecommendation.FixStatus.NOT_ENOUGH_DATA, AiRecommendation.FixStatus.STILL_PRESENT)) {
            for (AiRecommendation r : store.findByFixStatus(status)) {
                try {
                    verify(r);
                } catch (RuntimeException e) {
                    log.warn("Vérification de la correction {} impossible : {}", r.getId(), e.getMessage());
                }
            }
        }
    }

    AiRecommendation verify(AiRecommendation r) {
        Verification v = switch (r.getIncidentType() == null ? "other" : r.getIncidentType()) {
            case "log_anomaly" -> verifyLogAnomaly(r);
            case "test_failure" -> verifyTest(r);
            case "vulnerability" -> verifyVulnerability(r);
            default -> new Verification(AiRecommendation.FixStatus.NOT_ENOUGH_DATA,
                    "Type d'incident non vérifiable automatiquement : vérification manuelle requise.");
        };
        AiRecommendation.FixStatus previous = r.getFixStatus();
        r.setFixStatus(v.status());
        r.setVerifiedAt(LocalDateTime.now());
        r.setVerificationDetails(v.details());
        store.save(r);
        if (previous != v.status()) {
            auditService.recordAs("system", "CORRECTION_VERIFIED", r.getId(), v.status() + " · " + v.details());
            eventService.publish("correction.verified",
                    v.status() == AiRecommendation.FixStatus.VERIFIED ? "success"
                            : v.status() == AiRecommendation.FixStatus.STILL_PRESENT ? "warning" : "info",
                    v.status() == AiRecommendation.FixStatus.VERIFIED ? "Correction vérifiée : le problème a disparu"
                            : v.status() == AiRecommendation.FixStatus.STILL_PRESENT ? "Correction inefficace : le problème persiste"
                            : "Correction en attente de données",
                    Map.of("recommendationId", r.getId(), "status", v.status().name(), "message", v.details()));
        }
        return r;
    }

    record Verification(AiRecommendation.FixStatus status, String details) { }

    private Verification verifyLogAnomaly(AiRecommendation r) {
        String key = r.getIncidentRef();
        if (key == null || !key.startsWith("anomaly-")) {
            return new Verification(AiRecommendation.FixStatus.NOT_ENOUGH_DATA, "Référence d'anomalie inconnue : vérification manuelle requise.");
        }
        LocalDateTime after = r.getAppliedAt();
        Optional<ManagedApplication> app = Optional.ofNullable(r.getApplicationCode()).flatMap(applicationService::find);
        long newLogs = app.isPresent()
                ? logRepository.countForApplicationAfter(app.get().getCode(), safe(app.get().logSourceSet()), after)
                : logRepository.countByTimestampAfter(after);
        List<LogRecord> recent = logRepository.findAfter(after, List.of(LogEntry.LogLevel.WARN, LogEntry.LogLevel.ERROR, LogEntry.LogLevel.FATAL));
        long occurrences = 0;
        LocalDateTime last = null;
        for (LogRecord l : recent) {
            if (app.isPresent() && !app.get().getCode().equals(l.getApplicationCode()) && !app.get().logSourceSet().contains(l.getSource())) continue;
            if (key.equals(LogAnalysisService.keyOf(LogAnalysisService.normalize(l.getMessage())))) {
                occurrences++;
                if (last == null || l.getTimestamp().isAfter(last)) last = l.getTimestamp();
            }
        }
        if (occurrences > 0) {
            return new Verification(AiRecommendation.FixStatus.STILL_PRESENT, occurrences + " occurrence(s) de l'anomalie depuis la correction du "
                    + after.format(FMT) + " (dernière le " + last.format(FMT) + ").");
        }
        if (newLogs < MIN_NEW_LOGS) {
            return new Verification(AiRecommendation.FixStatus.NOT_ENOUGH_DATA, "Seulement " + newLogs + " log(s) reçu(s) depuis la correction : "
                    + "au moins " + MIN_NEW_LOGS + " sont nécessaires pour conclure.");
        }
        return new Verification(AiRecommendation.FixStatus.VERIFIED, "Aucune occurrence sur " + newLogs + " log(s) reçu(s) depuis la correction du "
                + after.format(FMT) + ".");
    }

    private Verification verifyTest(AiRecommendation r) {
        String ref = r.getIncidentRef();
        if (ref == null || !ref.startsWith("test:") || !ref.contains("#")) {
            return new Verification(AiRecommendation.FixStatus.NOT_ENOUGH_DATA, "Référence de test inconnue : vérification manuelle requise.");
        }
        String suiteName = ref.substring(5, ref.indexOf('#'));
        String testName = ref.substring(ref.indexOf('#') + 1);
        List<TestRunRecord> runs = new ArrayList<>(historyService.runsAfter(r.getApplicationCode(), r.getAppliedAt()));
        java.util.Collections.reverse(runs); // la plus récente d'abord
        for (TestRunRecord run : runs) {
            for (SuiteResult suite : historyService.readSuites(run.getSuitesJson())) {
                if (!suiteName.equals(suite.getSuiteName()) || suite.getTestCases() == null) continue;
                for (TestCaseResult tc : suite.getTestCases()) {
                    if (!testName.equals(tc.getName())) continue;
                    if (tc.getStatus() == TestCaseResult.Status.PASSED) {
                        return new Verification(AiRecommendation.FixStatus.VERIFIED, "Le test réussit dans l'exécution #" + run.getId()
                                + " du " + run.getExecutedAt().format(FMT) + ".");
                    }
                    if (tc.getStatus().isFailure()) {
                        return new Verification(AiRecommendation.FixStatus.STILL_PRESENT, "Le test échoue encore dans l'exécution #" + run.getId()
                                + " du " + run.getExecutedAt().format(FMT) + " : " + String.valueOf(tc.getFailureMessage()));
                    }
                }
            }
        }
        return new Verification(AiRecommendation.FixStatus.NOT_ENOUGH_DATA, runs.isEmpty()
                ? "Aucune exécution de tests depuis la correction : relancez les tests ou attendez le prochain pipeline."
                : "Le test n'apparaît pas dans les " + runs.size() + " exécution(s) postérieure(s) à la correction.");
    }

    private Verification verifyVulnerability(AiRecommendation r) {
        String ref = r.getIncidentRef();
        if (ref == null || !ref.startsWith("vuln:")) {
            return new Verification(AiRecommendation.FixStatus.NOT_ENOUGH_DATA, "Référence de vulnérabilité inconnue : vérification manuelle requise.");
        }
        String[] parts = ref.split(":", 4); // vuln:source:id:composant
        if (parts.length < 4) {
            return new Verification(AiRecommendation.FixStatus.NOT_ENOUGH_DATA, "Référence de vulnérabilité incomplète.");
        }
        List<VulnerabilityRecord> records = vulnerabilityRepository.findBySourceAndVulnIdAndComponent(parts[1], parts[2], parts[3]);
        if (r.getApplicationCode() != null) {
            records = records.stream().filter(v -> r.getApplicationCode().equals(v.getApplicationCode())).toList();
        }
        if (records.isEmpty()) {
            return new Verification(AiRecommendation.FixStatus.NOT_ENOUGH_DATA, "Vulnérabilité introuvable dans les résultats de scan.");
        }
        VulnerabilityRecord v = records.get(0);
        if (!v.getStatus().isOpen()) {
            return new Verification(AiRecommendation.FixStatus.VERIFIED, "Vulnérabilité au statut " + v.getStatus()
                    + (v.isAutoResolved() ? " (plus détectée par le dernier scan)" : "") + ".");
        }
        if (v.getLastDetectedAt() != null && v.getLastDetectedAt().isAfter(r.getAppliedAt())) {
            return new Verification(AiRecommendation.FixStatus.STILL_PRESENT, "Toujours détectée par le scan du "
                    + v.getLastDetectedAt().format(FMT) + ", postérieur à la correction.");
        }
        return new Verification(AiRecommendation.FixStatus.NOT_ENOUGH_DATA, "Aucun nouveau scan depuis la correction : relancez le scan.");
    }

    private static Set<String> safe(Set<String> sources) {
        return sources.isEmpty() ? Set.of("__aucune_source__") : sources;
    }
}
