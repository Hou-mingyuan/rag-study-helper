package com.rag.studyhelper.feishu.config;

import com.rag.studyhelper.feishu.client.FeishuClient;
import com.rag.studyhelper.feishu.service.FeishuSyncService;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.FeishuSyncRunMapper;
import com.rag.studyhelper.service.DocumentIngestionService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class FeishuConfigTest {

    @Test
    void buildsTimeoutBoundClientAndSyncServiceFromOnePropertySet() {
        FeishuProperties properties = new FeishuProperties()
                .setAppId("app")
                .setAppSecret("secret")
                .setBaseUrl("https://open.feishu.cn")
                .setSpaceId("remote-space")
                .setConnectTimeoutMillis(1111)
                .setReadTimeoutMillis(2222)
                .setWriteTimeoutMillis(3333);
        FeishuConfig config = new FeishuConfig();

        FeishuClient client = config.feishuClient(properties);
        assertNotNull(client);
        FeishuSyncService service = config.feishuSyncService(
                client,
                mock(DocumentIngestionService.class),
                properties,
                mock(DocumentsMapper.class),
                mock(FeishuSyncRunMapper.class),
                mock(RedissonClient.class));
        assertNotNull(service);
        assertEquals(1111, properties.getConnectTimeoutMillis());
        assertEquals(2222, properties.getReadTimeoutMillis());
        assertEquals(3333, properties.getWriteTimeoutMillis());
    }
}
