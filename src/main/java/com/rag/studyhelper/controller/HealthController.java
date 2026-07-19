package com.rag.studyhelper.controller;

import com.rag.studyhelper.utils.Results;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.rag.studyhelper.config.ApiKeyProperties;
import com.rag.studyhelper.config.RagProviderResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行态健康检查接口。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${vector.store.type:in-memory}")
    private String vectorStoreType;

    @Value("${app.feishu.sync-enabled:false}")
    private boolean feishuSyncEnabled;

    @Autowired
    private RagProviderResolver ragProviderResolver;

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    @GetMapping("/health")
    public Results<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", "rag-study-helper");
        status.put("ragProvider", ragProviderResolver.isMockMode() ? "mock" : "openai");
        status.put("vectorStore", vectorStoreType);
        status.put("feishuSyncEnabled", feishuSyncEnabled);
        status.put("apiKeyAuthEnabled", apiKeyProperties.isConfigured());
        status.put("time", OffsetDateTime.now().toString());
        return Results.success(status);
    }
}
