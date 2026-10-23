/**
 * Role : lancer les scans d images et recuperer leurs resultats Trivy.
 * Contenu : definition de TrivyScanService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Déclenche un scan Trivy d'une image de conteneur et récupère la sortie JSON
 * (stdout). Prérequis : binaire trivy dans le PATH (embarqué dans l'image Docker
 * du backend) et accès à l'image (démon Docker local ou registre).
 */
@Service
public class TrivyScanService {

    @Value("${assistant.security.trivy.image-name:assistant-devsecops-backend:latest}")
    private String defaultImage;

    @Value("${assistant.security.scan-timeout-seconds:1800}")
    private int timeoutSeconds;

    public record TrivyScanResult(boolean success, String jsonOutput, String errorMessage) { }

    public String getDefaultImage() {
        return defaultImage;
    }

    public TrivyScanResult scanImage(String image) {
        String target = image == null || image.isBlank() ? defaultImage : image.trim();
        if (!target.matches("[\\w./:@-]+")) {
            return new TrivyScanResult(false, null, "Nom d'image invalide : " + target);
        }
        try {
            ProcessRunner.Result r = ProcessRunner.run(
                    List.of("trivy", "image", "--format", "json", "--quiet", "--scanners", "vuln", target),
                    null, timeoutSeconds, false);
            if (r.timedOut()) {
                return new TrivyScanResult(false, null, "Timeout : le scan Trivy a dépassé " + timeoutSeconds + "s");
            }
            if (r.exitCode() != 0) {
                return new TrivyScanResult(false, null, "Trivy a renvoyé une erreur : " + ProcessRunner.tail(r.stderr(), 800));
            }
            return new TrivyScanResult(true, r.stdout(), null);
        } catch (IOException e) {
            return new TrivyScanResult(false, null,
                    "Impossible de lancer Trivy (est-il installé et dans le PATH ?) : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TrivyScanResult(false, null, "Scan interrompu");
        }
    }
}
