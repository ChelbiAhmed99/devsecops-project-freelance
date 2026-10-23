/**
 * Role : rapprocher les anomalies des tickets Jira et piloter les actions Jira controlees et tracees.
 * Contenu : definition de JiraService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.jira;

import com.vermeg.assistant.domain.JiraActionRecord;
import com.vermeg.assistant.domain.ManagedApplication;
import com.vermeg.assistant.model.AiRecommendation;
import com.vermeg.assistant.model.JiraModels;
import com.vermeg.assistant.repository.JiraActionRepository;
import com.vermeg.assistant.security.CurrentUser;
import com.vermeg.assistant.service.AiClient;
import com.vermeg.assistant.service.AiRecommendationStore;
import com.vermeg.assistant.service.AiService;
import com.vermeg.assistant.service.ApplicationService;
import com.vermeg.assistant.service.AuditService;
import com.vermeg.assistant.service.EventService;
import com.vermeg.assistant.service.TextSimilarityUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intégration Jira contrôlée.
 * <ul>
 *   <li><b>Rapprochement</b> : pour une anomalie / un échec / une vulnérabilité, recherche JQL par mots-clés
 *       et par empreinte, puis classement par similarité (TF-IDF côté service IA, Jaccard à défaut).</li>
 *   <li><b>Proposition</b> : brouillon de ticket (ou de commentaire) incluant le diagnostic de corrélation et,
 *       si elle existe, la correction proposée par l'IA et son statut de validation humaine.</li>
 *   <li><b>Contrôle</b> : rien n'est écrit dans Jira sans approbation explicite d'un responsable qualité,
 *       distinct de l'auteur de la proposition (quatre yeux) ; chaque étape est auditée.</li>
 *   <li><b>Simulation</b> : sans configuration Jira, le workflow complet reste utilisable et tracé.</li>
 * </ul>
 */
