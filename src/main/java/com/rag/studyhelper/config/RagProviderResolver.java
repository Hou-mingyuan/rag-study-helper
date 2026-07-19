package com.rag.studyhelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 解析 RAG 模型供应方：mock（零密钥演示）或 openai（真实 API）。
 */
@Component
public class RagProviderResolver {

    private final boolean mockMode;

    public RagProviderResolver(
            @Value("${app.rag.provider:auto}") String provider,
            @Value("${app.rag.chat-api-key:}") String chatApiKey,
            @Value("${app.rag.embedding-api-key:}") String embeddingApiKey) {
        if ("mock".equalsIgnoreCase(provider)) {
            this.mockMode = true;
        } else if ("openai".equalsIgnoreCase(provider)) {
            this.mockMode = false;
        } else {
            this.mockMode = !StringUtils.hasText(chatApiKey) || !StringUtils.hasText(embeddingApiKey);
        }
    }

    public boolean isMockMode() {
        return mockMode;
    }
}
