/**
 * Role : correler les erreurs des differentes couches (applicatif, serveur, CI) et designer une cause racine explicable.
 * Contenu : definition de IncidentCorrelationEngine et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.model.Diagnostics.CausalLink;
import com.vermeg.assistant.model.Diagnostics.CorrelatedIncident;
import com.vermeg.assistant.model.Diagnostics.ExplanationFactor;
import com.vermeg.assistant.model.Diagnostics.IncidentSignal;
import com.vermeg.assistant.model.Diagnostics.RootCauseCandidate;
import com.vermeg.assistant.model.Diagnostics.TestFailureRef;
import com.vermeg.assistant.model.LogEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Corrélation intelligente des anomalies et diagnostic explicable.
 * <ol>
 *   <li><b>Sessionisation</b> : les erreurs (ERROR/FATAL, et les WARN du serveur) sont découpées en
 *       incidents : une rafale se termine après {@code gapMinutes} sans nouvelle erreur.</li>
 *   <li><b>Signaux</b> : dans un incident, les erreurs sont regroupées par message normalisé et couche.</li>
 *   <li><b>Cause racine</b> : chaque signal reçoit un score = somme pondérée de 5 facteurs explicables
 *       (précédence temporelle, règles causales du domaine, couche, exception racine, gravité).</li>
 *   <li><b>Chaîne causale</b> : liens cause → effet issus d'une base de règles JBoss/WildFly, base de
 *       données, réseau, mémoire, CI ; à défaut, simple succession temporelle (signalée comme telle).</li>
 * </ol>
 * Déterministe et sans appel externe : le résultat est reproductible, auditable, et sert de
 * contexte « ancré » au LLM (qui ne fait que reformuler et compléter).
 */
@Service
public class IncidentCorrelationEngine {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");

    // Poids des facteurs (somme = 1) : documentés et affichés à l'utilisateur.
    static final double W_TEMPORAL = 0.30;
    static final double W_CAUSAL = 0.30;
    static final double W_LAYER = 0.15;
    static final double W_ROOT_EXCEPTION = 0.15;
    static final double W_SEVERITY = 0.10;

    @Value("${assistant.correlation.gap-minutes:5}")
    private int gapMinutes = 5;

    @Value("${assistant.correlation.min-events:2}")
    private int minEvents = 2;

    @Value("${assistant.correlation.max-incidents:20}")
    private int maxIncidents = 20;

    /** Règle de connaissance : un signal « cause » explique des signaux « effet » ultérieurs. */
    record CausalRule(String code, String label, String category, Pattern cause, Pattern effect, String explanation) {
        boolean isCause(String text) {
            return cause.matcher(text).find();
        }

