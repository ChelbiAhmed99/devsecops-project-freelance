/**
 * Role : appeler l'API REST de Jira (Cloud ou Data Center) pour rechercher, creer et commenter des tickets.
 * Contenu : definition de JiraClient et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vermeg.assistant.model.JiraModels;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client Jira minimal (HttpClient du JDK, aucune dépendance) :
 * <ul>
 *   <li>Cloud : recherche via {@code /rest/api/3/search/jql} (l'ancien {@code /rest/api/2/search} a été retiré
 *       par Atlassian), authentification e-mail + jeton d'API (Basic) ;</li>
 *   <li>Data Center : recherche via {@code /rest/api/2/search}, jeton d'accès personnel (Bearer).</li>
 * </ul>
 * Création et commentaire passent par l'API v2, qui accepte du texte simple sur les deux déploiements.
 * Le jeton n'est jamais journalisé ni renvoyé au frontend.
 */
@Service
public class JiraClient {

    private static final String FIELDS = "summary,status,priority,assignee,updated,labels";

    @Value("${assistant.jira.enabled:false}")
    private boolean enabled;
    @Value("${assistant.jira.base-url:}")
    private String baseUrl;
    @Value("${assistant.jira.deployment:cloud}")
    private String deployment;
    @Value("${assistant.jira.email:}")
    private String email;
    @Value("${assistant.jira.api-token:}")
    private String apiToken;
    @Value("${assistant.jira.timeout-seconds:15}")
    private int timeoutSeconds = 15;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public boolean isEnabled() {
        return enabled;
    }

    /** Intégration réellement utilisable : activée, URL et jeton renseignés. */
    public boolean isConfigured() {
        return enabled && baseUrl != null && !baseUrl.isBlank() && apiToken != null && !apiToken.isBlank()
                && (!isCloud() || (email != null && !email.isBlank()));
    }

    public boolean isCloud() {
        return !"datacenter".equalsIgnoreCase(deployment) && !"server".equalsIgnoreCase(deployment);
    }

    public String deployment() {
        return isCloud() ? "cloud" : "datacenter";
    }

    public String baseUrl() {
        return baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    public String browseUrl(String key) {
        return baseUrl() + "/browse/" + key;
    }

    public List<JiraModels.JiraIssue> search(String jql, int max) throws Exception {
        String path = (isCloud() ? "/rest/api/3/search/jql" : "/rest/api/2/search")
                + "?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8)
                + "&maxResults=" + Math.max(1, Math.min(max, 50)) + "&fields=" + FIELDS;
        JsonNode root = send(request(path).GET().build(), 200);
        List<JiraModels.JiraIssue> issues = new ArrayList<>();
        for (JsonNode issue : root.path("issues")) {
            JsonNode f = issue.path("fields");
            List<String> labels = new ArrayList<>();
            f.path("labels").forEach(l -> labels.add(l.asText()));
            String key = issue.path("key").asText();
            issues.add(new JiraModels.JiraIssue(key, f.path("summary").asText(""), f.path("status").path("name").asText(""),
                    f.path("priority").path("name").asText(""), f.path("assignee").path("displayName").asText(""),
                    f.path("updated").asText(""), labels, browseUrl(key)));
        }
        return issues;
    }

    /** Crée un ticket et renvoie sa clé (ex. TITRES-142). */
    public String createIssue(String projectKey, String issueType, String summary, String description, List<String> labels)
            throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", projectKey));
        fields.put("summary", summary);
        fields.put("description", description);
        fields.put("issuetype", Map.of("name", issueType));
        fields.put("labels", labels);
        JsonNode root = send(request("/rest/api/2/issue").POST(body(Map.of("fields", fields))).build(), 201);
        return root.path("key").asText();
    }

    public void addComment(String issueKey, String comment) throws Exception {
        send(request("/rest/api/2/issue/" + URLEncoder.encode(issueKey, StandardCharsets.UTF_8) + "/comment")
                .POST(body(Map.of("body", comment))).build(), 201);
    }

    private HttpRequest.Builder request(String path) {
        String auth = isCloud()
                ? "Basic " + Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8))
                : "Bearer " + apiToken;
        return HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", auth)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
    }

    private HttpRequest.BodyPublisher body(Object o) throws Exception {
        return HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(o), StandardCharsets.UTF_8);
    }

    private JsonNode send(HttpRequest request, int expected) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expected && response.statusCode() / 100 != 2) {
            String detail = response.body() == null ? "" : response.body();
            throw new IllegalStateException("Jira a répondu " + response.statusCode() + " : "
                    + (detail.length() > 300 ? detail.substring(0, 300) + "..." : detail));
        }
        return response.body() == null || response.body().isBlank() ? objectMapper.readTree("{}") : objectMapper.readTree(response.body());
    }
}
