package com.vermeg.assistant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * État des prérequis des traitements lancés depuis l'interface (Maven, Trivy,
 * démon Docker, SonarQube, clés d'API). Permet aux écrans Tests et Sécurité
 * d'indiquer AVANT le clic ce qui fonctionnera et, sinon, quoi faire à la place
 * (importer un rapport). Résultat mis en cache : lancer "mvn -v" à chaque
 * affichage de page serait inutilement coûteux.
 */
@Service
public class ToolStatusService {

    private static final long CACHE_MILLIS = 60_000;

    public record Tool(String label, boolean available, String detail, String hint) { }

    private final SonarIssueFetcher sonarIssueFetcher;

    @Value("${assistant.tests.project-path:.}")
    private String testsProjectPath;

    @Value("${assistant.security.trivy.image-name:assistant-devsecops-backend:latest}")
    private String defaultImage;

    @Value("${assistant.ci.api-key:}")
    private String ciApiKey;

    private volatile Map<String, Tool> cached;
    private volatile long cachedAt;

    public ToolStatusService(SonarIssueFetcher sonarIssueFetcher) {
        this.sonarIssueFetcher = sonarIssueFetcher;
    }

    public Map<String, Tool> status(boolean refresh) {
        Map<String, Tool> current = cached;
        if (!refresh && current != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
            return current;
        }
        Map<String, Tool> result = new LinkedHashMap<>();
        result.put("maven", probe("Maven", List.of(ProcessRunner.mavenCommand(), "-v"),
                "Installez Maven et ajoutez-le au PATH, ou importez des rapports JUnit XML."));
        result.put("project", project());
        result.put("trivy", probe("Trivy", List.of("trivy", "--version"),
                "Installez Trivy (ou utilisez l'image Docker du backend), ou importez un rapport Trivy JSON."));
        result.put("docker", docker());
        result.put("sonarqube", sonar());
        result.put("nvdApiKey", nvdKey());
        result.put("ciApiKey", new Tool("Clé d'API CI/CD", ciApiKey != null && !ciApiKey.isBlank(),
                ciApiKey != null && !ciApiKey.isBlank() ? "configurée" : "non configurée",
                "Définissez CI_API_KEY pour que le pipeline publie ses résultats sur /api/ci/**."));
        cached = result;
        cachedAt = System.currentTimeMillis();
        return result;
    }

    public String defaultImage() {
        return defaultImage;
    }

    private Tool probe(String label, List<String> command, String hint) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(command, null, 20, true);
            if (r.success()) {
                return new Tool(label, true, firstLine(r.stdout()), null);
            }
            return new Tool(label, false, r.timedOut() ? "pas de réponse" : "erreur au lancement", hint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Tool(label, false, "vérification interrompue", hint);
        } catch (Exception e) {
            return new Tool(label, false, "introuvable dans le PATH", hint);
        }
    }

    private Tool project() {
        Path root = Path.of(testsProjectPath).toAbsolutePath().normalize();
        boolean hasPom = Files.isRegularFile(root.resolve("pom.xml"));
        return new Tool("Projet analysé", hasPom, root.toString(),
                hasPom ? null : "Aucun pom.xml à cet emplacement : définissez TESTS_PROJECT_PATH et SECURITY_PROJECT_PATH.");
    }

    private Tool docker() {
        Path socket = Path.of("/var/run/docker.sock");
        boolean readable = Files.exists(socket) && Files.isReadable(socket);
        String detail = !Files.exists(socket) ? "socket Docker absent (images de registre uniquement)"
                : readable ? "démon Docker accessible (images locales)" : "socket Docker présent mais non lisible";
        return new Tool("Démon Docker", readable, detail,
                readable ? null : "Sans accès au démon Docker, Trivy ne scanne que les images d'un registre "
                        + "(ex. nginx:1.27). Sous Docker Desktop, mettez DOCKER_GID=0 dans .env.");
    }

    private Tool sonar() {
        String status = String.valueOf(sonarIssueFetcher.health().get("status"));
        return switch (status) {
            case "UP" -> new Tool("SonarQube", true, "joignable", null);
            case "DISABLED" -> new Tool("SonarQube", false, "désactivé",
                    "Lancez « docker compose --profile sonar up -d » puis définissez SONAR_ENABLED=true et SONAR_TOKEN.");
            default -> new Tool("SonarQube", false, "injoignable",
                    "Vérifiez SONAR_HOST_URL et que SonarQube est démarré.");
        };
    }

    private Tool nvdKey() {
        String key = System.getenv("NVD_API_KEY");
        boolean present = key != null && !key.isBlank();
        return new Tool("Clé d'API NVD", present, present ? "configurée" : "non configurée",
                present ? null : "Sans clé, le premier scan OWASP télécharge la base NVD très lentement "
                        + "(20 à 60 min). Clé gratuite : https://nvd.nist.gov/developers/request-an-api-key");
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > 120 ? line.substring(0, 120) : line;
    }
}