@Service
public class JiraService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Set<String> TYPES = Set.of("CREATE_ISSUE", "ADD_COMMENT");
    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]{1,30}-\\d{1,9}");
    private static final Pattern STRONG_TOKEN = Pattern.compile(
            "(CVE-\\d{4}-\\d{4,7}|WFLY[A-Z]{0,6}\\d{4,5}|IJ\\d{6}|ARJUNA\\d{6}|UT\\d{6}|ORA-\\d{5}|[A-Z][A-Za-z0-9]*(Exception|Error))");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "from", "that", "this", "after", "before",
            "les", "des", "une", "pour", "dans", "avec", "sur", "par", "est", "pas", "aux", "ces", "son", "ses", "sont",
            "request", "error", "erreur", "failed", "échec", "exception", "service");

    private final JiraClient client;
    private final JiraActionRepository repository;
    private final ApplicationService applicationService;
    private final AiRecommendationStore recommendationStore;
    private final AiService aiService;
    private final AiClient aiClient;
    private final AuditService auditService;
    private final EventService eventService;

    @Value("${assistant.jira.default-project:}")
    private String defaultProject;
    @Value("${assistant.jira.issue-type:Bug}")
    private String issueType = "Bug";
    @Value("${assistant.jira.four-eyes:true}")
    private boolean fourEyes = true;

    public JiraService(JiraClient client, JiraActionRepository repository, ApplicationService applicationService,
                       AiRecommendationStore recommendationStore, AiService aiService, AiClient aiClient,
                       AuditService auditService, EventService eventService) {
        this.client = client;
        this.repository = repository;
        this.applicationService = applicationService;
        this.recommendationStore = recommendationStore;
        this.aiService = aiService;
        this.aiClient = aiClient;
        this.auditService = auditService;
        this.eventService = eventService;
    }

    public JiraModels.JiraStatus status() {
        return new JiraModels.JiraStatus(client.isEnabled(), client.isConfigured(), client.isConfigured() ? "LIVE" : "SIMULATION",
                client.deployment(), client.isConfigured() ? client.baseUrl() : null, blankToNull(defaultProject), issueType, fourEyes);
    }

    // ------------------------------------------------------------ rapprochement

    public List<JiraModels.JiraMatch> matches(JiraModels.IncidentQuery q) {
        String text = text(q);
        String fingerprint = fingerprint(q);
        List<String> keywords = keywords(text);
        if (!client.isConfigured()) return simulatedMatches(text, fingerprint);
        String project = projectKey(q.application(), null);
        StringBuilder jql = new StringBuilder();
        if (project != null) jql.append("project = \"").append(jqlEscape(project)).append("\" AND ");
        jql.append("(labels = \"").append(fingerprint).append('"');
        for (String k : keywords.stream().limit(4).toList()) jql.append(" OR text ~ \"").append(jqlTerm(k)).append('"');
        jql.append(") ORDER BY updated DESC");
        List<JiraModels.JiraIssue> issues;
        try {
            issues = client.search(jql.toString(), 20);
        } catch (Exception e) {
            throw new IllegalStateException("Recherche Jira impossible : " + e.getMessage());
        }
        List<Double> sims = aiClient.similarity(text, issues.stream().map(JiraModels.JiraIssue::summary).toList())
                .orElseGet(() -> issues.stream().map(i -> TextSimilarityUtil.jaccardSimilarity(text.toLowerCase(), i.summary().toLowerCase())).toList());
        List<JiraModels.JiraMatch> result = new ArrayList<>();
        for (int i = 0; i < issues.size(); i++) {
            JiraModels.JiraIssue issue = issues.get(i);
            if (issue.labels().contains(fingerprint)) {
                result.add(new JiraModels.JiraMatch(issue, 1.0, "Empreinte identique : ticket déjà ouvert pour cet incident"));
                continue;
            }
            double score = Math.round(sims.get(i) * 100) / 100.0;
            List<String> shared = keywords.stream().filter(k -> issue.summary().toLowerCase().contains(k.toLowerCase())).toList();
            if (score < 0.1 && shared.isEmpty()) continue;
            result.add(new JiraModels.JiraMatch(issue, Math.max(score, shared.isEmpty() ? 0 : 0.3),
                    shared.isEmpty() ? "Similarité textuelle " + Math.round(score * 100) + " %" : "Mots-clés communs : " + String.join(", ", shared)));
        }
        result.sort(Comparator.comparingDouble(JiraModels.JiraMatch::score).reversed());
        return result.stream().limit(5).toList();
    }

    /** Mode simulation : rapprochement avec les tickets simulés créés précédemment par l'assistant. */
    private List<JiraModels.JiraMatch> simulatedMatches(String text, String fingerprint) {
        List<JiraModels.JiraMatch> result = new ArrayList<>();
        for (JiraActionRecord a : repository.findByStatusIn(List.of("SIMULATED", "EXECUTED"))) {
            if (!"CREATE_ISSUE".equals(a.getType()) || a.getResultKey() == null) continue;
            JiraModels.JiraIssue issue = new JiraModels.JiraIssue(a.getResultKey(), a.getSummary(), "Simulé", "", "",
                    a.getExecutedAt() == null ? "" : a.getExecutedAt().toString(), List.of(nz(a.getFingerprint())), a.getResultUrl());
            if (fingerprint.equals(a.getFingerprint())) {
                result.add(new JiraModels.JiraMatch(issue, 1.0, "Empreinte identique : ticket déjà créé pour cet incident"));
            } else {
                double s = TextSimilarityUtil.jaccardSimilarity(text.toLowerCase(), nz(a.getSummary()).toLowerCase());
                if (s >= 0.2) result.add(new JiraModels.JiraMatch(issue, Math.round(s * 100) / 100.0, "Similarité textuelle " + Math.round(s * 100) + " %"));
            }
        }
        result.sort(Comparator.comparingDouble(JiraModels.JiraMatch::score).reversed());
        return result.stream().limit(5).toList();
    }

    // ------------------------------------------------------------- proposition

    /** Brouillon pré-rempli (non enregistré) : l'utilisateur le relit et l'ajuste avant de le proposer. */
    public JiraModels.ProposalRequest draft(JiraModels.IncidentQuery q, String type, String issueKey) {
        String t = type == null ? "CREATE_ISSUE" : type.toUpperCase(Locale.ROOT);
        Optional<ManagedApplication> app = Optional.ofNullable(q.application()).flatMap(applicationService::find);
        String diagnostic = aiService.diagnose(nz(q.incidentType()), q.incidentRef(), app.map(ManagedApplication::getCode).orElse(null));
        Optional<AiRecommendation> rec = Optional.ofNullable(q.recommendationId()).flatMap(recommendationStore::findById);
        String title = truncate(firstLine(text(q)), 180);
        String summary = "[DevSecOps]" + app.map(a -> "[" + a.getCode() + "]").orElse("") + " " + typeLabel(q.incidentType()) + " : " + title;

        StringBuilder d = new StringBuilder();
        d.append("h3. Incident détecté par l'assistant DevSecOps\n");
        app.ifPresent(a -> d.append("*Application* : ").append(a.getName()).append(" (").append(a.getCode()).append(") — criticité ")
                .append(a.getCriticality()).append('\n'));
        d.append("*Type* : ").append(typeLabel(q.incidentType())).append('\n');
        if (q.incidentRef() != null) d.append("*Référence* : ").append(q.incidentRef()).append('\n');
        d.append("{noformat}\n").append(truncate(text(q), 1500)).append("\n{noformat}\n");
        if (diagnostic != null) d.append("\nh3. Analyse d'origine (corrélation déterministe)\n").append(diagnostic).append('\n');
        rec.ifPresent(r -> {
            d.append("\nh3. Correction proposée par l'IA\n");
            d.append(r.getStatus() == AiRecommendation.ValidationStatus.APPROVED
                    ? "_Validée par " + r.getReviewedBy() + " le " + (r.getReviewedAt() == null ? "?" : r.getReviewedAt().format(FMT)) + "_\n"
                    : "_Proposition NON encore validée par un humain : à examiner avant toute application._\n");
            d.append("*Cause probable* : ").append(nz(r.getExplanation())).append('\n');
            d.append("{code}\n").append(nz(r.getSuggestedFix())).append("\n{code}\n");
        });
        d.append("\nh3. Traçabilité\nProposé depuis l'assistant DevSecOps par ").append(CurrentUser.username()).append(" le ")
                .append(LocalDateTime.now().format(FMT)).append(". Empreinte : ").append(fingerprint(q))
                .append(". Aucune modification n'est appliquée automatiquement au code ou à la production.");

        String comment = "Nouvelle occurrence détectée par l'assistant DevSecOps"
                + app.map(a -> " sur " + a.getName()).orElse("") + " (" + LocalDateTime.now().format(FMT) + ").\n"
                + "{noformat}\n" + truncate(text(q), 800) + "\n{noformat}\n"
                + (diagnostic != null ? "Analyse d'origine : " + diagnostic + "\n" : "")
                + rec.map(r -> (r.getStatus() == AiRecommendation.ValidationStatus.APPROVED ? "Correction validée : " : "Piste IA (non validée) : ")
                + truncate(nz(r.getSuggestedFix()), 1200)).orElse("");
        return new JiraModels.ProposalRequest(t, app.map(ManagedApplication::getCode).orElse(q.application()),
                projectKey(q.application(), null), q.incidentType(), q.incidentRef(), issueKey, truncate(summary, 250),
                d.toString(), comment, q.recommendationId());
    }

    public JiraModels.JiraActionView propose(JiraModels.ProposalRequest r) {
        if (r == null || r.type() == null || !TYPES.contains(r.type().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Type d'action invalide : " + TYPES);
        }
        String type = r.type().toUpperCase(Locale.ROOT);
        JiraActionRecord a = new JiraActionRecord();
        a.setType(type);
        a.setStatus("PROPOSED");
        a.setApplicationCode(r.application() == null || r.application().isBlank() ? null
                : applicationService.find(r.application()).map(ManagedApplication::getCode).orElse(null));
        a.setIncidentType(r.incidentType());
        a.setIncidentRef(r.incidentRef());
        a.setFingerprint(fingerprint(new JiraModels.IncidentQuery(r.application(), r.incidentType(), r.incidentRef(), r.summary(), null)));
        a.setRecommendationId(blankToNull(r.recommendationId()));
        if ("CREATE_ISSUE".equals(type)) {
            String project = projectKey(r.application(), r.projectKey());
            if (project == null) throw new IllegalArgumentException("Projet Jira inconnu : renseignez-le sur l'application ou dans JIRA_DEFAULT_PROJECT");
            if (r.summary() == null || r.summary().isBlank()) throw new IllegalArgumentException("Le résumé du ticket est obligatoire");
            a.setProjectKey(project);
            a.setSummary(r.summary().trim());
            a.setDescription(r.description());
        } else {
            if (r.issueKey() == null || !KEY.matcher(r.issueKey().trim().toUpperCase(Locale.ROOT)).matches()) {
                throw new IllegalArgumentException("Clé de ticket invalide (ex. TITRES-142)");
            }
            if (r.comment() == null || r.comment().isBlank()) throw new IllegalArgumentException("Le commentaire est obligatoire");
            a.setIssueKey(r.issueKey().trim().toUpperCase(Locale.ROOT));
            a.setSummary(r.summary());
            a.setComment(r.comment().trim());
        }
        a.setProposedBy(CurrentUser.username());
        a.setProposedAt(LocalDateTime.now());
        repository.save(a);
        auditService.record("JIRA_PROPOSED", "action Jira #" + a.getId(), type + (a.getIssueKey() != null ? " sur " + a.getIssueKey()
                : " dans " + a.getProjectKey()) + " · " + nz(a.getIncidentRef()));
        eventService.publish("jira.proposed", "info", "Action Jira à valider : " + truncate(nz(a.getSummary() != null ? a.getSummary() : a.getIssueKey()), 120),
                Map.of("actionId", a.getId(), "type", type));
        return view(a);
    }

    // --------------------------------------------------------------- contrôle

    public JiraModels.JiraActionView approve(Long id, JiraModels.DecisionRequest decision) {
        JiraActionRecord a = find(id);
        if (!"PROPOSED".equals(a.getStatus())) throw new IllegalStateException("Action déjà traitée (" + a.getStatus() + ")");
        String user = CurrentUser.username();
        if (fourEyes && user.equalsIgnoreCase(a.getProposedBy())) {
            throw new IllegalStateException("Principe des quatre yeux : l'auteur de la proposition ne peut pas l'approuver");
        }
        a.setDecidedBy(user);
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionComment(decision == null ? null : decision.comment());
        if (!client.isConfigured()) {
            a.setStatus("SIMULATED");
            a.setExecutedAt(LocalDateTime.now());
            a.setResultKey("CREATE_ISSUE".equals(a.getType()) ? "SIM-" + a.getId() : a.getIssueKey());
            repository.save(a);
            auditService.record("JIRA_SIMULATED", "action Jira #" + id, a.getType() + " approuvée par " + user + " (mode simulation, aucun appel à Jira)");
            return view(a);
        }
        try {
            if ("CREATE_ISSUE".equals(a.getType())) {
                // Idempotence : si un ticket porte déjà l'empreinte de l'incident, on ne crée pas de doublon.
                List<JiraModels.JiraIssue> existing = client.search("labels = \"" + a.getFingerprint() + "\"", 1);
                if (!existing.isEmpty()) {
                    a.setResultKey(existing.get(0).key());
                    a.setResultUrl(existing.get(0).url());
                    a.setError("Ticket existant réutilisé (même empreinte) : aucune création.");
                } else {
                    String key = client.createIssue(a.getProjectKey(), issueType, a.getSummary(), nz(a.getDescription()),
                            List.of("devsecops", a.getFingerprint()));
                    a.setResultKey(key);
                    a.setResultUrl(client.browseUrl(key));
                }
            } else {
                client.addComment(a.getIssueKey(), a.getComment());
                a.setResultKey(a.getIssueKey());
                a.setResultUrl(client.browseUrl(a.getIssueKey()));
            }
            a.setStatus("EXECUTED");
            a.setExecutedAt(LocalDateTime.now());
            repository.save(a);
            auditService.record("JIRA_EXECUTED", "action Jira #" + id, a.getType() + " -> " + a.getResultKey() + " (approuvée par " + user + ")");
        } catch (Exception e) {
            a.setStatus("FAILED");
            a.setError(e.getMessage());
            repository.save(a);
            auditService.record("JIRA_FAILED", "action Jira #" + id, String.valueOf(e.getMessage()));
        }
        return view(a);
    }

    public JiraModels.JiraActionView reject(Long id, JiraModels.DecisionRequest decision) {
        JiraActionRecord a = find(id);
        if (!"PROPOSED".equals(a.getStatus())) throw new IllegalStateException("Action déjà traitée (" + a.getStatus() + ")");
        if (decision == null || decision.comment() == null || decision.comment().isBlank()) {
            throw new IllegalArgumentException("Un rejet doit être motivé");
        }
        a.setStatus("REJECTED");
        a.setDecidedBy(CurrentUser.username());
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionComment(decision.comment());
        repository.save(a);
        auditService.record("JIRA_REJECTED", "action Jira #" + id, decision.comment());
        return view(a);
    }

    public List<JiraModels.JiraActionView> list(String status, String application) {
        return repository.findAllByOrderByProposedAtDesc().stream()
                .filter(a -> status == null || status.isBlank() || status.equalsIgnoreCase(a.getStatus()))
                .filter(a -> application == null || application.isBlank() || application.equals(a.getApplicationCode()))
                .map(this::view).toList();
    }

    // ----------------------------------------------------------- utilitaires

    private JiraActionRecord find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NoSuchElementException("Action Jira introuvable : " + id));
    }

    private String projectKey(String application, String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit.trim().toUpperCase(Locale.ROOT);
        Optional<String> fromApp = Optional.ofNullable(application).flatMap(applicationService::find).map(ManagedApplication::getJiraProjectKey);
        return fromApp.orElse(blankToNull(defaultProject));
    }

    static String fingerprint(JiraModels.IncidentQuery q) {
        String basis = q.incidentRef() != null && !q.incidentRef().isBlank() ? q.incidentRef() : nz(q.text());
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
            return "devsecops-" + HexFormat.of().formatHex(h, 0, 6);
        } catch (Exception e) {
            return "devsecops-" + Integer.toHexString(basis.hashCode());
        }
    }

    /** Mots-clés de recherche : codes d'erreur, CVE et exceptions d'abord, puis mots longs et informatifs. */
    static List<String> keywords(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = STRONG_TOKEN.matcher(text);
        while (m.find()) out.add(m.group(1));
        List<String> words = new ArrayList<>();
        for (String w : text.split("[^\\p{L}\\p{N}_.-]+")) {
            String t = w.replaceAll("^[.-]+|[.-]+$", "");
            if (t.length() < 5 || t.contains("#") || t.chars().allMatch(Character::isDigit) || STOP.contains(t.toLowerCase(Locale.ROOT))) continue;
            words.add(t);
        }
        words.sort(Comparator.comparingInt(String::length).reversed());
        out.addAll(words);
        return out.stream().limit(6).toList();
    }

    private static String text(JiraModels.IncidentQuery q) {
        String t = q.text() == null || q.text().isBlank() ? nz(q.incidentRef()) : q.text();
        return t.replace('#', ' ').trim();
    }

    private static String jqlEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Terme de recherche plein texte : les termes avec ponctuation sont cherchés comme une phrase. */
    private static String jqlTerm(String term) {
        String clean = term.replace("\"", "").replace("\\", "");
        return clean.matches("[\\p{L}\\p{N}_]+") ? clean : "\\\"" + clean + "\\\"";
    }

    private static String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "log_anomaly" -> "Anomalie de logs";
            case "test_failure" -> "Test en échec";
            case "vulnerability" -> "Vulnérabilité";
            case "incident" -> "Incident corrélé";
            default -> "Incident";
        };
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private JiraModels.JiraActionView view(JiraActionRecord a) {
        return new JiraModels.JiraActionView(a.getId(), a.getType(), a.getStatus(), a.getApplicationCode(), a.getProjectKey(),
                a.getIncidentType(), a.getIncidentRef(), a.getFingerprint(), a.getIssueKey(), a.getSummary(), a.getDescription(),
                a.getComment(), a.getRecommendationId(), a.getProposedBy(), a.getProposedAt(), a.getDecidedBy(), a.getDecidedAt(),
                a.getDecisionComment(), a.getExecutedAt(), a.getResultKey(), a.getResultUrl(), a.getError());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
