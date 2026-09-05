package com.rag.studyhelper.feishu.client;

import com.rag.studyhelper.feishu.config.FeishuProperties;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuClientReliabilityTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void enumerateReadsEveryPageBeforeReportingComplete() throws Exception {
        enqueueToken();
        server.enqueue(json("""
                {"code":0,"data":{"items":[
                  {"node_token":"n1","obj_token":"d1","obj_type":"docx","title":"One","obj_edit_time":"10"}
                ],"has_more":true,"page_token":"next"}}
                """));
        server.enqueue(json("""
                {"code":0,"data":{"items":[
                  {"node_token":"n2","obj_token":"d2","obj_type":"docx","title":"Two","obj_edit_time":"20"}
                ],"has_more":false}}
                """));

        FeishuEnumeration result = client(1, 0).enumerate("remote-space");

        assertTrue(result.complete());
        assertEquals(2, result.pagesFetched());
        assertEquals(2, result.nodes().size());
        assertEquals(20L, result.maxUpdateTime());
    }

    @Test
    void retryAfter429ProducesOneCompleteResult() throws Exception {
        enqueueToken();
        server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "0"));
        server.enqueue(json("{" +
                "\"code\":0,\"data\":{\"items\":[],\"has_more\":false}}"));

        FeishuEnumeration result = client(1, 0).enumerate("remote-space");

        assertTrue(result.complete());
        assertEquals(1, result.retryCount());
        assertEquals(1, result.pagesFetched());
    }

    @Test
    void missingPageTokenFailsInsteadOfReturningPartialNodes() {
        enqueueToken();
        server.enqueue(json("""
                {"code":0,"data":{"items":[
                  {"node_token":"n1","obj_token":"d1","obj_type":"docx","title":"One","obj_edit_time":"10"}
                ],"has_more":true}}
                """));

        IOException error = assertThrows(IOException.class,
                () -> client(0, 0).enumerate("remote-space"));

        assertTrue(error.getMessage().contains("without page_token"), error::toString);
    }

    @Test
    void readTimeoutExhaustionFailsTheWholeEnumeration() {
        enqueueToken();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        IOException error = assertThrows(IOException.class,
                () -> client(1, 0, Duration.ofMillis(100)).enumerate("remote-space"));

        assertTrue(error.getMessage().contains("failed after 2 attempts"));
    }

    @Test
    void readsDocumentSheetBitableAndPaginatedSpacesWithOneCachedToken() throws Exception {
        enqueueToken();
        server.enqueue(json("{\"code\":0,\"data\":{\"content\":\"document body\"}}"));
        server.enqueue(json("""
                {"code":0,"data":{"sheets":[
                  {"title":"总览","row_count":2,"column_count":28}
                ]}}
                """));
        server.enqueue(json("""
                {"code":0,"data":{"valueRange":{"values":[["姓名","分数"],["甲","95"]]}}}
                """));
        server.enqueue(json("""
                {"code":0,"data":{"items":[{"table_id":"tbl-1","name":"术语表"}],"has_more":false}}
                """));
        server.enqueue(json("""
                {"code":0,"data":{"items":[{"fields":{"术语":"RAG","解释":"检索增强生成"}}],"has_more":false}}
                """));
        server.enqueue(json("""
                {"code":0,"data":{"items":[{"space_id":"s1"}],"has_more":true,"page_token":"p 2"}}
                """));
        server.enqueue(json("""
                {"code":0,"data":{"items":[{"space_id":"s2"}],"has_more":false}}
                """));

        FeishuClient client = client(0, 0);
        assertEquals("document body", client.readContent(node("docx", "doc-token")));
        String sheet = client.readContent(node("sheet", "sheet-token"));
        assertTrue(sheet.contains("=== Sheet: 总览 ==="));
        assertTrue(sheet.contains("姓名 | 分数"));
        String bitable = client.readContent(node("bitable", "app-token"));
        assertTrue(bitable.contains("=== Bitable: 术语表 ==="));
        assertTrue(bitable.contains("检索增强生成"));
        assertEquals(2, client.listSpaces().size());
        assertEquals("token", client.getAccessToken());

        List<String> paths = new java.util.ArrayList<>();
        for (int index = 0; index < 8; index++) {
            paths.add(server.takeRequest().getPath());
        }
        assertEquals(1, paths.stream().filter(path -> path.contains("tenant_access_token")).count());
        assertTrue(paths.stream().anyMatch(path -> path.contains("page_token=p%202")));
        assertTrue(paths.stream().anyMatch(path -> path.contains("AB2")), paths::toString);
    }

    @Test
    void validatesCredentialsSpaceNodeTypeAndObjectTokens() {
        OkHttpClient http = new OkHttpClient();
        FeishuProperties properties = new FeishuProperties();
        assertThrows(IllegalArgumentException.class,
                () -> new FeishuClient("", "secret", server.url("/").toString(), http, properties));
        FeishuClient client = client(0, 0);
        assertThrows(IllegalArgumentException.class, () -> client.enumerate(" "));
        assertThrows(IllegalArgumentException.class, () -> client.readContent(null));
        assertThrows(IllegalArgumentException.class, () -> client.readContent(node("mindnote", "x")));
        assertThrows(IllegalArgumentException.class, () -> client.getDocumentContent(" "));
        assertThrows(IllegalArgumentException.class, () -> client.getSheetContent(null));
        assertThrows(IllegalArgumentException.class, () -> client.getBitableContent(""));
    }

    @Test
    void retryableBusinessCodeRecoversButPermanentCodeDoesNotRetry() throws Exception {
        enqueueToken();
        server.enqueue(json("{\"code\":99991400,\"msg\":\"busy\"}"));
        server.enqueue(json("{\"code\":0,\"data\":{\"items\":[],\"has_more\":false}}"));
        FeishuEnumeration recovered = client(1, 0).enumerate("space");
        assertEquals(1, recovered.retryCount());

        enqueueToken();
        server.enqueue(json("{\"code\":12345,\"msg\":\"permission denied\"}"));
        IOException permanent = assertThrows(IOException.class,
                () -> client(3, 0).enumerate("space"));
        assertTrue(permanent.getMessage().contains("Feishu code 12345"));
    }

    @Test
    void repeatedPaginationTokenAndPageLimitFailClosed() {
        enqueueToken();
        server.enqueue(json("{\"code\":0,\"data\":{\"items\":[],\"has_more\":true,\"page_token\":\"same\"}}"));
        server.enqueue(json("{\"code\":0,\"data\":{\"items\":[],\"has_more\":true,\"page_token\":\"same\"}}"));
        IOException repeated = assertThrows(IOException.class,
                () -> client(0, 0).listSpaces());
        assertTrue(repeated.getMessage().contains("repeated page_token"));

        enqueueToken();
        server.enqueue(json("{\"code\":0,\"data\":{\"items\":[],\"has_more\":true,\"page_token\":\"next\"}}"));
        server.enqueue(json("{\"code\":0,\"data\":{\"items\":[],\"has_more\":false}}"));
        FeishuProperties properties = new FeishuProperties().setMaxRetries(0).setMaxPages(1);
        FeishuClient limited = new FeishuClient("app", "secret", server.url("/").toString(),
                new OkHttpClient(), properties);
        IOException limit = assertThrows(IOException.class, () -> limited.listSpaces());
        assertTrue(limit.getMessage().contains("page limit"));
    }

    private FeishuClient client(int retries, long backoffMillis) {
        return client(retries, backoffMillis, Duration.ofSeconds(2));
    }

    private FeishuClient client(int retries, long backoffMillis, Duration timeout) {
        FeishuProperties properties = new FeishuProperties()
                .setMaxRetries(retries)
                .setInitialBackoffMillis(backoffMillis)
                .setPageSize(2)
                .setMaxPages(20);
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
        return new FeishuClient("app-id", "app-secret", server.url("/").toString(), http, properties);
    }

    private void enqueueToken() {
        server.enqueue(json("{" +
                "\"code\":0,\"tenant_access_token\":\"token\",\"expire\":7200}"));
    }

    private WikiNode node(String type, String token) {
        WikiNode node = new WikiNode();
        node.setObjType(type);
        node.setObjToken(token);
        return node;
    }

    private MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
