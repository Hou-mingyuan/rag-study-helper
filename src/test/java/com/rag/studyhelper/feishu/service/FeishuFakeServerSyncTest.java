package com.rag.studyhelper.feishu.service;

import com.rag.studyhelper.feishu.client.FeishuClient;
import com.rag.studyhelper.feishu.config.FeishuProperties;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.FeishuSyncRunMapper;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.FeishuSyncRun;
import com.rag.studyhelper.service.DocumentIngestionService;
import com.rag.studyhelper.support.MybatisMetadata;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeishuFakeServerSyncTest {

    @Mock
    private DocumentIngestionService ingestion;
    @Mock
    private DocumentsMapper documents;
    @Mock
    private FeishuSyncRunMapper runs;
    @Mock
    private RedissonClient redisson;
    @Mock
    private RLock lock;

    private MockWebServer server;
    private FeishuProperties properties;
    private FeishuSyncService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisMetadata.initialize(Documents.class, FeishuSyncRun.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new FeishuProperties()
                .setAppId("app-id")
                .setAppSecret("app-secret")
                .setSpaceId("remote-space")
                .setLocalSpaceId(1L)
                .setBaseUrl(server.url("/").toString())
                .setMaxRetries(1)
                .setInitialBackoffMillis(0)
                .setMissingConfirmations(2)
                .setMaxDeleteCount(10)
                .setMaxDeleteRatio(1.0)
                .setProtectZeroRemote(false)
                .setLockLeaseSeconds(30);
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .writeTimeout(Duration.ofSeconds(2))
                .build();
        FeishuClient client = new FeishuClient(
                "app-id", "app-secret", server.url("/").toString(), http, properties);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicLong ids = new AtomicLong(200);
        doAnswer(invocation -> {
            FeishuSyncRun run = invocation.getArgument(0);
            run.setId(ids.incrementAndGet());
            return 1;
        }).when(runs).insert(any(FeishuSyncRun.class));
        service = new FeishuSyncService(client, ingestion, properties, documents, runs, redisson);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fakeServerCreatesANewRemoteDocument() throws Exception {
        enqueueToken();
        enqueueNodes("n1", "doc-1", 10);
        enqueueContent("RAG create content");
        Documents indexed = remoteDocument(11L, "n1", 10);
        when(documents.selectOne(any())).thenReturn(null, indexed);
        when(documents.selectList(any())).thenReturn(List.of(indexed));

        FeishuSyncReport report = service.syncWiki();

        assertEquals("SUCCEEDED", report.status());
        assertEquals(1, report.created());
        verify(ingestion).ingestFeishuDocument(
                1L, "remote-space", "Title_document", "RAG create content",
                "n1", 10L, "docx");
    }

    @Test
    void fakeServerUpdatesOnlyWhenRemoteTimestampAdvances() throws Exception {
        enqueueToken();
        enqueueNodes("n1", "doc-1", 20);
        enqueueContent("RAG updated content");
        Documents existing = remoteDocument(11L, "n1", 10);
        Documents indexed = remoteDocument(11L, "n1", 20);
        when(documents.selectOne(any())).thenReturn(existing, indexed);
        when(documents.selectList(any())).thenReturn(List.of(indexed));

        FeishuSyncReport report = service.syncWiki();

        assertEquals("SUCCEEDED", report.status());
        assertEquals(1, report.updated());
        verify(ingestion).ingestFeishuDocument(
                1L, "remote-space", "Title_document", "RAG updated content",
                "n1", 20L, "docx");
    }

    @Test
    void fakeServerDeleteRequiresTwoCompleteEmptyEnumerations() throws Exception {
        enqueueToken();
        enqueueEmptyNodes();
        enqueueEmptyNodes();
        Documents missing = remoteDocument(12L, "missing", 10);
        missing.setRemoteMissingCount(0);
        when(documents.selectList(any())).thenReturn(List.of(missing));

        FeishuSyncReport first = service.syncWiki();
        FeishuSyncReport second = service.syncWiki();

        assertEquals(0, first.deleted());
        assertEquals(1, second.deleted());
        verify(ingestion).deleteDocument(1L, 12L);
    }

    private void enqueueToken() {
        server.enqueue(json("{\"code\":0,\"tenant_access_token\":\"token\",\"expire\":7200}"));
    }

    private void enqueueNodes(String nodeToken, String objectToken, long updateTime) {
        server.enqueue(json("{\"code\":0,\"data\":{\"items\":[{"
                + "\"node_token\":\"" + nodeToken + "\","
                + "\"obj_token\":\"" + objectToken + "\","
                + "\"obj_type\":\"docx\",\"title\":\"Title\","
                + "\"obj_edit_time\":\"" + updateTime + "\"}],\"has_more\":false}}"));
    }

    private void enqueueEmptyNodes() {
        server.enqueue(json("{\"code\":0,\"data\":{\"items\":[],\"has_more\":false}}"));
    }

    private void enqueueContent(String content) {
        server.enqueue(json("{\"code\":0,\"data\":{\"content\":\"" + content + "\"}}"));
    }

    private MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private Documents remoteDocument(long id, String token, long updateTime) {
        Documents document = new Documents();
        document.setId(id);
        document.setSpaceId(1L);
        document.setSource("FEISHU");
        document.setRemoteSpaceId("remote-space");
        document.setFeishuNodeToken(token);
        document.setStatus("READY");
        document.setFeishuUpdateTime(updateTime);
        document.setRemoteMissingCount(0);
        return document;
    }
}
