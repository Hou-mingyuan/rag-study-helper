package com.rag.studyhelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.model.IngestionJob;
import com.rag.studyhelper.service.IngestionJobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.rag.provider=mock",
        "app.rag.auto-scan=false",
        "app.rag.document-scan-path=src/test/resources/evaluation/docs",
        "app.rag.inbox-path=target/application-flow-inbox",
        "app.rag.ingestion-recovery-initial-delay-ms=3600000",
        "app.rag.ingestion-recovery-interval-ms=3600000",
        "app.vector.reconciliation.enabled=false",
        "app.vector.reconciliation.initial-delay-millis=3600000",
        "vector.store.type=in-memory",
        "app.api-key.enabled=false",
        "app.feishu.sync-enabled=false"
})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "app.it.enabled", matches = "true")
class ApplicationFlowIT {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        String mysqlHost = System.getProperty("app.it.mysql.host", "127.0.0.1");
        int mysqlPort = Integer.parseInt(System.getProperty("app.it.mysql.port", "13306"));
        String mysqlDatabase = System.getProperty("app.it.mysql.database", "rag_study_helper");
        String mysqlUsername = System.getProperty("app.it.mysql.username", "rag");
        String mysqlPassword = System.getProperty("app.it.mysql.password", "rag-local-app-password");
        String redisHost = System.getProperty("app.it.redis.host", "127.0.0.1");
        int redisPort = Integer.parseInt(System.getProperty("app.it.redis.port", "16379"));
        String redisPassword = System.getProperty("app.it.redis.password", "");
        int redisDatabase = Integer.parseInt(System.getProperty("app.it.redis.database", "3"));
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + mysqlHost + ":" + mysqlPort
                + "/" + mysqlDatabase + "?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", () -> mysqlUsername);
        registry.add("spring.datasource.password", () -> mysqlPassword);
        registry.add("spring.data.redis.host", () -> redisHost);
        registry.add("spring.data.redis.port", () -> redisPort);
        registry.add("spring.data.redis.password", () -> redisPassword);
        registry.add("spring.data.redis.database", () -> redisDatabase);
        registry.add("app.vector.collection-name", () -> "application_flow_" + RUN);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private IngestionJobService jobs;

    @Test
    @Timeout(90)
    void mockModeCompletesSpaceUploadChunkChatUpdateAndDeleteLifecycle() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/favicon.ico")).andExpect(status().isNoContent());

        JsonNode health = ok(get("/api/health"));
        assertEquals("UP", health.path("status").asText());
        assertEquals("mock", health.path("ragProvider").asText());
        JsonNode readiness = ok(get("/api/readiness"));
        assertEquals("UP", readiness.path("status").asText());

        String spaceName = "验收空间-" + RUN.substring(0, 10);
        JsonNode space = ok(post("/api/spaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(java.util.Map.of(
                        "name", spaceName,
                        "description", "端到端数据一致性验收"))));
        long spaceId = space.path("id").asLong();
        assertTrue(spaceId > 1);
        assertEquals(spaceName, ok(get("/api/spaces/{id}", spaceId)).path("name").asText());
        assertTrue(ok(get("/api/spaces")).isArray());

        mvc.perform(post("/api/spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(java.util.Map.of("name", spaceName))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());

        JsonNode renamed = ok(put("/api/spaces/{id}", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(java.util.Map.of(
                        "name", spaceName + "-已更新",
                        "description", "覆盖空间更新路径"))));
        assertTrue(renamed.path("name").asText().endsWith("已更新"));

        JsonNode session = ok(post("/api/spaces/{id}/sessions", spaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"集成验收会话\"}"));
        String sessionId = session.path("id").asText();
        assertFalse(sessionId.isBlank());
        assertTrue(ok(get("/api/spaces/{id}/sessions", spaceId)).isArray());
        JsonNode renamedSession = ok(put("/api/spaces/{spaceId}/sessions/{sessionId}", spaceId, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"已重命名会话\"}"));
        assertEquals("已重命名会话", renamedSession.path("title").asText());

        byte[] firstBody = ("# RAG 可靠性\n\n" +
                "确定性向量 ID 支持部分失败后的幂等重试。引用必须包含文档和分块标识。\n\n" +
                "飞书删除必须经过完整枚举、连续缺失确认和数量比例阈值。\n")
                .getBytes(StandardCharsets.UTF_8);
        long uploadJobId = upload(spaceId, "acceptance.md", firstBody);
        assertEquals("COMPLETED", awaitJob(spaceId, uploadJobId).getStatus());

        JsonNode documents = ok(get("/api/spaces/{id}/documents", spaceId));
        assertEquals(1, documents.size());
        long documentId = documents.get(0).path("id").asLong();
        assertTrue(documents.get(0).path("chunks").asInt() >= 1);
        JsonNode chunks = ok(get("/api/spaces/{spaceId}/documents/{documentId}/chunks", spaceId, documentId)
                .param("offset", "0").param("limit", "1"));
        assertEquals(1, chunks.size());
        long chunkId = chunks.get(0).path("chunkId").asLong();
        JsonNode detail = ok(get("/api/spaces/{spaceId}/documents/{documentId}/chunks/{chunkId}",
                spaceId, documentId, chunkId));
        assertTrue(detail.path("text").asText().contains("确定性向量"));

        JsonNode vectorStatus = ok(get("/api/vector/status"));
        assertEquals(vectorStatus.path("activeChunks").asLong(), vectorStatus.path("indexedChunks").asLong());
        assertEquals("READY", ok(post("/api/vector/rebuild")).path("status").asText());
        assertTrue(ok(post("/api/vector/reconcile")).path("lockAcquired").asBoolean());

        MvcResult streamStart = mvc.perform(post("/api/spaces/{id}/chat", spaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(java.util.Map.of(
                                "sessionId", sessionId,
                                "question", "飞书删除保护需要哪些条件？"))))
                .andExpect(request().asyncStarted())
                .andReturn();
        String stream = mvc.perform(asyncDispatch(streamStart))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(stream.contains("event:retrieval"));
        assertTrue(stream.contains("event:token"));
        assertTrue(stream.contains("event:done"));
        assertTrue(stream.contains("documentId"));
        assertTrue(stream.contains("chunkId"));

        JsonNode sessionDetail = ok(get("/api/spaces/{spaceId}/sessions/{sessionId}", spaceId, sessionId));
        assertEquals(2, sessionDetail.path("messages").size());

        byte[] secondBody = ("# RAG 可靠性（修订）\n\n" +
                "确定性向量 ID、版本状态机和补偿队列共同保证最终一致。\n\n" +
                "飞书安全删除要求完整枚举、两次连续缺失、数量阈值、比例阈值和分布式锁。\n")
                .getBytes(StandardCharsets.UTF_8);
        long updateJobId = upload(spaceId, "acceptance.md", secondBody);
        assertEquals("COMPLETED", awaitJob(spaceId, updateJobId).getStatus());
        assertEquals(documentId, ok(get("/api/spaces/{id}/documents", spaceId)).get(0).path("id").asLong());

        long duplicateJobId = upload(spaceId, "acceptance.md", secondBody);
        assertEquals("COMPLETED", awaitJob(spaceId, duplicateJobId).getStatus());
        assertNotEquals(updateJobId, duplicateJobId);
        assertEquals(3, ok(get("/api/spaces/{id}/jobs", spaceId)).size());

        JsonNode feishu = ok(get("/api/spaces/{id}/feishu/status", spaceId));
        assertFalse(feishu.path("enabled").asBoolean());

        JsonNode deleteJob = accepted(delete("/api/spaces/{spaceId}/documents/{documentId}", spaceId, documentId)
                .header("Idempotency-Key", "delete-" + RUN));
        assertEquals("COMPLETED", awaitJob(spaceId, deleteJob.path("id").asLong()).getStatus());
        assertEquals(0, ok(get("/api/spaces/{id}/documents", spaceId)).size());

        ok(delete("/api/spaces/{spaceId}/sessions/{sessionId}", spaceId, sessionId));
        assertEquals(0, ok(get("/api/spaces/{id}/sessions", spaceId)).size());
        ok(delete("/api/spaces/{id}", spaceId));
        mvc.perform(get("/api/spaces/{id}", spaceId)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/spaces/{id}/documents", spaceId)).andExpect(status().isBadRequest());
    }

    private long upload(long spaceId, String name, byte[] body) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", name, "text/markdown", body);
        JsonNode job = accepted(multipart("/api/spaces/{id}/documents/upload", spaceId)
                .file(file)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
        return job.path("id").asLong();
    }

    private IngestionJob awaitJob(long spaceId, long jobId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        IngestionJob job;
        do {
            job = jobs.get(spaceId, jobId);
            if (java.util.Set.of("COMPLETED", "FAILED", "CANCELLED", "DEAD").contains(job.getStatus())) {
                return job;
            }
            Thread.sleep(75L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for ingestion job " + jobId + ": " + job.getStatus());
    }

    private JsonNode ok(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return object(result);
    }

    private JsonNode accepted(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andExpect(status().isAccepted()).andReturn();
        return object(result);
    }

    private JsonNode object(MvcResult result) throws Exception {
        JsonNode envelope = json.readTree(result.getResponse().getContentAsByteArray());
        assertEquals("200", envelope.path("resCode").asText(), envelope::toString);
        return envelope.path("obj");
    }
}
