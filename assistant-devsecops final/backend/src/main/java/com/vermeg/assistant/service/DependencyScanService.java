/**
 * Role : lancer ou recuperer les analyses de dependances du projet.
 * Contenu : definition de DependencyScanService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Déclenche OWASP Dependency-Check sur le projet surveillé. Le premier lancement
 * télécharge la base NVD (long) ; une clé NVD_API_KEY accélère fortement la mise à jour.
 */
@Service
public class DependencyScanService {

    @Value("${assistant.security.project-path:.}")
    private String projectPath;

    @Value("${assistant.security.scan-timeout-seconds:1800}")
    private int timeoutSeconds;

    public record ScanResult(boolean success, int exitCode, String output) { }

    public ScanResult runDependencyCheck() {
        // La clé NVD n'est pas passée en argument (elle serait visible dans la liste des processus) :
        // le pom.xml la lit dans la variable d'environnement NVD_API_KEY, héritée par le processus Maven.
        List<String> cmd = new ArrayList<>(List.of(ProcessRunner.mavenCommand(), "-B",
                "org.owasp:dependency-check-maven:check", "-Dformat=JSON"));
        try {
            ProcessRunner.Result r = ProcessRunner.run(cmd, Path.of(projectPath).toAbsolutePath().toFile(),
                    timeoutSeconds, true);
            if (r.timedOut()) {
                return new ScanResult(false, -1, "Timeout : le scan a dépassé " + timeoutSeconds
                        + "s (le premier lancement télécharge la base NVD).");
            }
            return new ScanResult(r.exitCode() == 0, r.exitCode(), r.stdout());
        } catch (IOException e) {
            return new ScanResult(false, -1, "Impossible de lancer Maven : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScanResult(false, -1, "Scan interrompu");
        }
    }
}
