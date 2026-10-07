package com.vermeg.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.model.Vulnerability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consulte l'API Web de SonarQube (après "mvn sonar:sonar") pour récupérer les
 * vulnérabilités et bugs du code (SAST), avec pagination.
 */
@Service
public class SonarIssueFetcher {

    @Value("${assistant.security.sonar.enabled:true}")
    private boolean enabled;

    @Value("${assistant.security.sonar.host-url:http://localhost:9000}")
    private String sonarHostUrl;

    @Value("${assistant.security.sonar.project-key:assistant-devsecops-backend}")
    private String projectKey;

    @Value("${assistant.security.sonar.token:}")
    private String sonarToken;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return enabled;
    }

    /** Vide si Sonar est désactivé/injoignable (distinct d'une liste vide = aucune issue). */
    public Optional<List<Vulnerability>> fetchIssuesIfAvailable() {
        if (!enabled) return Optional.empty();
        List<Vulnerability> vulnerabilities = new ArrayList<>();
        try {
            int page = 1;
            int total;
            do {
                String url = sonarHostUrl + "/api/issues/search?componentKeys="
                        + URLEncoder.encode(projectKey, StandardCharsets.UTF_8)
                        + "&types=VULNERABILITY,BUG&resolved=false&ps=500&p=" + page;
                HttpResponse<String> response = httpClient.send(request(url), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return Optional.empty();
                }
                JsonNode root = objectMapper.readTree(response.body());
                for (JsonNode issue : root.path("issues")) {
                    vulnerabilities.add(SonarIssueMapper.toVulnerability(issue));
                }
                total = root.path("paging").path("total").asInt(root.path("total").asInt(0));
                page++;
            } while ((page - 1) * 500 < total && page <= 20);
            return Optional.of(vulnerabilities);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Compatibilité : liste vide si Sonar est indisponible. */
    public List<Vulnerability> fetchIssues() {
        return fetchIssuesIfAvailable().orElseGet(ArrayList::new);
    }

    public Map<String, Object> health() {
        if (!enabled) return Map.of("status", "DISABLED");
        try {
            HttpResponse<String> response = httpClient.send(request(sonarHostUrl + "/api/system/status"),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Map.of("status", "DOWN");
            String status = objectMapper.readTree(response.body()).path("status").asText("UNKNOWN");
            return Map.of("status", "UP".equals(status) ? "UP" : "DOWN", "detail", status);
        } catch (Exception e) {
            return Map.of("status", "DOWN");
        }
    }

    private HttpRequest request(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET();
        if (sonarToken != null && !sonarToken.isBlank()) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((sonarToken + ":").getBytes(StandardCharsets.UTF_8)));
        }
        return builder.build();
    }
}
