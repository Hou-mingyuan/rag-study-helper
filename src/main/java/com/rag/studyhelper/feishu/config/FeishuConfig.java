package com.rag.studyhelper.feishu.config;

import com.rag.studyhelper.feishu.client.FeishuClient;
import com.rag.studyhelper.feishu.service.FeishuSyncService;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.FeishuSyncRunMapper;
import com.rag.studyhelper.service.DocumentIngestionService;
import okhttp3.OkHttpClient;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 飞书配置
 */
@Configuration
public class FeishuConfig {

    /**
     * app.feishu.sync-enabled 不等于 true 的时候 不注入 spring bean
     * 不需要这个功能的可以直接删掉 feishu 包下的东西，留着也不影响
     */
    @Bean
    @ConditionalOnProperty(name = "app.feishu.sync-enabled", havingValue = "true")
    public FeishuClient feishuClient(FeishuProperties properties) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .readTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
                .writeTimeout(Duration.ofMillis(properties.getWriteTimeoutMillis()))
                .build();
        return new FeishuClient(
                properties.getAppId(),
                properties.getAppSecret(),
                properties.getBaseUrl(),
                httpClient,
                properties
        );
    }

    @Bean
    @ConditionalOnProperty(name = "app.feishu.sync-enabled", havingValue = "true")
    public FeishuSyncService feishuSyncService(
            FeishuClient feishuClient,
            DocumentIngestionService ingestionService,
            FeishuProperties properties,
            DocumentsMapper documentsMapper,
            FeishuSyncRunMapper runsMapper,
            RedissonClient redissonClient) {
        return new FeishuSyncService(feishuClient, ingestionService, properties,
                documentsMapper, runsMapper, redissonClient);
    }
}
