/**
 * Role : orchestrer les campagnes de validation multi-applications et produire un rapport par application.
 * Contenu : definition de ValidationService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.domain.ApplicationValidationRecord;
import com.vermeg.assistant.domain.ManagedApplication;
import com.vermeg.assistant.domain.ValidationCampaignRecord;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.model.ValidationModels;
import com.vermeg.assistant.model.ValidationModels.ApplicationValidationReport;
import com.vermeg.assistant.model.ValidationModels.CriterionResult;
import com.vermeg.assistant.model.ValidationModels.LlmAssessment;
import com.vermeg.assistant.model.ValidationModels.ValidationOutcome;
import com.vermeg.assistant.model.ValidationModels.Verdict;
import com.vermeg.assistant.model.Vulnerability;
import com.vermeg.assistant.repository.ApplicationValidationRepository;
import com.vermeg.assistant.repository.ValidationCampaignRepository;
import com.vermeg.assistant.security.CurrentUser;
import com.vermeg.assistant.service.AiClient;
import com.vermeg.assistant.service.ApplicationService;
import com.vermeg.assistant.service.AuditService;
import com.vermeg.assistant.service.EventService;
import com.vermeg.assistant.service.JobService;
import com.vermeg.assistant.service.TestOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Campagnes de validation : l'utilisateur sélectionne N applications ; chacune est validée
 * isolément (une erreur sur l'une n'interrompt pas les autres) et produit SON rapport.
 * <p>
 * Pour chaque application : (1) exécution optionnelle de ses tests, (2) collecte de ses preuves,
 * (3) politique adaptée à sa criticité, (4) critères mesurables, (5) avis IA borné et contrôlé,
 * (6) décision tracée, (7) risques et recommandations, (8) rapport Markdown + HTML persisté.
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);
    public static final String JOB_TYPE = "validation";
    private static final Set<String> MODES = Set.of("LATEST_RESULTS", "RUN_TESTS");

    private final ApplicationService applicationService;
    private final ValidationEvidenceCollector collector;
    private final ValidationPolicyProvider policyProvider;
    private final TestOrchestrationService testService;
    private final AiClient aiClient;
    private final ValidationCampaignRepository campaignRepository;
    private final ApplicationValidationRepository validationRepository;
    private final JobService jobService;
    private final EventService eventService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AdaptiveValidationEngine engine = new AdaptiveValidationEngine();
    private final ValidationInsights insights = new ValidationInsights();

    /** Actions déclenchées en fin de campagne (ex. vérification des corrections appliquées). */
    private final List<Consumer<Long>> campaignListeners = new CopyOnWriteArrayList<>();

    public ValidationService(ApplicationService applicationService, ValidationEvidenceCollector collector,
                             ValidationPolicyProvider policyProvider, TestOrchestrationService testService,
                             AiClient aiClient, ValidationCampaignRepository campaignRepository,
                             ApplicationValidationRepository validationRepository, JobService jobService,
                             EventService eventService, AuditService auditService, ObjectMapper objectMapper) {
        this.applicationService = applicationService;
        this.collector = collector;
        this.policyProvider = policyProvider;
        this.testService = testService;
        this.aiClient = aiClient;
        this.campaignRepository = campaignRepository;
        this.validationRepository = validationRepository;
        this.jobService = jobService;
        this.eventService = eventService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public void onCampaignCompleted(Consumer<Long> listener) {
        campaignListeners.add(listener);
    }

    // ================================================================ lancement

    public ApiDtos.ValidationCampaignView start(ApiDtos.ValidationRequest request) {
        if (request == null || request.applications() == null || request.applications().isEmpty()) {
            throw new IllegalArgumentException("Sélectionnez au moins une application à valider");
        }
        if (jobService.running(JOB_TYPE).isPresent()) {
            throw new IllegalStateException("Une campagne de validation est déjà en cours : attendez sa fin avant d'en lancer une autre");
        }
        String mode = request.mode() == null || request.mode().isBlank() ? "LATEST_RESULTS" : request.mode().trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw new IllegalArgumentException("Mode invalide : " + MODES);
        boolean useLlm = request.useLlm() == null || request.useLlm();

        List<ManagedApplication> apps = new ArrayList<>();
        for (String code : new LinkedHashSet<>(request.applications())) {
            ManagedApplication app = applicationService.require(code);
            if (!app.isActive()) throw new IllegalArgumentException("Application désactivée : " + app.getCode());
            apps.add(app);
        }

        ValidationCampaignRecord campaign = new ValidationCampaignRecord();
        campaign.setLabel(request.label() == null || request.label().isBlank()
                ? "Validation de " + apps.size() + " application(s)" : request.label().trim());
        campaign.setMode(mode);
        campaign.setUseLlm(useLlm);
        campaign.setStatus("RUNNING");
        campaign.setApplicationCodes(String.join(",", apps.stream().map(ManagedApplication::getCode).toList()));
        campaign.setTriggeredBy(CurrentUser.username());
        campaign.setStartedAt(LocalDateTime.now());
        campaign.setTotal(apps.size());
        campaign = campaignRepository.save(campaign);

        List<Long> recordIds = new ArrayList<>();
        for (ManagedApplication app : apps) {
            ApplicationValidationRecord r = new ApplicationValidationRecord();
            r.setCampaignId(campaign.getId());
            r.setApplicationCode(app.getCode());
            r.setApplicationName(app.getName());
            r.setCriticality(app.getCriticality().name());
            r.setState("PENDING");
            recordIds.add(validationRepository.save(r).getId());
        }
        auditService.record("VALIDATION_STARTED", "campagne #" + campaign.getId(),
                campaign.getApplicationCodes() + " · mode " + mode + " · IA " + (useLlm ? "oui" : "non"));

        final Long campaignId = campaign.getId();
        JobService.Job job = jobService.submit(JOB_TYPE, campaign.getLabel(), () -> runCampaign(campaignId, recordIds, mode, useLlm));
        campaign.setJobId(job.getId());
        campaignRepository.save(campaign);
        return campaign(campaignId);
    }

    private JobService.JobOutcome runCampaign(Long campaignId, List<Long> recordIds, String mode, boolean useLlm) {
        int done = 0;
        int errors = 0;
        for (Long id : recordIds) {
            ApplicationValidationRecord record = validationRepository.findById(id).orElse(null);
            if (record == null) continue;
            try {
                validateOne(record, campaignId, mode, useLlm);
                done++;
            } catch (RuntimeException e) {
                // Isolation : l'échec d'une application n'empêche pas la validation des autres.
                log.error("Validation de {} en erreur", record.getApplicationCode(), e);
                record.setState("ERROR");
                record.setError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                record.setFinishedAt(LocalDateTime.now());
                validationRepository.save(record);
                errors++;
                eventService.publish("validation.application", "warning", "Validation en erreur : " + record.getApplicationName(),
                        Map.of("campaignId", campaignId, "application", record.getApplicationCode(), "error", String.valueOf(record.getError())));
            }
        }
        ValidationCampaignRecord campaign = finishCampaign(campaignId, errors);
        eventService.publish("validation.completed", errors == 0 ? "success" : "warning",
                "Campagne de validation terminée : " + done + " rapport(s) produit(s)",
                Map.of("campaignId", campaignId, "validated", campaign.getValidated(), "reserves", campaign.getReserves(),
                        "rejected", campaign.getRejected(), "inconclusive", campaign.getInconclusive(), "errors", errors));
        auditService.record("VALIDATION_COMPLETED", "campagne #" + campaignId, done + " rapport(s), " + errors + " erreur(s)");
        for (Consumer<Long> listener : campaignListeners) {
            try {
                listener.accept(campaignId);
            } catch (RuntimeException e) {
                log.warn("Traitement de fin de campagne en échec : {}", e.getMessage());
            }
        }
        return new JobService.JobOutcome(errors < recordIds.size(),
                done + " application(s) validée(s), " + errors + " en erreur · " + campaign.getValidated() + " validée(s), "
                        + campaign.getReserves() + " avec réserves, " + campaign.getRejected() + " rejetée(s), "
                        + campaign.getInconclusive() + " non concluante(s)",
                Map.of("campaignId", campaignId));
    }

    private ValidationCampaignRecord finishCampaign(Long campaignId, int errors) {
        ValidationCampaignRecord c = campaignRepository.findById(campaignId).orElseThrow();
        List<ApplicationValidationRecord> results = validationRepository.findByCampaignIdOrderByApplicationNameAsc(campaignId);
        c.setValidated(count(results, Verdict.VALIDATED));
        c.setReserves(count(results, Verdict.VALIDATED_WITH_RESERVES));
        c.setRejected(count(results, Verdict.REJECTED));
        c.setInconclusive(count(results, Verdict.INCONCLUSIVE));
        c.setErrors(errors);
        OptionalDouble avg = results.stream().map(ApplicationValidationRecord::getScore).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average();
        c.setAverageScore(avg.isPresent() ? Math.round(avg.getAsDouble() * 10) / 10.0 : null);
        c.setStatus(errors == 0 ? "COMPLETED" : errors == results.size() ? "FAILED" : "PARTIAL");
        c.setFinishedAt(LocalDateTime.now());
        return campaignRepository.save(c);
    }

    private static int count(List<ApplicationValidationRecord> results, Verdict v) {
        return (int) results.stream().filter(r -> r.getVerdict() == v).count();
    }

    /** Validation complète d'une application ; persiste le résultat et ses rapports. */
    void validateOne(ApplicationValidationRecord record, Long campaignId, String mode, boolean useLlm) {
        record.setState("RUNNING");
        record.setStartedAt(LocalDateTime.now());
        validationRepository.save(record);
        ManagedApplication app = applicationService.require(record.getApplicationCode());

        List<String> notes = new ArrayList<>();
        if ("RUN_TESTS".equals(mode)) {
            if (app.getProjectPath() == null || app.getProjectPath().isBlank()) {
                notes.add("Mode « exécuter les tests » : aucun dossier projet déclaré, dernières preuves connues utilisées.");
            } else {
                notes.add(testService.runForApplication(app)
                        .map(s -> "Tests exécutés pendant la campagne : " + s.getTotalTests() + " tests, réussite " + s.getSuccessRatePercent() + " %.")
                        .orElse("Tests lancés mais aucun rapport produit (compilation ou configuration à vérifier) : dernières preuves connues utilisées."));
            }
        }
        ValidationEvidence ev = collector.collect(app);
        ValidationPolicy policy = policyProvider.forApplication(app, notes);
        List<CriterionResult> criteria = engine.evaluateCriteria(ev, policy);

        LlmAssessment llm;
        if (!useLlm || !policy.llmEnabled) {
            llm = LlmAssessment.unavailable("DISABLED", useLlm ? "IA désactivée par la politique de l'application" : "Analyse IA non demandée pour cette campagne");
        } else {
            ValidationOutcome provisional = engine.decide(criteria, policy, null, List.of(), ev);
            Set<String> allowed = allowedRefs(ev, criteria);
            llm = aiClient.assessValidation(llmPayload(app, ev, criteria, provisional, allowed))
                    .map(json -> LlmAssessmentParser.parse(json, allowed))
                    .orElse(LlmAssessment.unavailable("UNAVAILABLE", "Service IA injoignable ou en erreur : décision purement déterministe"));
        }
        ValidationOutcome outcome = engine.decide(criteria, policy, llm, notes, ev);

        ApplicationValidationReport report = new ApplicationValidationReport(app.getCode(), app.getName(), app.getCriticality().name(),
                app.getOwner(), app.getServerType(), campaignId, record.getId(), LocalDateTime.now(), CurrentUser.username(), mode,
                outcome.verdict(), outcome.score(), outcome.baseScore(), outcome.llmAdjustment(), outcome.confidence(),
                outcome.reviewRequired(), outcome.reviewReasons(), outcome.blockingReasons(), criteria, outcome.trace(), policy.snapshot(),
                new ValidationModels.TestSection(ev.testsAvailable, ev.testRunId, ev.testRunAt, ev.testTrigger, ev.totalTests,
                        ev.failedTests, ev.skippedTests, ev.successRate, ev.lineCoverage, ev.branchCoverage, ev.testDurationSeconds,
                        ev.testRunAt == null ? 0 : Math.round(ev.testAgeHours() * 10) / 10.0, ev.failures, ev.newFailures, ev.fixedTests,
                        ev.flaky, ev.testTrend),
                new ValidationModels.LogSection(ev.logsAvailable, ev.logCount, ev.errorCount, ev.warnCount, ev.errorRatePercent,
                        ev.logsByLayer, ev.errorsByLayer, ev.criticalAnomalies, ev.spikes, ev.anomalies, ev.logExcerpts),
                ev.incidents,
                new ValidationModels.SecuritySection(ev.securityScanned, ev.openVulns, ev.openBySeverity, ev.topVulnerabilities),
                insights.risks(ev, criteria, outcome), insights.recommendations(ev, criteria, outcome), outcome.llm(),
                ev.previousScore, ev.previousVerdict);

        record.setVerdict(outcome.verdict());
        record.setFinalVerdict(outcome.verdict());
        record.setScore(outcome.score());
        record.setBaseScore(outcome.baseScore());
        record.setLlmAdjustment(outcome.llmAdjustment());
        record.setConfidence(outcome.confidence());
        record.setReviewRequired(outcome.reviewRequired());
        record.setTestsTotal(ev.totalTests);
        record.setTestsFailed(ev.failedTests);
        record.setSuccessRate(ev.testsAvailable ? ev.successRate : null);
        record.setLineCoverage(ev.lineCoverage);
        record.setOpenCritical(ev.openCount("CRITICAL"));
        record.setOpenHigh(ev.openCount("HIGH"));
        record.setCriticalAnomalies(ev.criticalAnomalies);
        record.setIncidents(ev.incidents.size());
        record.setLogErrors(ev.errorCount);
        record.setLlmStatus(outcome.llm() == null ? null : outcome.llm().status());
        record.setLlmOpinion(outcome.llm() == null ? null : outcome.llm().opinion());
        record.setLlmProvider(outcome.llm() == null ? null : outcome.llm().provider());
        try {
            record.setReportJson(objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            record.setReportJson("{}");
        }
        record.setReportMarkdown(ValidationReportRenderer.markdown(report));
        record.setReportHtml(ValidationReportRenderer.html(report));
        record.setState("DONE");
        record.setFinishedAt(LocalDateTime.now());
        validationRepository.save(record);

        auditService.record("VALIDATION_RESULT", app.getCode(), outcome.verdict() + " · score " + outcome.score()
                + " (déterministe " + outcome.baseScore() + ", IA " + outcome.llmAdjustment() + ")"
                + (outcome.reviewRequired() ? " · revue humaine requise" : ""));
        eventService.publish("validation.application",
                outcome.verdict() == Verdict.VALIDATED ? "success" : outcome.verdict() == Verdict.REJECTED ? "critical" : "warning",
                app.getName() + " : " + outcome.verdict().label() + " (" + outcome.score() + "/100)",
                Map.of("campaignId", campaignId, "validationId", record.getId(), "application", app.getCode(),
                        "verdict", outcome.verdict().name(), "score", outcome.score()));
    }

    /** Identifiants que l'IA a le droit de citer : tout ce qui figure dans les preuves transmises. */
    private Set<String> allowedRefs(ValidationEvidence ev, List<CriterionResult> criteria) {
        Set<String> refs = new LinkedHashSet<>();
        criteria.forEach(c -> refs.add(c.code()));
        ev.failures.forEach(f -> refs.add(f.ref()));
        refs.addAll(ev.newFailures);
        ev.flaky.forEach(f -> refs.add("test:" + f.suiteName() + "#" + f.testName()));
        ev.anomalies.forEach(a -> refs.add(a.key()));
        ev.incidents.forEach(i -> refs.add(i.id()));
        ev.topVulnerabilities.forEach(v -> refs.add("vuln:" + v.getSource() + ":" + v.getId() + ":" + v.getAffectedComponent()));
        return refs;
    }

    private Map<String, Object> llmPayload(ManagedApplication app, ValidationEvidence ev, List<CriterionResult> criteria,
                                           ValidationOutcome provisional, Set<String> allowed) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("application", Map.of("code", app.getCode(), "name", app.getName(), "criticality", app.getCriticality().name(),
                "server_type", app.getServerType() == null ? "" : app.getServerType()));
        p.put("deterministic", Map.of("verdict", provisional.verdict().name(), "score", provisional.score(),
                "confidence", provisional.confidence(), "blocking", provisional.blockingReasons()));
        List<Map<String, Object>> crit = new ArrayList<>();
        for (CriterionResult c : criteria) {
            crit.add(Map.of("code", c.code(), "label", c.label(), "status", c.status(), "score", c.score(),
                    "measured", c.measured(), "threshold", c.threshold(), "justification", truncate(c.justification(), 400)));
        }
        p.put("criteria", crit);
        p.put("tests", Map.of("total", ev.totalTests, "failed", ev.failedTests, "success_rate", ev.successRate,
                "coverage", ev.lineCoverage == null ? -1 : ev.lineCoverage, "new_failures", ev.newFailures,
                "failures", ev.failures.stream().limit(10).map(f -> Map.of("ref", f.ref(), "message", truncate(nz(f.message()), 300))).toList()));
        p.put("incidents", ev.incidents.stream().limit(5).map(i -> Map.of("id", i.id(), "severity", i.severity(),
                "summary", truncate(i.summary(), 500), "root_cause", truncate(i.rootCause().signal().message(), 300),
                "rule", nz(i.rootCause().rule()), "confidence", i.rootCause().confidencePercent())).toList());
        p.put("anomalies", ev.anomalies.stream().limit(8).map(a -> Map.of("key", a.key(), "layer", nz(a.layer()),
                "message", truncate(a.message(), 250), "occurrences", a.occurrences(), "critical", a.critical())).toList());
        p.put("vulnerabilities", ev.topVulnerabilities.stream().limit(10).map((Vulnerability v) -> Map.of(
                "ref", "vuln:" + v.getSource() + ":" + v.getId() + ":" + v.getAffectedComponent(), "id", v.getId(),
                "severity", v.getSeverity().name(), "component", truncate(v.getAffectedComponent(), 120),
                "fixed_version", nz(v.getFixedVersion()))).toList());
        p.put("allowed_refs", new ArrayList<>(allowed));
        return p;
    }

    // ============================================================ décision humaine

    public ApiDtos.ApplicationValidationSummary decide(Long id, ApiDtos.ValidationDecisionRequest request) {
        ApplicationValidationRecord r = validationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Validation introuvable : " + id));
        if (!"DONE".equals(r.getState())) throw new IllegalStateException("Cette validation n'est pas terminée");
        if (request == null || request.decision() == null) throw new IllegalArgumentException("Champ 'decision' requis : CONFIRM ou OVERRIDE");
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        Verdict finalVerdict;
        String recorded;
        if ("CONFIRM".equals(decision)) {
            finalVerdict = r.getVerdict();
            recorded = "CONFIRMED";
        } else if ("OVERRIDE".equals(decision)) {
            if (request.verdict() == null) throw new IllegalArgumentException("Indiquez la décision corrigée (verdict)");
            try {
                finalVerdict = Verdict.valueOf(request.verdict().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Verdict invalide : " + Arrays.toString(Verdict.values()));
            }
            if (request.comment() == null || request.comment().trim().length() < 10) {
                throw new IllegalArgumentException("Une correction de décision doit être justifiée (commentaire de 10 caractères minimum)");
            }
            recorded = "OVERRIDDEN";
        } else {
            throw new IllegalArgumentException("Décision invalide : CONFIRM ou OVERRIDE");
        }
        r.setFinalVerdict(finalVerdict);
        r.setHumanDecision(recorded);
        r.setDecidedBy(CurrentUser.username());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecisionComment(request.comment() == null ? null : request.comment().trim());
        r.setReportMarkdown(ValidationReportRenderer.replaceHuman(r.getReportMarkdown(), ValidationReportRenderer.humanMarkdown(
                recorded, finalVerdict.label(), r.getDecidedBy(), r.getDecidedAt(), r.getDecisionComment())));
        r.setReportHtml(ValidationReportRenderer.replaceHuman(r.getReportHtml(), ValidationReportRenderer.humanHtml(
                recorded, finalVerdict.label(), r.getDecidedBy(), r.getDecidedAt(), r.getDecisionComment())));
        validationRepository.save(r);
        auditService.record("VALIDATION_DECISION", r.getApplicationCode() + " #" + r.getId(), recorded + " : " + r.getVerdict()
                + " -> " + finalVerdict + (r.getDecisionComment() == null ? "" : " · " + r.getDecisionComment()));
        eventService.publish("validation.decision", "info", "Décision humaine : " + r.getApplicationName() + " → " + finalVerdict.label(),
                Map.of("validationId", r.getId(), "application", r.getApplicationCode(), "decision", recorded));
        return summary(r);
    }

    // ================================================================== lecture

    public List<ApiDtos.ValidationCampaignView> campaigns(int limit) {
        return campaignRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 100)))).stream()
                .map(c -> view(c, validationRepository.findByCampaignIdOrderByApplicationNameAsc(c.getId()))).toList();
    }

    public ApiDtos.ValidationCampaignView campaign(Long id) {
        ValidationCampaignRecord c = campaignRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Campagne introuvable : " + id));
        return view(c, validationRepository.findByCampaignIdOrderByApplicationNameAsc(id));
    }

    public ApiDtos.ApplicationValidationDetail detail(Long id) {
        ApplicationValidationRecord r = record(id);
        JsonNode report = null;
        if (r.getReportJson() != null) {
            try {
                report = objectMapper.readTree(r.getReportJson());
            } catch (Exception e) {
                log.warn("Rapport JSON illisible pour la validation {}", id);
            }
        }
        return new ApiDtos.ApplicationValidationDetail(summary(r), report);
    }

    public List<ApiDtos.ApplicationValidationSummary> history(String application, int limit) {
        String code = applicationService.require(application).getCode();
        return validationRepository.findByApplicationCodeAndStateOrderByFinishedAtDesc(code, "DONE",
                PageRequest.of(0, Math.max(1, Math.min(limit, 100)))).stream().map(this::summary).toList();
    }

    /** Tableau de bord récapitulatif : dernière validation de chaque application active. */
    public ApiDtos.PortfolioSummary portfolio() {
        List<ApiDtos.PortfolioEntry> entries = new ArrayList<>();
        int validated = 0;
        int reserves = 0;
        int rejected = 0;
        int inconclusive = 0;
        int never = 0;
        int pending = 0;
        List<Double> scores = new ArrayList<>();
        for (ManagedApplication app : applicationService.activeEntities()) {
            List<ApplicationValidationRecord> last = validationRepository.findByApplicationCodeAndStateOrderByFinishedAtDesc(
                    app.getCode(), "DONE", PageRequest.of(0, 8));
            ApiDtos.ApplicationValidationSummary latest = last.isEmpty() ? null : summary(last.get(0));
            List<Double> trend = new ArrayList<>(last.stream().map(ApplicationValidationRecord::getScore).filter(Objects::nonNull).toList());
            Collections.reverse(trend);
            entries.add(new ApiDtos.PortfolioEntry(applicationService.toView(app), latest, trend));
            if (latest == null) {
                never++;
                continue;
            }
            if (latest.score() != null) scores.add(latest.score());
            if (latest.reviewRequired() && latest.humanDecision() == null) pending++;
            switch (Verdict.valueOf(latest.finalVerdict())) {
                case VALIDATED -> validated++;
                case VALIDATED_WITH_RESERVES -> reserves++;
                case REJECTED -> rejected++;
                case INCONCLUSIVE -> inconclusive++;
            }
        }
        Double avg = scores.isEmpty() ? null : Math.round(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
        return new ApiDtos.PortfolioSummary(entries.size(), validated, reserves, rejected, inconclusive, never, pending, avg,
                entries, LocalDateTime.now());
    }

    public String markdown(Long id) {
        return Optional.ofNullable(record(id).getReportMarkdown()).orElseThrow(() -> new NoSuchElementException("Rapport non disponible"));
    }

    public String html(Long id) {
        return Optional.ofNullable(record(id).getReportHtml()).orElseThrow(() -> new NoSuchElementException("Rapport non disponible"));
    }

    public String fileBaseName(Long id) {
        ApplicationValidationRecord r = record(id);
        return "rapport-validation-" + r.getApplicationCode() + "-" + (r.getFinishedAt() == null ? LocalDate.now() : r.getFinishedAt().toLocalDate());
    }

    /** Archive ZIP d'une campagne : un dossier par application (rapport .md + .html) et une synthèse. */
    public byte[] campaignZip(Long campaignId) {
        ApiDtos.ValidationCampaignView c = campaign(campaignId);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            StringBuilder synthese = new StringBuilder("# ").append(c.label()).append(" (campagne #").append(c.id()).append(")\n\n")
                    .append("Lancée par ").append(c.triggeredBy()).append(" le ").append(ValidationReportRenderer.date(c.startedAt()))
                    .append(" · mode ").append(c.mode()).append(" · IA ").append(c.useLlm() ? "oui" : "non").append("\n\n")
                    .append("| Application | Décision | Décision finale | Score | Confiance | Revue requise |\n|---|---|---|---|---|---|\n");
            for (ApiDtos.ApplicationValidationSummary s : c.results()) {
                synthese.append("| ").append(s.applicationName()).append(" | ").append(s.state().equals("DONE") ? s.verdict() : s.state())
                        .append(" | ").append(s.finalVerdict() == null ? "—" : s.finalVerdict()).append(" | ")
                        .append(s.score() == null ? "—" : s.score()).append(" | ")
                        .append(s.confidence() == null ? "—" : Math.round(s.confidence() * 100) + " %").append(" | ")
                        .append(s.reviewRequired() ? "oui" : "non").append(" |\n");
                if (!"DONE".equals(s.state())) continue;
                ApplicationValidationRecord r = record(s.id());
                String base = r.getApplicationCode() + "/" + fileBaseName(r.getId());
                put(zip, base + ".md", r.getReportMarkdown());
                put(zip, base + ".html", r.getReportHtml());
            }
            put(zip, "synthese-campagne-" + c.id() + ".md", synthese.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Création de l'archive impossible : " + e.getMessage(), e);
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private ApplicationValidationRecord record(Long id) {
        return validationRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Validation introuvable : " + id));
    }

    private ApiDtos.ValidationCampaignView view(ValidationCampaignRecord c, List<ApplicationValidationRecord> results) {
        return new ApiDtos.ValidationCampaignView(c.getId(), c.getLabel(), c.getMode(), c.isUseLlm(), c.getStatus(),
                c.getApplicationCodes() == null ? List.of() : List.of(c.getApplicationCodes().split(",")), c.getTriggeredBy(),
                c.getStartedAt(), c.getFinishedAt(), c.getTotal(), c.getValidated(), c.getReserves(), c.getRejected(),
                c.getInconclusive(), c.getErrors(), c.getAverageScore(), c.getJobId(), results.stream().map(this::summary).toList());
    }

    public ApiDtos.ApplicationValidationSummary summary(ApplicationValidationRecord r) {
        return new ApiDtos.ApplicationValidationSummary(r.getId(), r.getCampaignId(), r.getApplicationCode(), r.getApplicationName(),
                r.getCriticality(), r.getState(), r.getVerdict() == null ? null : r.getVerdict().name(),
                r.getFinalVerdict() == null ? null : r.getFinalVerdict().name(), r.getScore(), r.getBaseScore(), r.getLlmAdjustment(),
                r.getConfidence(), r.isReviewRequired(), r.getTestsTotal(), r.getTestsFailed(), r.getSuccessRate(), r.getLineCoverage(),
                r.getOpenCritical(), r.getOpenHigh(), r.getCriticalAnomalies(), r.getIncidents(), r.getLogErrors(), r.getLlmStatus(),
                r.getLlmOpinion(), r.getLlmProvider(), r.getError(), r.getStartedAt(), r.getFinishedAt(), r.getHumanDecision(),
                r.getDecidedBy(), r.getDecidedAt(), r.getDecisionComment());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max - 3) + "...";
    }
}