        boolean isEffect(String text) {
            return effect.matcher(text).find();
        }
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    static final List<CausalRule> RULES = List.of(
            new CausalRule("DB_UNAVAILABLE", "Base de données indisponible", "DATABASE",
                    p("IJ000453|unable to get managed connection|could not connect to (the )?database|communications link failure"
                            + "|the connection attempt failed|PSQLException: Connection|SQLRecoverableException|ORA-125(41|14)"
                            + "|datasource.{0,40}(unavailable|down|failed)|connection refused.{0,60}(5432|1521|3306|db|database|postgres|oracle)"),
                    p("hikari|jdbc|\\bsql|transaction|entitymanager|jpa|hibernate|persistence|repository|connection is not available|could not open"),
                    "La base de données (ou sa datasource JBoss) est injoignable : toutes les opérations de persistance qui suivent échouent."),
            new CausalRule("DB_POOL_SATURATION", "Pool de connexions saturé", "DATABASE",
                    p("connection is not available|pool (is )?exhausted|hikaripool.{0,40}timed out|IJ000655|maximum pool size|no managed connections available"),
                    p("timeout|timed out|\\b503\\b|service unavailable|request failed|transaction|rollback|slow"),
                    "Le pool de connexions est saturé : les requêtes attendent puis expirent, ce qui dégrade les services appelants."),
            new CausalRule("TX_TIMEOUT", "Transaction expirée (gestionnaire de transactions)", "DATABASE",
                    p("ARJUNA012117|ARJUNA012121|transaction ?reaper|transaction timeout|TransactionRolledbackException"),
                    p("rollback|rolled back|transaction|persist|commit|EJBTransactionRolledback"),
                    "Le gestionnaire de transactions du serveur annule une transaction trop longue : les opérations métier associées échouent."),
            new CausalRule("OUT_OF_MEMORY", "Saturation mémoire de la JVM", "RESOURCE",
                    p("OutOfMemoryError|java heap space|GC overhead limit|Metaspace"),
                    p(".+"),
                    "La JVM manque de mémoire : tout traitement ultérieur peut échouer de façon imprévisible."),
            new CausalRule("DISK_FULL", "Disque plein", "RESOURCE",
                    p("No space left on device|disk full|insufficient disk"),
                    p(".+"),
                    "Le disque est plein : écritures de fichiers, de logs et de base échouent."),
            new CausalRule("DEPLOYMENT_FAILURE", "Échec de déploiement sur le serveur", "DEPLOYMENT",
                    p("WFLYCTL0013|WFLYCTL0180|WFLYCTL0186|WFLYSRV0026|DeploymentUnitProcessingException|deployment .{0,60} failed"
                            + "|failed to (start|deploy) (service|deployment)|services with missing.{0,20}dependencies"),
                    p("\\b404\\b|not found|ClassNotFoundException|NoClassDefFoundError|NameNotFoundException|UT005023"
                            + "|WELD-001408|unsatisfied dependencies|not (available|installed)|lookup failed"),
                    "Le déploiement (ou un service du serveur) n'a pas démarré correctement : les composants qui en dépendent sont absents."),
            new CausalRule("TLS_CERTIFICATE", "Problème de certificat TLS", "SECURITY",
                    p("PKIX|SSLHandshakeException|handshake_failure|unable to find valid certification path|certificate (expired|has expired|not yet valid)"),
                    p("connection|upstream|\\b50[234]\\b|refused|failed to call|unable to reach|timeout|I/O error"),
                    "La négociation TLS échoue (certificat expiré ou non reconnu) : les appels vers le partenaire concerné sont interrompus."),
            new CausalRule("UPSTREAM_DOWN", "Service distant injoignable", "NETWORK",
                    p("connection refused|UnknownHostException|no route to host|unable to reach|connect timed out|503 service unavailable"),
                    p("fallback|circuit|retry|timeout|timed out|failed|exception|\\b50[234]\\b"),
                    "Un service distant est injoignable : les appels qui en dépendent échouent ou basculent en mode dégradé."),
            new CausalRule("CONFIGURATION", "Configuration manquante ou invalide", "CONFIGURATION",
                    p("could not resolve placeholder|missing (required )?(property|configuration)|NameNotFoundException|BeanCreationException"
                            + "|UnsatisfiedDependencyException|no such (property|bean)|invalid configuration"),
                    p("failed to start|application run failed|deployment|\\b404\\b|startup|context initialization failed"),
                    "Un paramètre ou une ressource de configuration est absent(e) : le démarrage ou le déploiement échoue."),
            new CausalRule("AUTH_KEYS", "Clés / jetons d'authentification invalides", "SECURITY",
                    p("invalid (jwt |token )?signature|signature does not match|keystore|token .{0,30}(invalid|expired)|jwt .{0,30}(invalid|expired)"),
                    p("\\b401\\b|unauthorized|forbidden|\\b403\\b|access denied|authentication failed"),
                    "Les jetons ou clés de signature ne correspondent pas : les requêtes authentifiées sont rejetées."),
            new CausalRule("BUILD_COMPILATION", "Erreur de compilation", "BUILD",
                    p("COMPILATION ERROR|cannot find symbol|Failed to execute goal .{0,80}(compiler|compile)"),
                    p("BUILD FAILURE|Finished: FAILURE|Job failed|FAILURE: Build failed|Reactor Summary"),
                    "Le code ne compile pas : le pipeline échoue avant même l'exécution des tests."),
            new CausalRule("BUILD_TESTS", "Tests en échec dans le pipeline", "BUILD",
                    p("There are test failures|Tests run: \\d+, Failures: [1-9]|Tests run: \\d+, Failures: \\d+, Errors: [1-9]|maven-surefire-plugin.{0,60}failed"),
                    p("BUILD FAILURE|Finished: (FAILURE|UNSTABLE)|Job failed|quality gate"),
                    "Des tests échouent : le pipeline marque le build en échec (ou instable)."));

