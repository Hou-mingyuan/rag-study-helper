package com.rag.studyhelper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.config.RagProviderResolver;
import com.rag.studyhelper.model.RerankCandidate;
import com.rag.studyhelper.model.RerankOutcome;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class RerankFakeServerTest {

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Test
    void retries429ThenReturnsValidatedExternalRanking() {
        server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "0"));
        server.enqueue(json("""
                {"results":[
                  {"index":1,"relevance_score":0.92},
                  {"index":0,"relevance_score":0.41}
                ]}
                """));
        RerankService service = service(Duration.ofSeconds(2), 1);

        RerankOutcome outcome = service.rerankCandidates("向量检索", candidates(), 2);

        assertEquals("external", outcome.mode());
        assertFalse(outcome.fallback());
        assertEquals("b", outcome.candidates().get(0).id());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void invalidIndexesDoNotEscapeCandidateBoundaryAndUseFallback() {
        server.enqueue(json("""
                {"results":[
                  {"index":99,"relevance_score":1.0},
                  {"index":-1,"relevance_score":0.9}
                ]}
                """));
        RerankService service = service(Duration.ofSeconds(2), 0);

        RerankOutcome outcome = service.rerankCandidates("向量检索", candidates(), 2);

        assertTrue(outcome.fallback());
        assertEquals("invalid response", outcome.detail());
        assertEquals(List.of("a", "b"),
                outcome.candidates().stream().map(candidate -> candidate.id()).toList());
    }

    private RerankService service(Duration timeout, int retries) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
        RerankService service = new RerankService(client, new ObjectMapper());
        RagProviderResolver provider = Mockito.mock(RagProviderResolver.class);
        when(provider.isMockMode()).thenReturn(false);
        ReflectionTestUtils.setField(service, "ragProviderResolver", provider);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", server.url("/").toString());
        ReflectionTestUtils.setField(service, "modelName", "fake-reranker");
        ReflectionTestUtils.setField(service, "maxRetries", retries);
        ReflectionTestUtils.setField(service, "retryBackoffMillis", 0L);
        return service;
    }

    private List<RerankCandidate> candidates() {
        return List.of(
                new RerankCandidate("a", "普通资料", 0.8),
                new RerankCandidate("b", "向量检索资料", 0.7));
    }

    private MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
