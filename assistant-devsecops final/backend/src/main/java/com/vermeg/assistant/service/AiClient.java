package com.vermeg.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client HTTP du microservice IA (FastAPI). Le backend ne connaît que ce contrat
 * JSON : le fournisseur de modèle (Groq, heuristiques locales...) peut changer
 * sans impact côté Java.
 */
@Service
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    @Value("${assistant.ai.service-url:http://localhost:8000}")
    private String aiServiceUrl;

    @Value("${assistant.ai.timeout-seconds:60}")
    private int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record AiExplanationResult(boolean success, String explanation, String suggestedFix,
                                      String severityAssessment, String provider, String model,
                                      String errorMessage) { }

    public record ChatResult(boolean success, String answer, String provider, String model, String errorMessage) { }

    public record Outlier(String bucket, double score) { }

    public AiExplanationResult explain(String incidentType, String source, String message,
                                       Integer occurrences, String extraContext) {
        Map<String, Object> body = new HashMap<>();
        body.put("incident_type", incidentType);
        body.put("source", source);
        body.put("message", message);
        body.put("occurrences", occurrences);
        body.put("extra_context", extraContext);
        try {
            HttpResponse<String> response = post("/explain", body, timeoutSeconds);
            if (response.statusCode() != 200) {
                return new AiExplanationResult(false, null, null, null, null, null,
                        "Le service IA a répondu " + response.statusCode() + detail(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            return new AiExplanationResult(true,
                    root.path("explanation").asText(""),
                    root.path("suggested_fix").asText(""),
                    root.path("severity_assessment").asText(""),
                    root.path("provider").asText("unknown"),
                    root.path("model").asText(""),
                    null);
        } catch (Exception e) {
            return new AiExplanationResult(false, null, null, null, null, null,
                    "Impossible de contacter le service IA (" + aiServiceUrl + ") : " + e.getMessage());
        }
    }

    public ChatResult chat(String question, List<Map<String, String>> history, String context) {
        Map<String, Object> body = new HashMap<>();
        body.put("question", question);
        body.put("history", history == null ? List.of() : history);
        body.put("context", context);
        try {
            HttpResponse<String> response = post("/chat", body, timeoutSeconds);
            if (response.statusCode() != 200) {
                return new ChatResult(false, null, null, null,
                        "Le service IA a répondu " + response.statusCode() + detail(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            return new ChatResult(true, root.path("answer").asText(""), root.path("provider").asText("unknown"),
                    root.path("model").asText(""), null);
        } catch (Exception e) {
            return new ChatResult(false, null, null, null,
                    "Impossible de contacter le service IA (" + aiServiceUrl + ") : " + e.getMessage());
        }
    }

    /**
     * Clustering TF-IDF + DBSCAN des messages normalisés (scikit-learn).
     * Renvoie message → numéro de cluster, ou vide si le service est indisponible.
     */
    public Optional<Map<String, Integer>> cluster(List<String> messages) {
        if (messages.isEmpty()) return Optional.of(Map.of());
        try {
            HttpResponse<String> response = post("/ml/cluster", Map.of("messages", messages), 15);
            if (response.statusCode() != 200) return Optional.empty();
            JsonNode labels = objectMapper.readTree(response.body()).path("labels");
            if (!labels.isArray() || labels.size() != messages.size()) return Optional.empty();
            Map<String, Integer> result = new LinkedHashMap<>();
            for (int i = 0; i < messages.size(); i++) {
                result.put(messages.get(i), labels.get(i).asInt(-1));
            }
            return Optional.of(result);
        } catch (Exception e) {
            log.debug("Clustering IA indisponible : {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Détection d'observations atypiques (IsolationForest) sur des vecteurs de
     * caractéristiques par fenêtre temporelle.
     */
    public Optional<List<Outlier>> outliers(List<String> bucketLabels, List<double[]> features) {
        if (bucketLabels.size() < 8) return Optional.empty(); // trop peu de points pour un modèle fiable
        try {
            Map<String, Object> body = Map.of("labels", bucketLabels, "features", features);
            HttpResponse<String> response = post("/ml/outliers", body, 15);
            if (response.statusCode() != 200) return Optional.empty();
            List<Outlier> result = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(response.body()).path("outliers")) {
                result.add(new Outlier(node.path("label").asText(), node.path("score").asDouble()));
            }
            return Optional.of(result);
        } catch (Exception e) {
            log.debug("Détection d'outliers IA indisponible : {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** État du service IA pour le tableau de bord : UP/DOWN + fournisseur LLM actif. */
    public Map<String, Object> health() {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(aiServiceUrl + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Map.of("status", "DOWN");
            }
            JsonNode root = objectMapper.readTree(response.body());
            return Map.of("status", "UP",
                    "provider", root.path("provider").asText("unknown"),
                    "model", root.path("model").asText(""),
                    "llmConfigured", root.path("api_key_configured").asBoolean(false));
        } catch (Exception e) {
            return Map.of("status", "DOWN", "error", String.valueOf(e.getMessage()));
        }
    }

    private HttpResponse<String> post(String path, Object body, int timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aiServiceUrl + path))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String detail(String body) {
        try {
            String detail = objectMapper.readTree(body).path("detail").asText(null);
            return detail == null ? "" : " : " + detail;
        } catch (Exception e) {
            return "";
        }
    }
}