    /** Exceptions qui, en tant que cause première ("Caused by"), désignent généralement une cause racine. */
    private static final Pattern ROOT_EXCEPTIONS = p(
            "(SQLException|SQLRecoverableException|PSQLException|ConnectException|UnknownHostException|SocketTimeoutException"
                    + "|NoRouteToHostException|OutOfMemoryError|SSLHandshakeException|CertificateException|CertPathValidatorException"
                    + "|NamingException|NameNotFoundException|ClassNotFoundException|NoClassDefFoundError|FileNotFoundException"
                    + "|AccessDeniedException|ResourceException|DeploymentUnitProcessingException)");

    public void configure(int gapMinutes, int minEvents, int maxIncidents) {
        this.gapMinutes = gapMinutes;
        this.minEvents = minEvents;
        this.maxIncidents = maxIncidents;
    }

    public List<CorrelatedIncident> correlate(List<LogEntry> logs) {
        return correlate(logs, List.of());
    }

    public List<CorrelatedIncident> correlate(List<LogEntry> logs, List<TestFailureRef> failures) {
        List<LogEntry> events = logs.stream()
                .filter(e -> e.getTimestamp() != null && e.getLevel() != null)
                .filter(this::isRelevant)
                .sorted(Comparator.comparing(LogEntry::getTimestamp))
                .toList();
        List<List<LogEntry>> sessions = new ArrayList<>();
        List<LogEntry> current = new ArrayList<>();
        LocalDateTime lastAt = null;
        for (LogEntry e : events) {
            if (lastAt != null && Duration.between(lastAt, e.getTimestamp()).toMinutes() >= gapMinutes) {
                sessions.add(current);
                current = new ArrayList<>();
            }
            current.add(e);
            lastAt = e.getTimestamp();
        }
        if (!current.isEmpty()) sessions.add(current);

        List<CorrelatedIncident> incidents = new ArrayList<>();
        for (List<LogEntry> session : sessions) {
            boolean fatal = session.stream().anyMatch(e -> e.getLevel() == LogEntry.LogLevel.FATAL);
            if (session.size() < minEvents && !fatal) continue;
            incidents.add(buildIncident(session, failures == null ? List.of() : failures));
        }
        incidents.sort(Comparator.comparingInt((CorrelatedIncident i) -> severityRank(i.severity())).reversed()
                .thenComparing(CorrelatedIncident::start, Comparator.reverseOrder()));
        return incidents.stream().limit(maxIncidents).toList();
    }

    /** Erreurs, et avertissements du serveur ou correspondant à une cause connue (ex. pool saturé). */
    private boolean isRelevant(LogEntry e) {
        if (e.getLevel().isErrorLike()) return true;
        if (e.getLevel() != LogEntry.LogLevel.WARN) return false;
        if (LogTypeClassifier.SERVER.equals(LogTypeClassifier.classify(e))) return true;
        String text = text(e.getMessage(), null);
        return RULES.stream().anyMatch(r -> r.isCause(text));
    }

