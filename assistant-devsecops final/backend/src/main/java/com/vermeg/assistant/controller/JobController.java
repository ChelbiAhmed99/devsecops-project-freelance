package com.vermeg.assistant.controller;

import com.vermeg.assistant.service.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/** Suivi des traitements asynchrones (tests, scans). */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public List<JobService.Job> recent(@RequestParam(defaultValue = "20") int limit) {
        return jobService.recent(Math.min(limit, 100));
    }

    /** Job en cours d'un type donné (tests, scan-dependencies, scan-image, sync-sonar) ; 204 s'il n'y en a pas. */
    @GetMapping("/running")
    public ResponseEntity<JobService.Job> running(@RequestParam String type) {
        return jobService.running(type).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public JobService.Job get(@PathVariable String id) {
        return jobService.find(id).orElseThrow(() -> new NoSuchElementException("Job introuvable : " + id));
    }
}
