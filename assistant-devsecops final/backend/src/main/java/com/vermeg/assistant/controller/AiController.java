/**
 * Role : exposer les endpoints REST lies aux fonctionnalites IA et deleguer leur traitement aux services.
 * Contenu : definition de AiController et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.AiRecommendation;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.service.AiClient;
import com.vermeg.assistant.service.AiRecommendationStore;
import com.vermeg.assistant.service.AiService;
import com.vermeg.assistant.service.CorrectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Brique IA.
 * <ul>
 *   <li>POST  /api/ai/explain                      -> explication + correction (PENDING_REVIEW)</li>
 *   <li>GET   /api/ai/recommendations              -> liste (filtre status)</li>
 *   <li>GET   /api/ai/recommendations/{id}         -> détail</li>
 *   <li>PATCH /api/ai/recommendations/{id}/status  -> validation humaine (APPROVED / REJECTED + commentaire)</li>
 *   <li>POST  /api/ai/recommendations/{id}/applied -> un humain déclare la correction appliquée (référence obligatoire)</li>
 *   <li>POST  /api/ai/recommendations/{id}/verify  -> vérifie l'effet de la correction sur les nouvelles données</li>
 *   <li>POST  /api/ai/chat                         -> copilote conversationnel contextualisé</li>
 *   <li>GET   /api/ai/health                       -> état du service IA</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;
    private final AiRecommendationStore store;
    private final AiClient aiClient;
    private final CorrectionService correctionService;

    public AiController(AiService aiService, AiRecommendationStore store, AiClient aiClient,
                        CorrectionService correctionService) {
        this.aiService = aiService;
        this.store = store;
        this.aiClient = aiClient;
        this.correctionService = correctionService;
    }

    @PostMapping("/recommendations/{id}/applied")
    public AiRecommendation markApplied(@PathVariable String id, @RequestBody ApiDtos.AppliedRequest body) {
        return correctionService.markApplied(id, body);
    }

    @PostMapping("/recommendations/{id}/verify")
    public AiRecommendation verify(@PathVariable String id) {
        return correctionService.verifyNow(id);
    }

    @PostMapping("/explain")
    public AiRecommendation explain(@RequestBody ApiDtos.ExplainRequest request) {
        return aiService.explain(request);
    }

    @GetMapping("/recommendations")
    public List<AiRecommendation> getRecommendations(@RequestParam(required = false) String status) {
        List<AiRecommendation> all = store.getAll();
        if (status == null || status.isBlank()) return all;
        return all.stream().filter(r -> r.getStatus().name().equalsIgnoreCase(status)).toList();
    }

    @GetMapping("/recommendations/{id}")
    public AiRecommendation get(@PathVariable String id) {
        return store.findById(id).orElseThrow(() -> new NoSuchElementException("Recommandation introuvable : " + id));
    }

    @PatchMapping("/recommendations/{id}/status")
    public AiRecommendation updateStatus(@PathVariable String id, @RequestBody ApiDtos.ReviewRequest body) {
        return aiService.review(id, body);
    }

    @PostMapping("/chat")
    public ApiDtos.ChatResponse chat(@RequestBody ApiDtos.ChatRequest request) {
        return aiService.chat(request);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return aiClient.health();
    }

    @ExceptionHandler(AiService.AiUnavailableException.class)
    public ResponseEntity<Map<String, Object>> aiUnavailable(AiService.AiUnavailableException e) {
        return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage()), "status", 502));
    }
}
