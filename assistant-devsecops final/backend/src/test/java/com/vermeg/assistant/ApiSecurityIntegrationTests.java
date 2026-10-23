/**
 * Role : documenter le comportement attendu en verifiant les regles d authentification et d acces aux API.
 * Contenu : definition de ApiSecurityIntegrationTests et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration de bout en bout (contexte Spring complet, base H2 en mémoire) :
 * authentification JWT, contrôle d'accès par rôle, clé d'API du pipeline CI,
 * ingestion puis recherche de logs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSecurityIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return "Bearer " + node.path("token").asText();
    }

    @Test
    void healthEndpointShouldBePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void protectedEndpointWithoutTokenShouldReturn401() throws Exception {
        mvc.perform(get("/api/logs/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void badCredentialsShouldReturn401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dev\",\"password\":\"mauvais\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginShouldReturnTokenAndProfile() throws Exception {
        String token = login("qualite", "Qualite@2026");
        mvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("qualite"))
                .andExpect(jsonPath("$.role").value("QUALITY_MANAGER"));
    }

    @Test
    void forgedTokenShouldBeRejected() throws Exception {
        mvc.perform(get("/api/dashboard/overview").header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.faux"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void developerShouldNotManageUsersNorVulnerabilities() throws Exception {
        String dev = login("dev", "Dev@2026");
        mvc.perform(get("/api/users").header("Authorization", dev)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/security/vulnerabilities/1").header("Authorization", dev)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FIXED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/security/scan/sonar").header("Authorization", dev)).andExpect(status().isForbidden());
    }

    @Test
    void adminShouldListUsers() throws Exception {
        String admin = login("admin", "Admin@2026");
        mvc.perform(get("/api/users").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }

    @Test
    void ingestedLogsShouldBeSearchable() throws Exception {
        String dev = login("dev", "Dev@2026");
        mvc.perform(post("/api/logs/ingest").header("Authorization", dev).contentType(MediaType.TEXT_PLAIN)
                        .content("2026-09-19 10:00:00 ERROR [billing-service] Invoice generation failed for batch 77\n"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingested").value(1));

        mvc.perform(get("/api/logs/search").param("q", "invoice generation").header("Authorization", dev))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].source").value("billing-service"));

        mvc.perform(get("/api/logs/anomalies").header("Authorization", dev))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Clustering-Engine", "jaccard"));
    }

    @Test
    void ciEndpointsShouldRequireValidApiKey() throws Exception {
        mvc.perform(get("/api/ci/quality-gate")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ci/quality-gate").header("X-API-Key", "mauvaise-cle")).andExpect(status().isUnauthorized());
        // Base de test vide : aucun résultat de test -> quality gate FAILED (412), mais authentifié.
        mvc.perform(get("/api/ci/quality-gate").header("X-API-Key", "test-ci-key"))
                .andExpect(status().is(412))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void ciKeyShouldNotGrantAccessToUserApi() throws Exception {
        mvc.perform(get("/api/logs/stats").header("X-API-Key", "test-ci-key")).andExpect(status().isUnauthorized());
    }

    @Test
    void ciShouldPublishTrivyReport() throws Exception {
        String trivy = "{\"SchemaVersion\":2,\"Results\":[{\"Target\":\"img\",\"Vulnerabilities\":[{\"VulnerabilityID\":\"CVE-2099-1\","
                + "\"PkgName\":\"libx\",\"Severity\":\"HIGH\",\"FixedVersion\":\"1.2\"}]}]}";
        mvc.perform(post("/api/ci/security/trivy").header("X-API-Key", "test-ci-key")
                        .contentType(MediaType.APPLICATION_JSON).content(trivy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detected").value(1));
    }

    @Test
    void aiUnavailableShouldReturn502() throws Exception {
        String dev = login("dev", "Dev@2026");
        mvc.perform(post("/api/ai/explain").header("Authorization", dev).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentType\":\"log_anomaly\",\"message\":\"Connection timeout\"}"))
                .andExpect(status().isBadGateway());
    }
}
