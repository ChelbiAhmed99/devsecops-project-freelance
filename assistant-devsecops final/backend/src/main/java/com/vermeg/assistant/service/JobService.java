/**
 * Role : gerer les informations et operations liees aux jobs CI/CD.
 * Contenu : definition de JobService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.security.CurrentUser;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Exécution asynchrone des traitements longs (tests Maven, Dependency-Check,
 * Trivy, synchronisation Sonar). L'API répond immédiatement avec un identifiant
 * de job ; le frontend suit l'avancement (polling + notification SSE).
 * Un seul job d'un même type peut tourner à la fois.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final int MAX_HISTORY = 100;

    public enum JobStatus { RUNNING, SUCCEEDED, FAILED }

    public static final class Job {
        private final String id;
        private final String type;
        private final String label;
        private final String triggeredBy;
        private final LocalDateTime startedAt;
        private volatile JobStatus status = JobStatus.RUNNING;
        private volatile LocalDateTime finishedAt;
        private volatile String message;
        private volatile Map<String, Object> result;

        Job(String type, String label, String triggeredBy) {
            this.id = UUID.randomUUID().toString();
            this.type = type;
            this.label = label;
            this.triggeredBy = triggeredBy;
            this.startedAt = LocalDateTime.now();
        }

        public String getId() { return id; }
        public String getType() { return type; }
        public String getLabel() { return label; }
        public String getTriggeredBy() { return triggeredBy; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public JobStatus getStatus() { return status; }
        public LocalDateTime getFinishedAt() { return finishedAt; }
        public String getMessage() { return message; }
        public Map<String, Object> getResult() { return result; }
    }

    /** Résultat d'un traitement : succès fonctionnel, message lisible et données. */
    public record JobOutcome(boolean success, String message, Map<String, Object> data) { }

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final EventService eventService;

    public JobService(EventService eventService) {
        this.eventService = eventService;
    }

    public synchronized Job submit(String type, String label, Supplier<JobOutcome> work) {
        Optional<Job> running = jobs.values().stream()
                .filter(j -> j.type.equals(type) && j.status == JobStatus.RUNNING).findFirst();
        if (running.isPresent()) {
            return running.get();
        }
        Job job = new Job(type, label, CurrentUser.username());
        jobs.put(job.id, job);
        prune();
        // L'utilisateur qui lance le job est propagé au thread d'exécution : l'audit et
        // l'historique indiquent ainsi le vrai déclencheur au lieu de "system".
        SecurityContext caller = SecurityContextHolder.getContext();
        executor.submit(() -> {
            SecurityContextHolder.setContext(caller);
            try {
                execute(job, work);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        return job;
    }

    private void execute(Job job, Supplier<JobOutcome> work) {
        try {
            JobOutcome outcome = work.get();
            job.result = outcome.data();
            job.message = outcome.message();
            job.status = outcome.success() ? JobStatus.SUCCEEDED : JobStatus.FAILED;
        } catch (RuntimeException e) {
            log.error("Échec du job {}", job.type, e);
            job.message = "Erreur : " + e.getMessage();
            job.status = JobStatus.FAILED;
        } finally {
            job.finishedAt = LocalDateTime.now();
            eventService.publish("job.finished",
                    job.status == JobStatus.SUCCEEDED ? "success" : "warning",
                    job.label + (job.status == JobStatus.SUCCEEDED ? " terminé" : " en échec"),
                    Map.of("jobId", job.id, "type", job.type, "status", job.status.name(),
                            "message", job.message == null ? "" : job.message));
        }
    }

    /** Job en cours pour un type donné (permet à l'interface de reprendre le suivi après un rechargement). */
    public Optional<Job> running(String type) {
        return jobs.values().stream()
                .filter(j -> j.type.equals(type) && j.status == JobStatus.RUNNING).findFirst();
    }

    public Optional<Job> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<Job> recent(int limit) {
        List<Job> list = new ArrayList<>(jobs.values());
        list.sort(Comparator.comparing(Job::getStartedAt).reversed());
        return list.stream().limit(limit).toList();
    }

    private void prune() {
        if (jobs.size() <= MAX_HISTORY) return;
        jobs.values().stream()
                .filter(j -> j.status != JobStatus.RUNNING)
                .sorted(Comparator.comparing(Job::getStartedAt))
                .limit(jobs.size() - MAX_HISTORY)
                .map(Job::getId)
                .toList()
                .forEach(jobs::remove);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