    private CorrelatedIncident buildIncident(List<LogEntry> session, List<TestFailureRef> failures) {
        // 1. Signaux : regroupement par message normalisé + couche, ordonnés par première apparition
        Map<String, List<LogEntry>> grouped = new LinkedHashMap<>();
        for (LogEntry e : session) {
            String layer = LogTypeClassifier.classify(e);
            grouped.computeIfAbsent(LogAnalysisService.keyOf(LogAnalysisService.normalize(e.getMessage())) + "|" + layer,
                    k -> new ArrayList<>()).add(e);
        }
        List<IncidentSignal> signals = new ArrayList<>();
        Map<String, String> stackByKey = new LinkedHashMap<>();
        for (Map.Entry<String, List<LogEntry>> g : grouped.entrySet()) {
            List<LogEntry> occ = g.getValue();
            LogEntry first = occ.get(0);
            LogEntry.LogLevel level = occ.stream().map(LogEntry::getLevel)
                    .max(Comparator.comparingInt(LogEntry.LogLevel::ordinal)).orElse(first.getLevel());
            String normalized = LogAnalysisService.normalize(first.getMessage());
            String stack = occ.stream().map(LogEntry::getStackTrace).filter(Objects::nonNull).findFirst().orElse(null);
            String key = g.getKey().substring(0, g.getKey().indexOf('|'));
            stackByKey.put(key + "|" + LogTypeClassifier.classify(first), stack);
            signals.add(new IncidentSignal(key, normalized, LogTypeClassifier.classify(first), first.getSource(),
                    level.name(), LogTypeClassifier.errorCode(first.getMessage()).orElse(null),
                    AnomalyCategorizer.categorize(first.getMessage(), stack), occ.size(), first.getTimestamp(),
                    occ.get(occ.size() - 1).getTimestamp(), truncate(first.getMessage(), 300)));
        }
        signals.sort(Comparator.comparing(IncidentSignal::firstSeen));

        // 2. Score explicable de chaque signal comme cause racine
        List<RootCauseCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < signals.size(); i++) {
            candidates.add(score(signals, i, stackByKey.get(signals.get(i).key() + "|" + signals.get(i).layer())));
        }
        candidates.sort(Comparator.comparingDouble(RootCauseCandidate::score).reversed());
        RootCauseCandidate top = candidates.get(0);
        double runnerUp = candidates.size() > 1 ? candidates.get(1).score() : 0.0;
        // Confiance : score du meilleur candidat, modulé par son avance sur le second (ambiguïté).
        double margin = top.score() <= 0 ? 0 : Math.min(1.0, (top.score() - runnerUp) / 0.2);
        int confidence = (int) Math.round(100 * clamp(top.score() * (0.7 + 0.3 * margin)));
        RootCauseCandidate best = new RootCauseCandidate(top.signal(), top.score(), confidence, top.factors(), top.rule(),
                top.rationale() + (candidates.size() > 1 && margin < 0.5
                        ? " Attention : un autre signal obtient un score proche, le diagnostic reste à confirmer." : ""));

        // 3. Chaîne causale (règles de connaissance, sinon succession temporelle)
        List<CausalLink> chain = buildChain(signals, best.signal(), best.rule());

        LocalDateTime start = session.get(0).getTimestamp();
        LocalDateTime end = session.get(session.size() - 1).getTimestamp();
        Set<String> layers = signals.stream().map(IncidentSignal::layer).collect(Collectors.toCollection(TreeSet::new));
        Set<String> sources = session.stream().map(LogEntry::getSource).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        boolean fatal = session.stream().anyMatch(e -> e.getLevel() == LogEntry.LogLevel.FATAL);
        long errors = session.stream().filter(e -> e.getLevel().isErrorLike()).count();
        String severity = fatal || (layers.size() >= 2 && session.size() >= 10) ? "CRITICAL"
                : layers.size() >= 2 || errors >= 5 ? "HIGH" : errors >= 2 ? "MEDIUM" : "LOW";
        String category = best.rule() != null
                ? RULES.stream().filter(r -> r.code().equals(best.rule())).map(CausalRule::category).findFirst().orElse(best.signal().category())
                : best.signal().category();

