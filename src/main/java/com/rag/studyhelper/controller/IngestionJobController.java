package com.rag.studyhelper.controller;

import com.rag.studyhelper.model.IngestionJob;
import com.rag.studyhelper.service.IngestionJobService;
import com.rag.studyhelper.utils.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/spaces/{spaceId}/jobs")
public class IngestionJobController {

    private final IngestionJobService jobs;

    public IngestionJobController(IngestionJobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    public Results<List<IngestionJob>> list(@PathVariable long spaceId) {
        return Results.success(jobs.list(spaceId));
    }

    @GetMapping("/{jobId}")
    public Results<IngestionJob> get(@PathVariable long spaceId, @PathVariable long jobId) {
        return Results.success(jobs.get(spaceId, jobId));
    }

    @PostMapping("/{jobId}/cancel")
    public Results<IngestionJob> cancel(@PathVariable long spaceId, @PathVariable long jobId) {
        return Results.success(jobs.cancel(spaceId, jobId));
    }

    @PostMapping("/{jobId}/retry")
    public Results<IngestionJob> retry(@PathVariable long spaceId, @PathVariable long jobId) {
        return Results.success(jobs.retry(spaceId, jobId));
    }
}
