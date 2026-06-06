package com.rag.studyhelper.feishu.config;

import com.rag.studyhelper.feishu.client.FeishuClient;
import com.rag.studyhelper.feishu.service.FeishuSyncService;
import com.rag.studyhelper.service.DocumentIngestionService;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class FeishuConfig {

    private static final String FEISHU_BASE_URL = "https://open.feishu.cn";

    @Bean
    @ConditionalOnProperty(name = "app.feishu.sync-enabled", havingValue = "true")
    public FeishuClient feishuClient(FeishuProperties properties) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        return new FeishuClient(
                properties.getAppId(),
                properties.getAppSecret(),
                FEISHU_BASE_URL,
                httpClient
        );
    }

    @Bean
    @ConditionalOnProperty(name = "app.feishu.sync-enabled", havingValue = "true")
    public FeishuSyncService feishuSyncService(
            FeishuClient feishuClient,
            DocumentIngestionService ingestionService,
            FeishuProperties properties) {
        return new FeishuSyncService(feishuClient, ingestionService, properties.getWikiId());
    }
}