        List<String> impacted = impactedTests(signals, failures);
        String id = "incident-" + LogAnalysisService.keyOf(start + "|" + best.signal().key()).substring("anomaly-".length());
        String summary = String.format(Locale.FRANCE,
                "Incident de %s (%s → %s), %d événement(s) sur %d couche(s) (%s) et %d service(s). Cause racine probable : [%s] %s (confiance %d %%).%s",
                formatDuration(Duration.between(start, end)), start.format(HM), end.format(HM), session.size(), layers.size(),
                layers.stream().map(LogTypeClassifier::label).collect(Collectors.joining(", ")), sources.size(),
                LogTypeClassifier.label(best.signal().layer()), truncate(best.signal().message(), 140), best.confidencePercent(),
                impacted.isEmpty() ? "" : " " + impacted.size() + " test(s) en échec probablement liés.");
        List<RootCauseCandidate> alternatives = candidates.stream().skip(1).limit(2).toList();
        return new CorrelatedIncident(id, start, end, severity, layers, sources, session.size(), signals, best,
                alternatives, chain, impacted, category, summary);
    }

    private RootCauseCandidate score(List<IncidentSignal> signals, int index, String stack) {
        IncidentSignal s = signals.get(index);
        // Les règles s'appliquent au texte brut (le message normalisé a perdu ses chiffres).
        String text = text(s.example(), stack);
        int n = signals.size();
        List<ExplanationFactor> factors = new ArrayList<>();

        // Précédence temporelle : la cause précède ses effets.
        double temporal = n == 1 ? 1.0 : 1.0 - (double) index / (n - 1);
        factors.add(factor("TEMPORAL", "Précédence temporelle", temporal, W_TEMPORAL,
                index == 0 ? "premier signal de l'incident (" + s.firstSeen().format(HM) + ")"
                        : (index + 1) + "e signal sur " + n + " par ordre d'apparition"));

        // Règles causales : combien des autres signaux sont des effets connus de ce signal ?
        CausalRule matchedRule = null;
        int explained = 0;
        for (CausalRule rule : RULES) {
            if (!rule.isCause(text)) continue;
            int count = 0;
            for (int j = 0; j < n; j++) {
                if (j == index) continue;
                IncidentSignal other = signals.get(j);
                if (!other.firstSeen().isBefore(s.firstSeen()) && rule.isEffect(other.example())) count++;
            }
            if (matchedRule == null || count > explained) {
                matchedRule = rule;
                explained = count;
            }
        }
        double causal = matchedRule == null ? 0.0 : n == 1 ? 0.6 : Math.min(1.0, 0.4 + 0.6 * explained / (double) (n - 1));
        factors.add(factor("CAUSAL_RULE", "Règle causale du domaine", causal, W_CAUSAL,
                matchedRule == null ? "aucune règle de cause connue ne correspond"
                        : "règle « " + matchedRule.label() + " » : explique " + explained + " autre(s) signal(aux)"));

        // Couche : une défaillance d'infrastructure (serveur) précède souvent les erreurs applicatives.
        double layer = switch (s.layer()) {
            case LogTypeClassifier.SERVER -> 1.0;
            case LogTypeClassifier.CICD -> 0.8;
            default -> 0.5;
        };
        factors.add(factor("LAYER", "Couche d'origine", layer, W_LAYER, LogTypeClassifier.label(s.layer())));

        // Exception racine ("Caused by:") de type infrastructure.
        String rootException = rootCause(stack);
        double rootEx = rootException != null && ROOT_EXCEPTIONS.matcher(rootException).find() ? 1.0
                : ROOT_EXCEPTIONS.matcher(s.example()).find() ? 0.6 : 0.0;
        factors.add(factor("ROOT_EXCEPTION", "Exception racine", rootEx, W_ROOT_EXCEPTION,
                rootException != null ? "cause première : " + truncate(rootException, 120)
                        : rootEx > 0 ? "exception d'infrastructure citée dans le message" : "pas d'exception d'infrastructure"));

        double severity = switch (s.level()) {
            case "FATAL" -> 1.0;
            case "ERROR" -> 0.7;
            default -> 0.3;
        };
        factors.add(factor("SEVERITY", "Gravité", severity, W_SEVERITY, "niveau " + s.level()));

        double score = round(factors.stream().mapToDouble(ExplanationFactor::contribution).sum());
        String rationale = (index == 0 ? "Premier signal de l'incident" : "Signal apparu en position " + (index + 1))
                + ", couche " + LogTypeClassifier.label(s.layer()).toLowerCase(Locale.FRANCE)
                + (matchedRule != null ? ", correspond à la règle « " + matchedRule.label() + " » ("
                + matchedRule.explanation() + ")" : "")
                + (rootException != null ? ", cause première « " + truncate(rootException, 80) + " »" : "") + ".";
        return new RootCauseCandidate(s, score, (int) Math.round(score * 100), factors,
                matchedRule == null ? null : matchedRule.code(), rationale);
    }

