package com.rag.studyhelper.controller;

import com.rag.studyhelper.feishu.config.FeishuProperties;
import com.rag.studyhelper.feishu.service.FeishuSyncReport;
import com.rag.studyhelper.feishu.service.FeishuSyncService;
import com.rag.studyhelper.feishu.service.FeishuSyncStatus;
import com.rag.studyhelper.model.FeishuSyncRun;
import com.rag.studyhelper.service.KnowledgeSpaceService;
import com.rag.studyhelper.utils.Results;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/spaces/{spaceId}/feishu")
public class FeishuSyncController {

    private final ObjectProvider<FeishuSyncService> serviceProvider;
    private final FeishuProperties properties;
    private final KnowledgeSpaceService spaces;

    public FeishuSyncController(ObjectProvider<FeishuSyncService> serviceProvider,
                                FeishuProperties properties,
                                KnowledgeSpaceService spaces) {
        this.serviceProvider = serviceProvider;
        this.properties = properties;
        this.spaces = spaces;
    }

    @GetMapping("/status")
    public Results<FeishuSyncStatus> status(@PathVariable long spaceId) {
        spaces.requireActive(spaceId);
        return Results.success(new FeishuSyncStatus(
                properties.isSyncEnabled() && serviceProvider.getIfAvailable() != null,
                properties.getLocalSpaceId(), redactRemoteId(properties.getSpaceId())));
    }

    @GetMapping("/runs")
    public Results<List<FeishuSyncRun>> runs(@PathVariable long spaceId) {
        spaces.requireActive(spaceId);
        return Results.success(requireService(spaceId).listRuns(spaceId));
    }

    @PostMapping("/sync")
    public Results<FeishuSyncReport> sync(@PathVariable long spaceId) {
        spaces.requireActive(spaceId);
        return Results.success(requireService(spaceId).syncWiki());
    }

    private FeishuSyncService requireService(long spaceId) {
        if (spaceId != properties.getLocalSpaceId()) {
            throw new IllegalArgumentException("Feishu sync is not configured for this knowledge space");
        }
        FeishuSyncService service = serviceProvider.getIfAvailable();
        if (!properties.isSyncEnabled() || service == null) {
            throw new IllegalStateException("Feishu sync is disabled");
        }
        return service;
    }

    private String redactRemoteId(String remoteId) {
        if (remoteId == null || remoteId.isBlank()) {
            return null;
        }
        return remoteId.length() <= 4 ? "configured" : "***" + remoteId.substring(remoteId.length() - 4);
    }
}
