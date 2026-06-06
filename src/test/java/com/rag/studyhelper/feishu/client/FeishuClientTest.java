package com.rag.studyhelper.feishu.client;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeishuClientTest {

    private MockWebServer server;
    private FeishuClient client;

    @BeforeEach
    void setUp() {
        server = new MockWebServer();
        client = new FeishuClient(
                "test-app-id", "test-app-secret",
                server.url("/").toString(),
                new OkHttpClient()
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void shouldGetAccessToken() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"test-token\",\"expire\":7200}"));

        String token = client.getAccessToken();
        assertEquals("test-token", token);
    }

    @Test
    void shouldGetWikiNodeTree() throws Exception {
        // Token request
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"tk\",\"expire\":7200}"));
        // Root nodes request
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"items\":[{\"node_token\":\"n1\",\"obj_token\":\"o1\",\"obj_type\":\"doc\",\"title\":\"Doc1\",\"has_child\":false,\"obj_edit_time\":\"1622505600\"}]}}"));
        // Second page: no more items
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"items\":[]}}"));

        List<WikiNode> nodes = client.getWikiNodeTree("space1");

        assertEquals(1, nodes.size());
        assertEquals("n1", nodes.get(0).getNodeToken());
        assertEquals("Doc1", nodes.get(0).getNodeTitle());
        assertFalse(nodes.get(0).isHasChild());
    }

    @Test
    void shouldGetWikiNodeTreeWithChildren() throws Exception {
        // Token
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"tk\",\"expire\":7200}"));
        // Root: one node with children (no page_token, so pagination completes)
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"items\":[{\"node_token\":\"parent\",\"obj_token\":\"op\",\"obj_type\":\"doc\",\"title\":\"Parent\",\"has_child\":true,\"obj_edit_time\":\"1622505600\"}]}}"));
        // Children of parent
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"items\":[{\"node_token\":\"child\",\"obj_token\":\"oc\",\"obj_type\":\"doc\",\"title\":\"Child\",\"has_child\":false,\"obj_edit_time\":\"1622505700\"}]}}"));

        List<WikiNode> nodes = client.getWikiNodeTree("space1");

        assertEquals(2, nodes.size());
        assertTrue(nodes.stream().anyMatch(n -> "parent".equals(n.getNodeToken())));
        assertTrue(nodes.stream().anyMatch(n -> "child".equals(n.getNodeToken())));
    }

    @Test
    void shouldGetDocumentContent() throws Exception {
        // Token
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"tk\",\"expire\":7200}"));
        // Raw content
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"content\":\"Hello from Feishu\"}}"));

        String content = client.getDocumentContent("doc_token");

        assertEquals("Hello from Feishu", content);
    }

    @Test
    void shouldTokenCaching() throws Exception {
        // First token request
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"tenant_access_token\":\"tk1\",\"expire\":7200}"));
        // First API call
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"content\":\"doc1\"}}"));
        // Second API call (should reuse token)
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"content\":\"doc2\"}}"));

        client.getDocumentContent("doc1");
        client.getDocumentContent("doc2");

        // Only 1 token request + 2 content requests = 3 total
        assertEquals(3, server.getRequestCount());
    }
}