    private List<CausalLink> buildChain(List<IncidentSignal> signals, IncidentSignal root, String ruleCode) {
        List<CausalLink> chain = new ArrayList<>();
        Optional<CausalRule> rule = RULES.stream().filter(r -> r.code().equals(ruleCode)).findFirst();
        for (IncidentSignal other : signals) {
            if (other == root || other.firstSeen().isBefore(root.firstSeen())) continue;
            if (rule.isPresent() && rule.get().isEffect(other.example())) {
                chain.add(new CausalLink(root.key(), label(root), other.key(), label(other), rule.get().code(),
                        rule.get().explanation()));
            }
        }
        if (chain.isEmpty()) {
            // Pas de règle connue : on présente la succession temporelle, explicitement qualifiée.
            IncidentSignal previous = null;
            for (IncidentSignal s : signals) {
                if (previous != null && chain.size() < 5) {
                    chain.add(new CausalLink(previous.key(), label(previous), s.key(), label(s), "TEMPORAL",
                            "Succession temporelle observée (aucune règle causale connue : lien à confirmer)."));
                }
                previous = s;
            }
        }
        return chain;
    }

    private List<String> impactedTests(List<IncidentSignal> signals, List<TestFailureRef> failures) {
        List<String> impacted = new ArrayList<>();
        for (TestFailureRef f : failures) {
            String failureText = (f.testName() == null ? "" : f.testName().replaceAll("([a-z])([A-Z])", "$1 $2")) + " "
                    + (f.message() == null ? "" : f.message()) + " " + (f.stackTrace() == null ? "" : truncate(f.stackTrace(), 600));
            for (IncidentSignal s : signals) {
                boolean sameCode = s.errorCode() != null && failureText.contains(s.errorCode());
                String exception = exceptionName(s.example());
                boolean sameException = exception != null && failureText.contains(exception);
                if (sameCode || sameException || TextSimilarityUtil.jaccardSimilarity(failureText, s.message()) >= 0.2) {
                    impacted.add(f.ref());
                    break;
                }
            }
        }
        return impacted;
    }

    // ----------------------------------------------------------------- utilitaires

    private static ExplanationFactor factor(String code, String label, double value, double weight, String detail) {
        return new ExplanationFactor(code, label, round(value), weight, round(value * weight), detail);
    }

    static String rootCause(String stack) {
        if (stack == null) return null;
        String last = null;
        for (String line : stack.split("\n")) {
            String l = line.strip();
            if (l.startsWith("Caused by:")) last = l.substring("Caused by:".length()).strip();
        }
        return last;
    }

    private static String exceptionName(String message) {
        if (message == null) return null;
        java.util.regex.Matcher m = Pattern.compile("([A-Z][A-Za-z0-9]*(Exception|Error))").matcher(message);
        return m.find() ? m.group(1) : null;
    }

    private static String text(String message, String stack) {
        return (message == null ? "" : message) + (stack == null ? "" : "\n" + truncate(stack, 2000));
    }

    private static String label(IncidentSignal s) {
        return "[" + LogTypeClassifier.label(s.layer()) + "] " + truncate(s.message(), 90);
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            case "MEDIUM" -> 1;
            default -> 0;
        };
    }

    private static String formatDuration(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min";
        return (s / 3600) + " h " + (s % 3600) / 60 + " min";
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
