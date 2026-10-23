/**
 * Role : classer chaque log dans sa couche (applicative, serveur JBoss/WildFly, pipeline CI/CD).
 * Contenu : definition de LogTypeClassifier et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.model.LogEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Distingue trois couches de logs, car elles n'ont ni la même signification ni le même
 * propriétaire :
 * <ul>
 *   <li>APPLICATION : code métier de l'application (ses propres loggers) ;</li>
 *   <li>SERVER : serveur d'applications JBoss / WildFly (sous-systèmes, datasources, déploiement,
 *       transactions, Undertow...) — reconnu par la catégorie du logger ou le code de message
 *       (WFLY*, IJ, ARJUNA, UT...) ;</li>
 *   <li>CICD : pipeline d'intégration continue (Maven, Jenkins, GitLab CI...).</li>
 * </ul>
 * Règles ordonnées et déterministes : la classification est explicable et testable.
 */
public final class LogTypeClassifier {

    public static final String APPLICATION = "APPLICATION";
    public static final String SERVER = "SERVER";
    public static final String CICD = "CICD";
    public static final List<String> TYPES = List.of(APPLICATION, SERVER, CICD);

    /** Codes de messages JBoss / WildFly et de leurs composants embarqués. */
    private static final Pattern ERROR_CODE = Pattern.compile(
            "\\b(WFLY[A-Z]{0,6}\\d{4,5}|JBAS\\d{6}|IJ\\d{6}|ARJUNA\\d{6}|UT\\d{6}|ISPN\\d{6}|AMQ\\d{6}|WELD-\\d{6}"
                    + "|JBERET\\d{6}|RESTEASY\\d{6}|HHH\\d{6}|ORA-\\d{5}|SQLSTATE\\s?\\w{5})\\b");

    private static final Pattern SERVER_CODE = Pattern.compile(
            "\\b(WFLY[A-Z]{0,6}\\d{4,5}|JBAS\\d{6}|IJ\\d{6}|ARJUNA\\d{6}|UT\\d{6}|ISPN\\d{6}|AMQ\\d{6}|WELD-\\d{6})");

    /** Préfixes de catégories de loggers du serveur -> libellé court de sous-système. */
    private static final Map<String, String> SERVER_CATEGORIES = new LinkedHashMap<>();

    static {
        SERVER_CATEGORIES.put("org.jboss.as.controller", "wildfly-controller");
        SERVER_CATEGORIES.put("org.jboss.as.server.deployment", "wildfly-deployment");
        SERVER_CATEGORIES.put("org.jboss.as.server", "wildfly-server");
        SERVER_CATEGORIES.put("org.jboss.as.connector", "wildfly-datasources");
        SERVER_CATEGORIES.put("org.jboss.jca", "wildfly-jca");
        SERVER_CATEGORIES.put("org.jboss.as.ejb3", "wildfly-ejb3");
        SERVER_CATEGORIES.put("org.jboss.as.txn", "wildfly-transactions");
        SERVER_CATEGORIES.put("com.arjuna", "wildfly-transactions");
        SERVER_CATEGORIES.put("io.undertow", "wildfly-undertow");
        SERVER_CATEGORIES.put("org.wildfly.extension.undertow", "wildfly-undertow");
        SERVER_CATEGORIES.put("org.jboss.weld", "wildfly-weld");
        SERVER_CATEGORIES.put("org.jboss.as.jpa", "wildfly-jpa");
        SERVER_CATEGORIES.put("org.infinispan", "wildfly-infinispan");
        SERVER_CATEGORIES.put("org.jgroups", "wildfly-jgroups");
        SERVER_CATEGORIES.put("org.apache.activemq.artemis", "wildfly-messaging");
        SERVER_CATEGORIES.put("org.wildfly.security", "wildfly-security");
        SERVER_CATEGORIES.put("org.jboss.as.domain", "wildfly-domain");
        SERVER_CATEGORIES.put("org.jboss.modules", "wildfly-modules");
        SERVER_CATEGORIES.put("org.jboss.resteasy", "wildfly-resteasy");
        SERVER_CATEGORIES.put("org.jboss", "wildfly");
        SERVER_CATEGORIES.put("org.wildfly", "wildfly");
        SERVER_CATEGORIES.put("org.apache.catalina", "tomcat");
    }

    private static final Set<String> SERVER_SOURCES = Set.of("wildfly", "jboss", "jboss-eap", "server", "undertow",
            "tomcat", "server.log");

    private static final Set<String> CI_SOURCES = Set.of("jenkins", "gitlab", "gitlab-ci", "github-actions", "ci",
            "ci-pipeline", "pipeline", "build", "maven-build", "azure-pipelines");

    private static final Pattern CI_MESSAGE = Pattern.compile(
            "BUILD FAILURE|BUILD SUCCESS|Failed to execute goal|COMPILATION ERROR|There are test failures"
                    + "|Tests run: \\d+, Failures: \\d+|\\[Pipeline]|Finished: (FAILURE|SUCCESS|UNSTABLE|ABORTED)"
                    + "|ERROR: Job failed|Job succeeded|Running with gitlab-runner|npm ERR!|Reactor Summary"
                    + "|quality gate|docker build|Pushing image");

    private LogTypeClassifier() {
    }

    /** Couche d'un log : celle déjà fixée (parseur, paramètre d'ingestion) ou déduite par règles. */
    public static String classify(LogEntry entry) {
        if (entry == null) return APPLICATION;
        if (entry.getLogType() != null && TYPES.contains(entry.getLogType())) return entry.getLogType();
        return classify(entry.getSource(), entry.getMessage());
    }

    public static String classify(String source, String message) {
        String src = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        String msg = message == null ? "" : message;
        if (CI_SOURCES.contains(src) || src.startsWith("ci-") || src.startsWith("jenkins")) return CICD;
        if (SERVER_SOURCES.contains(src) || src.startsWith("wildfly") || src.startsWith("jboss")) return SERVER;
        if (serverSubsystem(source).isPresent()) return SERVER;
        if (SERVER_CODE.matcher(msg).find()) return SERVER;
        if (CI_MESSAGE.matcher(msg).find()) return CICD;
        return APPLICATION;
    }

    /** Libellé court du sous-système serveur pour une catégorie de logger JBoss/WildFly. */
    public static Optional<String> serverSubsystem(String category) {
        if (category == null) return Optional.empty();
        String c = category.trim();
        for (Map.Entry<String, String> e : SERVER_CATEGORIES.entrySet()) {
            if (c.startsWith(e.getKey())) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    /** Premier code d'erreur reconnu dans un message (WFLYCTL0013, IJ000453, ORA-00942...), sinon vide. */
    public static Optional<String> errorCode(String message) {
        if (message == null) return Optional.empty();
        Matcher m = ERROR_CODE.matcher(message);
        return m.find() ? Optional.of(m.group(1).replace(" ", "")) : Optional.empty();
    }

    static Pattern errorCodePattern() {
        return ERROR_CODE;
    }

    public static String label(String type) {
        return switch (type == null ? APPLICATION : type) {
            case SERVER -> "Serveur (JBoss/WildFly)";
            case CICD -> "Pipeline CI/CD";
            default -> "Applicatif";
        };
    }
}
