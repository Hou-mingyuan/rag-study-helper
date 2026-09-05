package com.rag.studyhelper.controller;

import com.rag.studyhelper.model.KnowledgeSpace;
import com.rag.studyhelper.model.KnowledgeSpaceRequest;
import com.rag.studyhelper.service.KnowledgeSpaceService;
import com.rag.studyhelper.utils.Results;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/spaces")
public class KnowledgeSpaceController {

    private final KnowledgeSpaceService spaceService;

    public KnowledgeSpaceController(KnowledgeSpaceService spaceService) {
        this.spaceService = spaceService;
    }

    @GetMapping
    public Results<List<KnowledgeSpace>> list() {
        return Results.success(spaceService.list());
    }

    @GetMapping("/{spaceId}")
    public Results<KnowledgeSpace> get(@PathVariable long spaceId) {
        return Results.success(spaceService.requireActive(spaceId));
    }

    @PostMapping
    public Results<KnowledgeSpace> create(@Valid @RequestBody KnowledgeSpaceRequest request) {
        return Results.success(spaceService.create(request));
    }

    @PutMapping("/{spaceId}")
    public Results<KnowledgeSpace> update(@PathVariable long spaceId,
                                          @Valid @RequestBody KnowledgeSpaceRequest request) {
        return Results.success(spaceService.update(spaceId, request));
    }

    @DeleteMapping("/{spaceId}")
    public Results<Void> delete(@PathVariable long spaceId) {
        spaceService.delete(spaceId);
        return Results.success("Knowledge space deleted");
    }
}
