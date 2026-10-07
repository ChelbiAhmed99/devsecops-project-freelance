package com.vermeg.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Notifications temps réel (Server-Sent Events) vers le tableau de bord :
 * anomalie critique détectée, nouvelle vulnérabilité critique, fin d'un job...
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    public EventService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, "connected", Map.of("at", LocalDateTime.now().toString()));
        return emitter;
    }

    /**
     * @param type     ex: "anomaly.critical", "vulnerability.critical", "job.finished", "logs.ingested"
     * @param severity info | success | warning | critical (utilisé par le frontend pour le style)
     */
    public void publish(String type, String severity, String title, Map<String, Object> data) {
        Map<String, Object> payload = Map.of(
                "type", type,
                "severity", severity,
                "title", title,
                "data", data == null ? Map.of() : data,
                "at", LocalDateTime.now().toString());
        for (SseEmitter emitter : emitters) {
            send(emitter, type, payload);
        }
    }

    public int subscriberCount() {
        return emitters.size();
    }

    /** Commentaire périodique pour garder la connexion ouverte à travers les proxys. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    private void send(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name)
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Abonné SSE déconnecté : {}", e.getMessage());
            emitters.remove(emitter);
        }
    }
}
